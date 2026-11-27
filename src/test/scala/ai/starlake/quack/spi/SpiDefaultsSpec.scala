package ai.starlake.quack.spi

import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.server.ServerEndpoint

class SpiDefaultsSpec extends AnyFlatSpec with Matchers:

  private class MinimalModule extends ManagerModule:
    def name: String                             = "minimal"
    def changelogPath: Option[String]            = None
    def start(ctx: ManagerContext): IO[Unit]     = IO.unit
    def endpoints: List[ServerEndpoint[Any, IO]] = Nil
    def publicPathPrefixes: Set[String]          = Set.empty
    def onEvent(event: ManagerEvent): IO[Unit]   = IO.unit
    def stop: IO[Unit]                           = IO.unit

  "ManagerModule" should "default staticMounts to Nil" in {
    (new MinimalModule).staticMounts shouldBe Nil
  }

  it should "default mutationGates to Nil" in {
    val m = new MinimalModule
    m.mutationGates shouldBe Nil
  }

  it should "default the platform surfaces to none" in {
    val m = new MinimalModule
    m.permissions shouldBe Nil
    m.staffPathPrefixes shouldBe empty
    m.consolePages shouldBe Nil
  }

  "ManagerEventSink.noop" should "accept events without effect" in {
    noException should be thrownBy ManagerEventSink.noop.emit(
      ManagerEvent.TenantCreated("acme")
    )
  }

  "FederatedSourceProvider" should "default the tenant-aware errors and renderTemplate to the old ones" in {
    val p = new FederatedSourceProvider:
      def wire: String                         = "spi_default"
      def extensionName: String                = "x"
      def reservedSecretPrefixes: List[String] = Nil
      def errors(source: ai.starlake.quack.model.FederatedSource, reserved: Set[String]): List[String] =
        List(s"old errors ${source.id}")
      def normalizedConfig(raw: String): Either[String, String] = Right(raw)
      def renderTemplate(
          source: ai.starlake.quack.model.FederatedSource,
          reserved: Set[String]
      ): Either[List[String], String] = Right(s"old render ${source.id}")
    val src = ai.starlake.quack.model.FederatedSource("s1", "td-1", "a")
    p.errors(src, "acme", Set.empty) shouldBe List("old errors s1")
    p.renderTemplate(src, "acme", Set.empty) shouldBe Right("old render s1")
  }
