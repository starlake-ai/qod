package ai.starlake.quack.ondemand

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager

/** #287: a proxy URL's user and password never go in `http_proxy`, which DuckDB refuses (quoting
  * the value in its error); they go in `http_proxy_username` / `http_proxy_password`.
  */
class DuckDbProxySpec extends AnyFlatSpec with Matchers:

  "DuckDbProxy.statements" should "set host:port alone for a URL with no user" in {
    DuckDbProxy.statements("http://proxy.internal:3128/") shouldBe
      List("SET http_proxy = 'proxy.internal:3128'")
    DuckDbProxy.statements("proxy.internal:3128") shouldBe
      List("SET http_proxy = 'proxy.internal:3128'")
  }

  it should "put a user and password in their own settings, percent-decoded and quoted" in {
    DuckDbProxy.statements("http://zqx%40user:zqx%3A'pass@[::1]:3128") shouldBe List(
      "SET http_proxy = '[::1]:3128'",
      "SET http_proxy_username = 'zqx@user'",
      "SET http_proxy_password = 'zqx:''pass'"
    )
  }

  it should "split at the last @ and the first : of the user part, and drop the path" in {
    DuckDbProxy.statements("http://a:b@c@h:3") shouldBe List(
      "SET http_proxy = 'h:3'",
      "SET http_proxy_username = 'a'",
      "SET http_proxy_password = 'b@c'"
    )
    DuckDbProxy.statements("https://u@h:1/path?x#y") shouldBe List(
      "SET http_proxy = 'h:1'",
      "SET http_proxy_username = 'u'",
      "SET http_proxy_password = ''"
    )
  }

  it should "set no credentials for an empty user part" in {
    DuckDbProxy.statements("http://@h:2") shouldBe List("SET http_proxy = 'h:2'")
  }

  "DuckDbProxy.percentDecode" should "decode UTF-8 escapes and keep +, a stray % and a bad escape" in {
    DuckDbProxy.percentDecode("caf%C3%A9+%zz%4") shouldBe "café+%zz%4"
    DuckDbProxy.percentDecode("100%25") shouldBe "100%"
  }

  "DuckDbProxy.fromEnv" should "read the first non-empty variable, in the spawn scripts' order" in {
    val env = Map("HTTP_PROXY" -> " ", "http_proxy" -> "", "HTTPS_PROXY" -> "http://h:9/")
    DuckDbProxy.fromEnv(env.get) shouldBe List("SET http_proxy = 'h:9'")
    DuckDbProxy.fromEnv(Map.empty[String, String].get) shouldBe Nil
  }

  "the statements" should "run on the manager's DuckDB and set what they name" in {
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    try
      val stmt = conn.createStatement()
      DuckDbProxy
        .statements("http://zqx%40user:zqx%3A'pass@127.0.0.1:3128")
        .foreach(sql => stmt.execute(sql))
      val rs = stmt.executeQuery(
        "SELECT current_setting('http_proxy'), current_setting('http_proxy_username'), " +
          "current_setting('http_proxy_password')"
      )
      rs.next() shouldBe true
      (rs.getString(1), rs.getString(2), rs.getString(3)) shouldBe
        ("127.0.0.1:3128", "zqx@user", "zqx:'pass")
    finally conn.close()
  }
