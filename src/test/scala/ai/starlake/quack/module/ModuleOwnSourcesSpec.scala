package ai.starlake.quack.module

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.federation.{FederatedSourceProviders, TestProvider}
import ai.starlake.quack.ondemand.module.ModuleOwnSources
import ai.starlake.quack.ondemand.state.InMemoryFederatedSourceStore
import ai.starlake.quack.spi.{FederatedSourceProvider, ManagerContext, ManagerEvent, ManagerModule}
import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `ctx.ownSources`: a module reads the stored sources of its own types, and a secret's value only
  * when it is held as a value.
  */
class ModuleOwnSourcesSpec extends AnyFlatSpec with Matchers:

  FederatedSourceProviders.install(List(TestProvider)) shouldBe Right(())

  private val store = new InMemoryFederatedSourceStore
  store.upsertSource(
    FederatedSource(
      "fs-own",
      "td-1",
      "own",
      sourceType = FederatedSourceType.Plugin("test_connector"),
      config = Some("{}"),
      readOnly = true
    )
  )
  store.upsertSource(
    FederatedSource(
      "fs-other",
      "td-1",
      "other",
      sourceType = FederatedSourceType.Plugin("other_connector"),
      config = Some("{}")
    )
  )
  store.upsertSource(
    FederatedSource(
      "fs-pg",
      "td-1",
      "pg",
      sourceType = FederatedSourceType.Postgres,
      config = Some("{}")
    )
  )
  store.upsertSecret(FederatedSecret("s-1", "fs-own", "TC_TOKEN", Some("k-own"), None))
  store.upsertSecret(FederatedSecret("s-2", "fs-own", "TC_REF", None, Some("env:QOD_TC")))
  store.upsertSecret(FederatedSecret("s-3", "fs-other", "TC_TOKEN", Some("k-other"), None))
  store.upsertSecret(FederatedSecret("s-4", "fs-pg", "PG_PWD", Some("k-pg"), None))

  private val own = new ModuleOwnSources(Set("test_connector"), store)

  private def late(w: String): FederatedSourceProvider = new FederatedSourceProvider:
    val wire                                               = w
    val extensionName                                      = "x"
    val reservedSecretPrefixes: List[String]               = Nil
    def errors(s: FederatedSource, r: Set[String])         = Nil
    def normalizedConfig(raw: String)                      = Right(raw)
    def renderTemplate(s: FederatedSource, r: Set[String]) = Right("")

  private def module(ps: List[FederatedSourceProvider]): ManagerModule = new ManagerModule:
    def name                              = "own-test"
    def changelogPath                     = None
    def start(ctx: ManagerContext)        = IO.unit
    def endpoints                         = Nil
    def publicPathPrefixes                = Set.empty
    override def federatedSourceProviders = ps
    def onEvent(event: ManagerEvent)      = IO.unit
    def stop                              = IO.unit

  "ModuleOwnSources.source" should "return a stored source of the module's own type" in {
    own.source("td-1", "own").map(_.id) shouldBe Some("fs-own")
  }

  it should "return nothing for another module's type, a built-in type or an unknown alias" in {
    own.source("td-1", "other") shouldBe None
    own.source("td-1", "pg") shouldBe None
    own.source("td-1", "ghost") shouldBe None
    own.source("td-2", "own") shouldBe None
  }

  "ModuleOwnSources.secretValue" should "return the value of a secret held as a value" in {
    own.secretValue("td-1", "own", "TC_TOKEN") shouldBe Some("k-own")
  }

  it should "return nothing for an externalRef secret, a missing one, or a source it does not own" in {
    own.secretValue("td-1", "own", "TC_REF") shouldBe None
    own.secretValue("td-1", "own", "NOPE") shouldBe None
    own.secretValue("td-1", "other", "TC_TOKEN") shouldBe None
    own.secretValue("td-1", "pg", "PG_PWD") shouldBe None
  }

  "ModuleOwnSources.of" should "own the wires of the module's providers that the registry holds" in {
    ModuleOwnSources.of(module(List(TestProvider, late("late_connector"))), store).wires shouldBe
      Set("test_connector")
    ModuleOwnSources.of(module(Nil), store).wires shouldBe empty
  }

  "ModuleOwnSources.Empty" should "own nothing" in {
    ModuleOwnSources.Empty.source("td-1", "own") shouldBe None
    ModuleOwnSources.Empty.secretValue("td-1", "own", "TC_TOKEN") shouldBe None
  }
