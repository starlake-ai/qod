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
  /** Opts in to edits of a source holding secrets: the connection is the config's `endpoint`, and
    * a config without one names none (the superuser rule then holds).
    */
  override def editsHeldSources: Boolean = true
  override def connectionOf(raw: String): Option[String] =
    io.circe.parser.parse(raw).toOption.flatMap(_.hcursor.get[String]("endpoint").toOption)
  /** The one provider every spec installs (the registry is install-once per JVM). A config with
    * `"unquotedToken": true` makes it render the token placeholder outside a literal, which is how
    * specs reach core's refusal of such a template without a second registered provider.
    */
  def renderTemplate(source: FederatedSource, reserved: Set[String]): Either[List[String], String] =
    val unquoted = source.config.flatMap(io.circe.parser.parse(_).toOption)
      .flatMap(_.hcursor.get[Boolean]("unquotedToken").toOption)
      .getOrElse(false)
    val token = if unquoted then "{{secret.TC_TOKEN}}" else "'{{secret.TC_TOKEN}}'"
    Right(
      s"CREATE OR REPLACE SECRET qod_tc_{{alias}} (TYPE test_connector, TOKEN $token);\n" +
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

  it should "refuse a wire longer than the source_type column" in {
    def named(w: String) = new FederatedSourceProvider:
      val wire                                   = w
      val extensionName                          = "x"
      val reservedSecretPrefixes: List[String]   = Nil
      def errors(s: FederatedSource, r: Set[String]) = Nil
      def normalizedConfig(raw: String)          = Right(raw)
      def renderTemplate(s: FederatedSource, r: Set[String]) = Right("")
    FederatedSourceProviders.validate(List(named("a" * 32))) shouldBe Right(())
    FederatedSourceProviders.validate(List(named("a" * 33))).left.toOption.get should include(
      "longer than 32 characters"
    )
  }

  it should "refuse two providers with one wire" in {
    FederatedSourceProviders.validate(List(TestProvider, TestProvider)).isLeft shouldBe true
  }

  "FederatedSourceProviders.of" should "find the provider of a plugin source" in {
    FederatedSourceProviders.of("test_connector") shouldBe Some(TestProvider)
    FederatedSourceProviders.reservedSecretPrefixes should contain("qod_tc_")
  }

  "A provider" should "name no console editor by default" in {
    TestProvider.editor shouldBe None
  }

  it should "opt into no edits of a source holding secrets by default" in {
    val plain = new FederatedSourceProvider:
      val wire                                               = "plain_connector"
      val extensionName                                      = "x"
      val reservedSecretPrefixes: List[String]               = Nil
      def errors(s: FederatedSource, r: Set[String])         = Nil
      def normalizedConfig(raw: String)                      = Right(raw)
      def renderTemplate(s: FederatedSource, r: Set[String]) = Right("")
    plain.editsHeldSources shouldBe false
    plain.connectionOf("""{"endpoint":"https://a"}""") shouldBe None
  }

  "TestProvider" should "name its config's endpoint as its connection" in {
    TestProvider.editsHeldSources shouldBe true
    TestProvider.connectionOf("""{"endpoint":"https://a","collections":["docs"]}""") shouldBe
      Some("https://a")
    TestProvider.connectionOf("""{"collections":["docs"]}""") shouldBe None
    TestProvider.connectionOf("not json") shouldBe None
  }
