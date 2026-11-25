package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType}
import ai.starlake.quack.spi.FederatedSourceProvider
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object TestProvider extends FederatedSourceProvider:
  val wire: String          = "test_connector"
  val extensionName: String = "qod_test_connector"
  val reservedSecretPrefixes: List[String] = List("qod_tc_")
  def errors(source: FederatedSource, reserved: Set[String]): List[String] =
    if !source.readOnly then List("test_connector sources are read-only") else Nil
  def normalizedConfig(raw: String): Either[String, String] =
    io.circe.parser.parse(raw).left.map(_.getMessage).map(_.noSpaces)
  def renderTemplate(source: FederatedSource, reserved: Set[String]): Either[List[String], String] =
    Right(
      "CREATE OR REPLACE SECRET qod_tc_{{alias}} (TYPE test_connector, TOKEN '{{secret.TC_TOKEN}}');\n" +
        "ATTACH 'tc:" + source.id + "' AS {{alias}} (TYPE test_connector, SECRET qod_tc_{{alias}}, READ_ONLY);"
    )

class FederatedSourceProvidersSpec extends AnyFlatSpec with Matchers:

  FederatedSourceProviders.install(List(TestProvider)) shouldBe Right(())

  "FederatedSourceType.fromWire" should "resolve a registered plugin wire" in {
    FederatedSourceType.fromWire("test_connector") shouldBe Some(FederatedSourceType.Plugin("test_connector"))
    FederatedSourceType.fromWire("TEST_CONNECTOR") shouldBe Some(FederatedSourceType.Plugin("test_connector"))
  }

  it should "refuse an unregistered wire" in {
    FederatedSourceType.fromWire("nope") shouldBe None
  }

  it should "list plugin wires among the accepted ones" in {
    FederatedSourceType.wireList should include("test_connector")
  }

  "FederatedSourceType.fromWireOrSql" should "never read a stored plugin value as sql" in {
    FederatedSourceType.fromWireOrSql("unloaded_plugin") shouldBe FederatedSourceType.Plugin("unloaded_plugin")
    FederatedSourceType.fromWireOrSql(null) shouldBe FederatedSourceType.Sql
  }

  "A plugin type" should "be rendered and not rdbms" in {
    FederatedSourceType.Plugin("test_connector").rendered shouldBe true
    FederatedSourceType.Plugin("test_connector").rdbms shouldBe false
  }

  "FederatedSourceProviders.install" should "refuse a wire that collides with a built-in type" in {
    val clash = new FederatedSourceProvider:
      val wire                                   = "postgres"
      val extensionName                          = "x"
      val reservedSecretPrefixes: List[String]   = Nil
      def errors(s: FederatedSource, r: Set[String]) = Nil
      def normalizedConfig(raw: String)          = Right(raw)
      def renderTemplate(s: FederatedSource, r: Set[String]) = Right("")
    FederatedSourceProviders.validate(List(clash)).isLeft shouldBe true
  }

  it should "refuse two providers with one wire" in {
    FederatedSourceProviders.validate(List(TestProvider, TestProvider)).isLeft shouldBe true
  }

  "FederatedSourceProviders.of" should "find the provider of a plugin source" in {
    FederatedSourceProviders.of("test_connector") shouldBe Some(TestProvider)
    FederatedSourceProviders.reservedSecretPrefixes should contain("qod_tc_")
  }
