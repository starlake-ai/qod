package ai.starlake.quack.ondemand

import ai.starlake.quack.model.SqlLiterals

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** The `SET`s that make a DuckDB connection's INSTALL go through the proxy the environment names:
  * DuckDB 2.0 reads `HTTP_PROXY` alone, raw, as the default of `http_proxy`, which takes
  * `host:port` only: a value holding a user and password is stored, then refused at the first HTTP
  * request with an error quoting it, password included. So a URL's user and password go in
  * `http_proxy_username` / `http_proxy_password`, percent-decoded, which DuckDB sends as the
  * proxy's Basic credentials (#287). The same split as both spawn scripts and the qod CLI's
  * `_duckdb_proxy_sql`.
  */
object DuckDbProxy:

  /** The variables read, first non-empty one wins, in the spawn scripts' order. */
  val EnvKeys: List[String] = List("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy")

  def fromEnv(env: String => Option[String]): List[String] =
    EnvKeys.iterator.flatMap(env(_)).map(_.trim).find(_.nonEmpty).map(statements).getOrElse(Nil)

  /** The statements for proxy URL `url`, with or without a scheme; the URL's path is dropped. */
  def statements(url: String): List[String] =
    val afterScheme = url.indexOf("://") match
      case -1 => url
      case i  => url.substring(i + 3)
    val authority = afterScheme.takeWhile(c => c != '/' && c != '?' && c != '#')
    val at        = authority.lastIndexOf('@')
    val hostPort  = s"SET http_proxy = ${SqlLiterals.duckdbLiteral(authority.substring(at + 1))}"
    if at <= 0 then List(hostPort)
    else
      val userInfo         = authority.substring(0, at)
      val (user, password) = userInfo.indexOf(':') match
        case -1 => (userInfo, "")
        case c  => (userInfo.substring(0, c), userInfo.substring(c + 1))
      List(
        hostPort,
        s"SET http_proxy_username = ${SqlLiterals.duckdbLiteral(percentDecode(user))}",
        s"SET http_proxy_password = ${SqlLiterals.duckdbLiteral(percentDecode(password))}"
      )

  /** `s` with each `%XX` decoded as a UTF-8 byte; a `%` not followed by two hex digits is kept. Not
    * `URLDecoder`, which also reads `+` as a space.
    */
  private[ondemand] def percentDecode(s: String): String =
    val out = new ByteArrayOutputStream()
    val raw = s.getBytes(StandardCharsets.UTF_8)
    var i   = 0
    while i < raw.length do
      val hex = if raw(i) == '%' && i + 2 < raw.length then hexByte(raw(i + 1), raw(i + 2)) else -1
      if hex >= 0 then
        out.write(hex)
        i += 3
      else
        out.write(raw(i).toInt)
        i += 1
    new String(out.toByteArray, StandardCharsets.UTF_8)

  private def hexByte(hi: Byte, lo: Byte): Int =
    val h = Character.digit(hi.toChar, 16)
    val l = Character.digit(lo.toChar, 16)
    if h < 0 || l < 0 then -1 else h * 16 + l
