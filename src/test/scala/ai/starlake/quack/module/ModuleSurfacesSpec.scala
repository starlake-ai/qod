package ai.starlake.quack.module

import ai.starlake.quack.docs.DocEndpoints
import ai.starlake.quack.ondemand.api.SecurityAuditEndpoints
import ai.starlake.quack.ondemand.module.ModuleSurfaces
import ai.starlake.quack.spi.*
import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ModuleSurfacesSpec extends AnyFlatSpec with Matchers:

  private def mod(
      n: String,
      perms: List[PlatformPermission] = Nil,
      prefixes: Set[String] = Set.empty,
      pages: List[ConsolePage] = Nil,
      mounts: List[StaticMount] = List(StaticMount("/ops", "/ops"))
  ): ManagerModule = new ManagerModule:
    def name                         = n
    def changelogPath                = None
    def start(ctx: ManagerContext)   = IO.unit
    def endpoints                    = Nil
    def publicPathPrefixes           = Set.empty
    override def staticMounts        = mounts
    override def permissions         = perms
    override def staffPathPrefixes   = prefixes
    override def consolePages        = pages
    def onEvent(event: ManagerEvent) = IO.unit
    def stop                         = IO.unit

  private val p = PlatformPermission("hosted.quota.extra", "extra minutes")

  /** What Main passes: every core endpoint's first segment after `api`, plus the reserved ones. */
  private val coreSegments =
    ModuleSurfaces.apiSegments(DocEndpoints.all :+ SecurityAuditEndpoints.audit) ++
      ModuleSurfaces.ReservedApiSegments

  private def validate(m: ManagerModule*) = ModuleSurfaces.validate(m.toList, coreSegments)

  "ModuleSurfaces.validate" should "accept a well-formed module" in {
    val s = validate(
      mod(
        "hosted",
        List(p),
        Set("/api/hosted/ops"),
        List(ConsolePage("quotas", "Quotas", "hosted.quota.extra", "/ops/quotas.js"))
      )
    ).toOption.get
    s.permissions.map(_.name) shouldBe List("hosted.quota.extra")
    s.staffPrefixes shouldBe Set("/api/hosted/ops")
    s.pages.map(_.path) shouldBe List("/ui/x/hosted/quotas")
  }

  it should "accept no modules at all" in {
    ModuleSurfaces.validate(Nil, coreSegments) shouldBe Right(ModuleSurfaces.Empty)
  }

  private def refused(m: ManagerModule*): String = validate(m*).left.toOption.get

  it should "refuse a permission without the module prefix" in {
    refused(mod("hosted", List(PlatformPermission("quota.extra", "")))) should include("hosted.")
  }
  it should "refuse the reserved platform prefix" in {
    refused(mod("platform", List(PlatformPermission("platform.read", "")))) should include(
      "reserved"
    )
  }
  it should "refuse a duplicate permission across modules" in {
    refused(
      mod("a", List(PlatformPermission("a.x", ""))),
      mod("a", List(PlatformPermission("a.x", "")))
    ) should include("twice")
  }
  it should "refuse a staff prefix outside /api/<module>/" in {
    refused(mod("hosted", prefixes = Set("/api/tenant"))) should include("/api/hosted/")
    refused(mod("hosted", prefixes = Set("/api/hosted"))) should include("/api/hosted/")
  }
  it should "refuse a page whose permission the module does not declare" in {
    refused(
      mod("hosted", List(p), pages = List(ConsolePage("q", "Q", "hosted.other", "/ops/q.js")))
    ) should include("hosted.other")
  }
  it should "refuse a bad page id and a duplicate page id" in {
    refused(
      mod("hosted", List(p), pages = List(ConsolePage("Q!", "Q", p.name, "/ops/q.js")))
    ) should include("Q!")
    refused(
      mod(
        "hosted",
        List(p),
        pages = List(
          ConsolePage("q", "Q", p.name, "/ops/q.js"),
          ConsolePage("q", "R", p.name, "/ops/r.js")
        )
      )
    ) should include("twice")
  }
  it should "refuse a bundle that is external, escapes, or lies outside the module's mounts" in {
    for b <- List(
        "https://cdn.example/x.js",
        "//cdn.example/x.js",
        "/ops/../secret.js",
        "/opsx/q.js",
        "ops/q.js",
        "/ops/q.js?v=1",
        "/elsewhere/q.js"
      )
    do
      withClue(b) {
        refused(mod("hosted", List(p), pages = List(ConsolePage("q", "Q", p.name, b)))) should
          include("bundle")
      }
  }

  // A staff prefix lies under /api/<module name>/ and the guard skips its URL tenant check, so a
  // module named after a core API namespace would open core routes to staff.
  it should "refuse a module named after a core API segment that declares a platform surface" in {
    val catalogPerm = PlatformPermission("catalog.read", "")
    for (label, m) <- List(
        "a permission"   -> mod("catalog", perms = List(catalogPerm)),
        "a staff prefix" -> mod("catalog", prefixes = Set("/api/catalog/x")),
        "a console page" -> mod(
          "catalog",
          pages = List(ConsolePage("q", "Q", catalogPerm.name, "/ops/q.js"))
        )
      )
    do
      withClue(label) {
        val msg = refused(m)
        msg should include("module catalog")
        msg should include("core API segment 'catalog'")
      }
    for seg <- List("branch", "scim", "pool", "tenant", "auth", "profile", "user", "security") do
      withClue(seg) {
        refused(mod(seg, prefixes = Set(s"/api/$seg/x"))) should include(
          s"core API segment '$seg'"
        )
      }
  }

  it should "refuse the reserved platform and console names as module names with a surface" in {
    refused(mod("console", prefixes = Set("/api/console/x"))) should include(
      "core API segment 'console'"
    )
    refused(mod("platform", prefixes = Set("/api/platform/x"))) should include(
      "core API segment 'platform'"
    )
  }

  it should "allow a module named after a core API segment that declares no platform surface" in {
    validate(mod("catalog")) shouldBe Right(ModuleSurfaces.Empty)
  }

  "ModuleSurfaces.apiSegments" should "list the first segment after api of every core endpoint" in {
    coreSegments should contain allOf (
      "catalog",
      "branch",
      "scim",
      "pool",
      "tenant",
      "auth",
      "profile",
      "user",
      "security",
      "platform",
      "console"
    )
    // /data/v1 is not under /api, and module names are not core.
    coreSegments should not contain "data"
    coreSegments should not contain "v1"
    coreSegments should not contain "hosted"
    coreSegments should not contain "test-module"
  }
