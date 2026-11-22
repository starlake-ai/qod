package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.ondemand.DuckDbProxy
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** #287: both spawn scripts write a node's proxy settings exactly as [[DuckDbProxy]] (the manager's
  * DuckLake initializer) does, so a proxy URL's user and password never go in `http_proxy`. The
  * PowerShell cases are cancelled on a host with no PowerShell.
  */
class SpawnScriptProxySpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val Urls = List(
    "http://proxy.internal:3128/",
    "proxy.internal:3128",
    "http://zqx%40user:zqx%3A'pass@[::1]:3128",
    "http://a:b@c@h:3",
    "https://u@h:1/path?x#y",
    "http://@h:2",
    "http://u:caf%C3%A9+%zz%4@h:4"
  )

  private val ProxyVars = DuckDbProxy.EnvKeys

  /** The proxy lines of a dry run's init SQL, with `url` in HTTP_PROXY and every other proxy
    * variable of this JVM's environment blanked.
    */
  private def proxyLines(cmd: Seq[String], url: String): List[String] =
    val env = Seq(
      "QOD_SPAWN_DRY_RUN" -> "1",
      "kind"              -> "memory",
      "dbName"            -> "m",
      "schemaName"        -> "main"
    ) ++ ProxyVars.map(_ -> "") :+ ("HTTP_PROXY" -> url)
    runSpawn(cmd, env).linesIterator.map(_.trim).filter(_.startsWith("SET http_proxy")).toList

  private def expected(url: String): List[String] = DuckDbProxy.statements(url).map(_ + ";")

  "spawn-quack-node.sh" should "write the proxy settings DuckDbProxy writes" in
    Urls.foreach { url =>
      withClue(url)(
        proxyLines(Seq("bash", "scripts/spawn-quack-node.sh", "21999"), url) shouldBe
          expected(url)
      )
    }

  it should "write no proxy setting without a proxy variable" in {
    proxyLines(Seq("bash", "scripts/spawn-quack-node.sh", "21999"), "") shouldBe Nil
  }

  "spawn-quack-node.ps1" should "write the proxy settings DuckDbProxy writes" in {
    assume(PowerShell.command.nonEmpty, "no PowerShell on this host")
    val ps1 = PowerShell.command.get ++ Seq("-File", "scripts/spawn-quack-node.ps1", "21999")
    Urls.foreach(url => withClue(url)(proxyLines(ps1, url) shouldBe expected(url)))
    proxyLines(ps1, "") shouldBe Nil
  }
