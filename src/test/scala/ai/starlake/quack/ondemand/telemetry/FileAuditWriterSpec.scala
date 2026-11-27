package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.{AuditFileConfig, TelemetryConfig}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class FileAuditWriterSpec extends AnyFlatSpec with Matchers:

  private val posix =
    java.nio.file.FileSystems.getDefault.supportedFileAttributeViews.contains("posix")

  private def withDir(test: Path => Unit): Unit =
    val root = Files.createTempDirectory("qod-audit-file")
    try test(root)
    finally
      Files.walk(root).sorted(Comparator.reverseOrder()).iterator.asScala.foreach { p =>
        p.toFile.setWritable(true)
        Files.deleteIfExists(p)
      }

  private def read(p: Path): String  = Files.readString(p, StandardCharsets.UTF_8)
  private def perms(p: Path): String =
    PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

  /** 29 characters and its newline: three fit in a 100-byte file. */
  private def line(i: Int): String = f"""{"n":"$i%03d","pad":"${"x" * 9}"}"""

  private def writer(
      path: Path,
      maxBytes: Long = 100,
      maxFiles: Int = 10,
      fsync: FiniteDuration = 1.hour
  ) =
    new FileAuditWriter(path, maxBytes, maxFiles, fsync)

  "FileAuditWriter" should "append each line, creating the folder 0700 and the file 0600" in withDir {
    root =>
      val path = root.resolve("a/b/audit.ndjson")
      val w    = writer(path, maxBytes = 1 << 20, fsync = Duration.Zero)
      w.append("one")
      w.append("two")
      w.close()
      read(path) shouldBe "one\ntwo\n"
      if posix then
        perms(path) shouldBe "rw-------"
        perms(path.getParent) shouldBe "rwx------"
  }

  it should "make the file and its folder group readable on request, and tighten a file found open" in withDir {
    root =>
      assume(posix)
      val path = root.resolve("g/audit.ndjson")
      new FileAuditWriter(path, 1 << 20, 10, 1.hour, groupReadable = true).close()
      perms(path) shouldBe "rw-r-----"
      perms(path.getParent) shouldBe "rwxr-x---"
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw-rw-"))
      writer(path).close()
      perms(path) shouldBe "rw-------"
  }

  it should "rotate by rename before a line that would pass the size, never splitting one" in withDir {
    root =>
      val path = root.resolve("audit.ndjson")
      val w    = writer(path)
      (1 to 7).foreach(i => w.append(line(i)))
      w.close()
      read(root.resolve("audit.ndjson.2")) shouldBe (1 to 3).map(line(_) + "\n").mkString
      read(root.resolve("audit.ndjson.1")) shouldBe (4 to 6).map(line(_) + "\n").mkString
      read(path) shouldBe line(7) + "\n"
      if posix then perms(root.resolve("audit.ndjson.1")) shouldBe "rw-------"
  }

  it should "delete the oldest file at the cap and count each deletion" in withDir { root =>
    val path    = root.resolve("audit.ndjson")
    val deleted = new AtomicInteger
    val w       = writer(path, maxFiles = 2)
    w.onDeleted { () => deleted.incrementAndGet(); () }
    (1 to 15).foreach(i => w.append(line(i)))
    w.close()
    deleted.get shouldBe 2
    Files.exists(root.resolve("audit.ndjson.3")) shouldBe false
    List("audit.ndjson.2", "audit.ndjson.1", "audit.ndjson")
      .map(f => read(root.resolve(f)))
      .mkString shouldBe
      (7 to 15).map(line(_) + "\n").mkString
  }

  it should "resume a file found at boot, finishing a torn last line and counting its size" in withDir {
    root =>
      val path = root.resolve("audit.ndjson")
      Files.writeString(path, line(1) + "\n" + """{"n":2,"to""")
      val w = writer(path)
      w.append(line(3))
      w.append(line(4)) // 30 + 10 + 1 + 30 bytes so far: this one rotates
      w.close()
      read(root.resolve("audit.ndjson.1")) shouldBe line(1) + "\n" + """{"n":2,"to""" + "\n" + line(
        3
      ) + "\n"
      read(path) shouldBe line(4) + "\n"
  }

  it should "refuse a path it cannot create" in withDir { root =>
    Files.writeString(root.resolve("file"), "")
    an[java.io.IOException] should be thrownBy writer(root.resolve("file/audit.ndjson"))
  }

  it should "let the sink drop and count a line it cannot write, then carry on" in withDir { root =>
    assume(posix && System.getProperty("user.name") != "root") // root writes a read-only folder
    val dir     = root.resolve("logs")
    val path    = dir.resolve("audit.ndjson")
    val w       = writer(path)
    val dropped = new AtomicInteger
    (1 to 3).foreach(i => w.append(line(i)))
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
    val sink = new StdoutTelemetrySink(new RecordingTelemetryStore, out = w.append)
    sink.onDropCounter { n => dropped.addAndGet(n); () }
    sink.appendStatements(List(stmt(4))) // does not fit, and the folder refuses the rotation
    sink.close()
    dropped.get shouldBe 1
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    w.append(line(5))
    w.close()
    read(root.resolve("logs/audit.ndjson.1")) shouldBe (1 to 3).map(line(_) + "\n").mkString
    read(path) shouldBe line(5) + "\n"
    an[IllegalStateException] should be thrownBy w.append("late")
  }

  it should "write every line the sink renders, in order, as JSON tagged by qodEvent" in withDir {
    root =>
      val path = root.resolve("audit.ndjson")
      val w    = writer(path, maxBytes = 1 << 20, fsync = 20.millis)
      val sink = new StdoutTelemetrySink(
        new RecordingTelemetryStore,
        out = w.append,
        onClosed = () => w.close()
      )
      sink.appendAudit(List(audit))
      sink.appendStatements((1 to 50).map(stmt).toList)
      sink.close()
      val lines = read(path).linesIterator.toList
      lines.size shouldBe 51
      lines.map(l =>
        io.circe.parser.parse(l).toOption.get.hcursor.get[String]("qodEvent").toOption.get
      ) shouldBe
        ("audit" :: List.fill(50)("statement"))
  }

  private val complete = AuditFileConfig(path = "/var/log/qod/audit.ndjson")

  "AuditFileConfig" should "accept a complete block and refuse a missing, relative or out-of-range one" in {
    complete.validate shouldBe Right(())
    val refusals = List(
      complete.copy(path = " ")                 -> "QOD_AUDIT_FILE_PATH",
      complete.copy(path = "logs/audit.ndjson") -> "QOD_AUDIT_FILE_PATH",
      complete.copy(maxFileBytes = 1000)        -> "QOD_AUDIT_FILE_MAX_BYTES",
      complete.copy(maxFiles = 0)               -> "QOD_AUDIT_FILE_MAX_FILES",
      complete.copy(fsyncIntervalSec = -1)      -> "QOD_AUDIT_FILE_FSYNC_INTERVAL_SEC"
    )
    refusals.foreach((cfg, envVar) => cfg.validate.left.toOption.get should include(envVar))
  }

  "TelemetryConfig.validate" should "accept the file sink only with a path and a store" in {
    TelemetryConfig.validate("postgres", 7, "file", complete) shouldBe Right(())
    TelemetryConfig.validate("postgres", 7, "file").isLeft shouldBe true
    TelemetryConfig.validate("none", 7, "file", complete).isLeft shouldBe true
  }

  private def stmt(i: Int) =
    StatementEvent(Instant.now(), "alice", "acme", "p1", "n1", s"SELECT $i", 3L, None, "ok", None)

  private val audit = AuditEvent(
    Instant.now(),
    "data-denial",
    "alice",
    "tenant",
    Some("acme"),
    "sql.denied",
    Some("sales.orders"),
    "denied",
    "flightsql",
    Map("verb" -> "Read")
  )
