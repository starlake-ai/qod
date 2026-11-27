package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, FederatedSourceType}
import ai.starlake.quack.spi.FederatedSourceProvider
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `heldEdit`: what a write over a stored source holding secrets does with them, for a module type
  * that opts in (`editsHeldSources` and `connectionOf`). The providers are passed in, so no
  * registry is touched.
  */
class FederatedSourceHeldEditSpec extends AnyFlatSpec with Matchers:

  private def endpointOf(raw: String): Option[String] =
    io.circe.parser.parse(raw).toOption.flatMap(_.hcursor.get[String]("endpoint").toOption)

  private def provider(w: String, opts: Boolean, conn: String => Option[String]) =
    new FederatedSourceProvider:
      val wire                                               = w
      val extensionName                                      = "x"
      val reservedSecretPrefixes: List[String]               = Nil
      def errors(s: FederatedSource, r: Set[String])         = Nil
      def normalizedConfig(raw: String)                      = Right(raw)
      def renderTemplate(s: FederatedSource, r: Set[String]) = Right("")
      override def editsHeldSources                          = opts
      override def connectionOf(raw: String)                 = conn(raw)

  private val providers: Map[String, FederatedSourceProvider] = Map(
    "opted"     -> provider("opted", opts = true, endpointOf),
    "unflagged" -> provider("unflagged", opts = false, endpointOf),
    "unnamed"   -> provider("unnamed", opts = true, _ => None)
  )

  private val opted = FederatedSourceType.Plugin("opted")

  private def source(t: FederatedSourceType, endpoint: String, collection: String = "docs") =
    FederatedSource(
      id = "fs-1",
      tenantDbId = "td-1",
      alias = "docs",
      sourceType = t,
      config = Some(s"""{"endpoint":"$endpoint","collections":["$collection"]}"""),
      readOnly = true
    )

  private val plain = FederatedSecret("s-1", "fs-1", "TOKEN", Some("v"), None)
  private val ref   = FederatedSecret("s-2", "fs-1", "OTHER", None, Some("env:QOD_TOKEN"))

  private def heldEdit(
      stored: FederatedSource,
      written: FederatedSource,
      held: List[FederatedSecret] = List(plain)
  ): HeldEdit =
    FederatedSourceRules.heldEdit(stored, written, held, providers.get)

  "heldEdit" should "keep the secrets of an opted source whose connection stays" in {
    heldEdit(source(opted, "https://a"), source(opted, "https://a", collection = "faq")) shouldBe
      HeldEdit.KeepsSecrets
    heldEdit(source(opted, "https://a"), source(opted, "https://a")) shouldBe HeldEdit.KeepsSecrets
  }

  it should "clear the secrets of an opted source whose connection changes" in {
    heldEdit(source(opted, "https://a"), source(opted, "https://b")) shouldBe HeldEdit.ClearsSecrets
  }

  it should "leave a source holding an externalRef secret to the superuser rule" in {
    heldEdit(source(opted, "https://a"), source(opted, "https://a", "faq"), List(plain, ref)) shouldBe
      HeldEdit.SuperuserOnly
    heldEdit(source(opted, "https://a"), source(opted, "https://b"), List(ref)) shouldBe
      HeldEdit.SuperuserOnly
  }

  it should "leave a type that does not opt in to the superuser rule" in {
    val t = FederatedSourceType.Plugin("unflagged")
    heldEdit(source(t, "https://a"), source(t, "https://b")) shouldBe HeldEdit.SuperuserOnly
  }

  it should "leave a config whose connection the provider cannot name to the superuser rule" in {
    val t = FederatedSourceType.Plugin("unnamed")
    heldEdit(source(t, "https://a"), source(t, "https://a", "faq")) shouldBe HeldEdit.SuperuserOnly
    val unreadable = source(opted, "https://a").copy(config = Some("not json"))
    heldEdit(source(opted, "https://a"), unreadable) shouldBe HeldEdit.SuperuserOnly
    heldEdit(unreadable, source(opted, "https://a")) shouldBe HeldEdit.SuperuserOnly
    heldEdit(source(opted, "https://a").copy(config = None), source(opted, "https://a")) shouldBe
      HeldEdit.SuperuserOnly
  }

  it should "leave a provider that is not loaded, a built-in type and a change of type to the superuser rule" in {
    val gone = FederatedSourceType.Plugin("gone")
    heldEdit(source(gone, "https://a"), source(gone, "https://a")) shouldBe HeldEdit.SuperuserOnly
    val pg = FederatedSourceType.Postgres
    heldEdit(source(pg, "https://a"), source(pg, "https://a")) shouldBe HeldEdit.SuperuserOnly
    val other = FederatedSourceType.Plugin("unflagged")
    heldEdit(source(opted, "https://a"), source(other, "https://a")) shouldBe
      HeldEdit.SuperuserOnly
  }

  it should "restrict nothing for a source holding no secret" in {
    heldEdit(source(opted, "https://a"), source(opted, "https://b"), Nil) shouldBe
      HeldEdit.KeepsSecrets
  }
