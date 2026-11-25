package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, FederatedSourceType}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PluginSourceRenderingSpec extends AnyFlatSpec with Matchers with OptionValues:

  FederatedSourceProviders.install(List(TestProvider)) shouldBe Right(())

  private val plugin = FederatedSourceType.Plugin("test_connector")

  private def src(readOnly: Boolean = true, config: String = """{"endpoint":"x"}""") =
    FederatedSource("src-tc", "td-1", "tc", sourceType = plugin, config = Some(config), readOnly = readOnly)

  private def builder(sources: List[FederatedSource], secrets: Map[String, List[FederatedSecret]]) =
    new FederationBlobBuilder(
      loadEnabled = _ => IO.pure(sources),
      loadSecrets = id => IO.pure(secrets.getOrElse(id, Nil)),
      resolver = new PostgresSecretResolver(),
      catalogAliasOf = _ => IO.pure(None)
    )

  "FederatedSourceRules.errors" should "delegate to the provider" in {
    FederatedSourceRules.errors(src(readOnly = false), Set.empty) should contain(
      "test_connector sources are read-only"
    )
    FederatedSourceRules.errors(src(), Set.empty) shouldBe Nil
  }

  it should "refuse a source whose provider is not loaded" in {
    val orphan = src().copy(sourceType = FederatedSourceType.Plugin("unloaded_plugin"))
    val msg    = FederatedSourceRules.errors(orphan, Set.empty).mkString
    msg should include("unknown sourceType 'unloaded_plugin' (expected one of: sql, ")
    msg should include("test_connector")
    msg should include("or its module is not loaded")
  }

  it should "reserve the provider's secret prefixes from sql sources" in {
    val sql = FederatedSource("s", "td-1", "s", "CREATE SECRET qod_tc_x (TYPE s3);")
    FederatedSourceRules.errors(sql, Set.empty).mkString should include("qod_tc_")
  }

  "FederatedSourceRules.sameConfig" should "compare normalized plugin config" in {
    FederatedSourceRules.sameConfig(plugin, Some("""{"a":1, "b":2}"""), Some("""{"a":1,"b":2}""")) shouldBe true
  }

  it should "not throw for an unloaded provider and compare the raw text" in {
    val orphan = FederatedSourceType.Plugin("unloaded_plugin")
    FederatedSourceRules.sameConfig(orphan, Some("a"), Some("a")) shouldBe true
    FederatedSourceRules.sameConfig(orphan, Some("a"), Some("b")) shouldBe false
  }

  "FederationBlobBuilder.build" should "render a plugin source and resolve its secrets" in {
    val s    = src()
    val secs = Map(s.id -> List(FederatedSecret("sec-1", s.id, "TC_TOKEN", Some("t0k'en"), None)))
    val blob = builder(List(s), secs).build("td-1").unsafeRunSync().value
    blob should include("ATTACH 'tc:src-tc' AS tc (TYPE test_connector, SECRET qod_tc_tc, READ_ONLY);")
    blob should include("TOKEN 't0k''en'")
  }

  it should "leave an orphan plugin source out and keep the tenant-db's other sources" in {
    val orphan = FederatedSource(
      "src-orph", "td-1", "orph", sourceType = FederatedSourceType.Plugin("unloaded_plugin"),
      config = Some("{}")
    )
    val sql  = FederatedSource("src-sql", "td-1", "good", "ATTACH 'x' AS {{alias}};")
    val blob = builder(List(orphan, sql), Map.empty).build("td-1").unsafeRunSync().value
    blob should include("-- BEGIN federation: good")
    blob should not include "-- BEGIN federation: orph"
  }

  it should "refuse a plugin template that puts a secret outside a literal and keep its siblings" in {
    val bad  = src(config = """{"endpoint":"x","unquotedToken":true}""")
    val sql  = FederatedSource("src-sql", "td-1", "good", "ATTACH 'x' AS {{alias}};")
    // A plain token value: a sql template would fall back and substitute it; a plugin one never does.
    val secs = Map(bad.id -> List(FederatedSecret("sec-1", bad.id, "TC_TOKEN", Some("plain"), None)))
    val b    = builder(List(bad, sql), secs)
    val blob = b.build("td-1").unsafeRunSync().value
    blob should include("-- BEGIN federation: good")
    blob should not include "-- BEGIN federation: tc"
    blob should not include "plain"
    b.logSafePreview("td-1").unsafeRunSync().value should not include "-- BEGIN federation: tc"
    val err = b.buildOne(bad).attempt.unsafeRunSync().left.toOption.value
    err shouldBe a[PluginSecretUnquoted]
    err.getMessage should include("'tc'")
    err.getMessage should include("test_connector")
    err.getMessage should not include "plain"
  }

  it should "still render the provider's quoted template" in {
    val s    = src()
    val secs = Map(s.id -> List(FederatedSecret("sec-1", s.id, "TC_TOKEN", Some("plain"), None)))
    builder(List(s), secs).build("td-1").unsafeRunSync().value should include("TOKEN 'plain'")
  }
