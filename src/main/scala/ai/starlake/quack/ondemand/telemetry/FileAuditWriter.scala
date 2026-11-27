package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.AuditFileConfig
import com.typesafe.scalalogging.LazyLogging

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption.{APPEND, CREATE, READ, WRITE}
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.duration.*
import scala.util.Using
import scala.util.control.NonFatal

/** `telemetry.auditSink = file`: the `out` of [[StdoutTelemetrySink]], so it runs on that sink's
  * writer thread behind its bounded queue, never on a statement's. Each line is appended to `path`,
  * which a log shipper tails (examples/audit-shipper).
  *
  * Rotation is by size and by rename, as a shipper's file source expects: when the next line would
  * pass `maxFileBytes`, `<path>.N-1` becomes `<path>.N`, ..., `<path>` becomes `<path>.1`, and a
  * fresh `<path>` starts. At the cap the oldest, `<path>.<maxFiles>`, is deleted, logged at ERROR
  * and counted (`onDeleted`, `qod_audit_file_deleted_total`), as it may not have been shipped; its
  * events are still in `qodstate_audit` / `qodstate_stmt_history` within their retention.
  *
  * A write that fails (a full or read-only disk) throws, so the sink drops and counts that line,
  * and the next line starts on a line of its own, so a torn line never swallows it (the same check
  * runs on the file found at boot). The file is forced to disk every `fsyncInterval`, or after
  * every line when it is zero, so a host crash loses at most what was written since. On a POSIX
  * file system the file is 0600 and a folder this creates 0700, or 0640 / 0750 with
  * `groupReadable`, for a shipper in the manager's group.
  */
final class FileAuditWriter(
    path: Path,
    maxFileBytes: Long,
    maxFiles: Int,
    fsyncInterval: FiniteDuration,
    groupReadable: Boolean = false
) extends LazyLogging:
  require(maxFiles >= 1, "maxFiles must be at least 1")

  private val posix     = path.getFileSystem.supportedFileAttributeViews.contains("posix")
  private val filePerms =
    PosixFilePermissions.fromString(if groupReadable then "rw-r-----" else "rw-------")
  private val dirPerms =
    PosixFilePermissions.fromString(if groupReadable then "rwxr-x---" else "rwx------")

  @volatile private var deletedHook: () => Unit = () => ()

  /** Wire the deletion counter after construction (metrics come up after the store). */
  def onDeleted(f: () => Unit): Unit = deletedHook = f

  // Guarded by `this`. `channel` is None only after a rotation whose reopen failed, or once closed.
  private var channel: Option[FileChannel] = Some(open())
  private var size: Long                   = channel.get.size()
  private var torn                         = true
  private var dirty                        = false
  private var closed                       = false

  private val syncer: Option[ScheduledExecutorService] =
    Option.when(fsyncInterval > Duration.Zero) {
      val s = Executors.newSingleThreadScheduledExecutor { r =>
        val t = new Thread(r, "qod-audit-file-fsync"); t.setDaemon(true); t
      }
      val ms = fsyncInterval.toMillis
      s.scheduleWithFixedDelay(() => sync(), ms, ms, TimeUnit.MILLISECONDS)
      s
    }

  def append(line: String): Unit = synchronized {
    if closed then throw new IllegalStateException("audit file closed")
    val bytes = (line + "\n").getBytes(StandardCharsets.UTF_8)
    val ch    = channel.getOrElse {
      val c = open(); channel = Some(c); size = c.size(); torn = true; c
    }
    if torn then
      size = ch.size()
      // An unreadable tail gets a newline too: an empty line costs nothing, a glued one an event.
      if size > 0 && scala.util.Try(lastByte()).fold(_ => true, _ != '\n') then
        write(ch, Array('\n'.toByte))
      torn = false
    val target = if size > 0 && size + bytes.length > maxFileBytes then rotate(ch) else ch
    write(target, bytes)
    if fsyncInterval == Duration.Zero then target.force(false)
  }

  /** Forces what was written to disk (the timer does it every `fsyncInterval`). */
  def sync(): Unit = synchronized {
    if dirty then
      try { channel.foreach(_.force(false)); dirty = false }
      catch case NonFatal(_) => () // a failing disk fails the next write, which is counted
  }

  def close(): Unit =
    syncer.foreach(_.shutdownNow())
    synchronized {
      if !closed then
        closed = true
        channel.foreach(c =>
          try c.force(false)
          finally c.close()
        )
        channel = None
    }

  private def write(ch: FileChannel, bytes: Array[Byte]): Unit =
    val buf = ByteBuffer.wrap(bytes)
    try
      while buf.hasRemaining do size += ch.write(buf)
      dirty = true
    catch
      case e: Throwable =>
        torn = true
        throw e

  /** Shifts the rotated files up one, deleting the oldest at the cap, and returns a fresh file for
    * the line that did not fit, so a line is never split across two files.
    */
  private def rotate(ch: FileChannel): FileChannel =
    // A file someone else removed is not shifted: rotating anyway would delete a rotated file per
    // line while the move kept failing.
    if Files.exists(path) then
      val oldest = rotated(maxFiles)
      if Files.exists(oldest) then
        val bytes = Files.size(oldest)
        Files.delete(oldest)
        logger.error(
          s"audit file: deleted $oldest ($bytes bytes) at the rotation cap (QOD_AUDIT_FILE_MAX_FILES" +
            s"=$maxFiles); if the shipper had not read it, its events are only in qodstate_audit " +
            "and qodstate_stmt_history, within their retention"
        )
        try deletedHook()
        catch case NonFatal(_) => ()
      (maxFiles - 1 to 1 by -1).foreach { i =>
        if Files.exists(rotated(i)) then Files.move(rotated(i), rotated(i + 1))
      }
      ch.force(false)
      Files.move(path, rotated(1))
    channel = None
    ch.close()
    val fresh = open()
    channel = Some(fresh)
    size = 0
    torn = false
    fresh

  private def rotated(i: Int): Path = path.resolveSibling(s"${path.getFileName}.$i")

  private def lastByte(): Byte =
    Using.resource(FileChannel.open(path, READ)) { r =>
      val b = ByteBuffer.allocate(1)
      r.read(b, r.size() - 1)
      b.get(0)
    }

  private def open(): FileChannel =
    val dir = path.toAbsolutePath.getParent
    if !Files.isDirectory(dir) then
      if posix then
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(dirPerms))
        Files.setPosixFilePermissions(dir, dirPerms) // the umask may have cut the group's bits
      else Files.createDirectories(dir)
    if posix then
      val ch = FileChannel.open(
        path,
        java.util.Set.of(CREATE, WRITE, APPEND),
        PosixFilePermissions.asFileAttribute(filePerms)
      )
      try Files.setPosixFilePermissions(path, filePerms) // a file found at boot, or a umask
      catch
        case e: Throwable =>
          ch.close()
          throw e
      ch
    else FileChannel.open(path, CREATE, WRITE, APPEND)

object FileAuditWriter:
  def apply(cfg: AuditFileConfig): FileAuditWriter =
    new FileAuditWriter(
      Path.of(cfg.path),
      cfg.maxFileBytes,
      cfg.maxFiles,
      cfg.fsyncIntervalSec.seconds,
      cfg.groupReadable
    )
