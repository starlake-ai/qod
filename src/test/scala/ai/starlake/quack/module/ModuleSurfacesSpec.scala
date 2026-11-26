package ai.starlake.quack.module

import ai.starlake.quack.docs.DocEndpoints
import ai.starlake.quack.ondemand.api.SecurityAuditEndpoints
import ai.starlake.quack.model.FederatedSource
import ai.starlake.quack.ondemand.module.{ModuleSurfaces, ResolvedSourceType}
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
      mounts: List[StaticMount] = List(StaticMount("/ops", "/ops")),
      public: Set[String] = Set.empty,
      providers: List[FederatedSourceProvider] = Nil
  ): ManagerModule = new ManagerModule:
    def name                         = n
    def changelogPath                = None
    def start(ctx: ManagerContext)   = IO.unit
    def endpoints                    = Nil
    def publicPathPrefixes           = public
    override def staticMounts        = mounts
    override def permissions         = perms
    override def staffPathPrefixes   = prefixes
    override def consolePages        = pages
    override def federatedSourceProviders = providers
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

  // Fix round 1: every bundle segment is allowlisted, since a browser resolves an escaped dot, a
  // backslash or a dot pair split by a tab or a line break as '..' and leaves the mount.
  it should "refuse a bundle segment a browser would resolve outside the mount" in {
    for b <- List(
        "/ops/%2e%2e/elsewhere/x.js",
        "/ops/.%2e/elsewhere/x.js",
        "/ops/%2e./elsewhere/x.js",
        "/ops/%2e/x.js",
        "/ops/%2E%2E/elsewhere/x.js",
        "/ops/..\\x.js",
        "/ops/.\t./x.js",
        "/ops/.\n./x.js",
        "/ops/a b.js",
        "/ops/./q.js",
        "/ops//q.js",
        "/ops/"
      )
    do
      withClue(b) {
        refused(mod("hosted", List(p), pages = List(ConsolePage("q", "Q", p.name, b)))) should
          include("bundle")
      }
  }

  it should "accept a bundle of plain segments under a mount" in {
    val pg = ConsolePage("q", "Q", p.name, "/ops/v1.2/q-x_Y.js")
    validate(mod("hosted", List(p), pages = List(pg))).map(_.pages.map(_.bundle)) shouldBe
      Right(List("/ops/v1.2/q-x_Y.js"))
  }

  it should "cover no bundle with a root mount" in {
    val m = mod(
      "hosted",
      List(p),
      pages = List(ConsolePage("q", "Q", p.name, "/q.js")),
      mounts = List(StaticMount("/", "/www"))
    )
    val msg = refused(m)
    msg should include("bundle")
    msg should include("a root mount (/) covers no bundle")
  }

  it should "refuse a staff prefix with an empty, dot or unlisted segment" in {
    for pre <- List(
        "/api/hosted//",
        "/api/hosted//x",
        "/api/hosted/ops/",
        "/api/hosted/./x",
        "/api/hosted/../tenant",
        "/api/hosted/..",
        "/api/hosted/%2e%2e",
        "/api/hosted/a b",
        "/api/hosted/a\\b",
        "api/hosted/x",
        "//api/hosted/x",
        "/api/hostedx/y",
        "/apix/hosted/y"
      )
    do
      withClue(pre) {
        refused(mod("hosted", prefixes = Set(pre))) should include("staff path prefix")
      }
  }

  it should "accept a staff prefix of plain segments under /api/<module>/" in {
    validate(mod("hosted", prefixes = Set("/api/hosted/ops", "/api/hosted/a.b/c-d_E")))
      .map(_.staffPrefixes) shouldBe Right(Set("/api/hosted/ops", "/api/hosted/a.b/c-d_E"))
  }

  it should "refuse a bad or repeated module name when the module declares a surface" in {
    for n <- List("Hosted", "", "ho sted", "hosted_x", "hosted.x", "h/x") do
      withClue(s"'$n'") {
        refused(mod(n, prefixes = Set(s"/api/$n/x"))) should include("module name")
      }
    refused(
      mod("hosted", prefixes = Set("/api/hosted/a")),
      mod("hosted", prefixes = Set("/api/hosted/b"))
    ) should include("module name 'hosted' is used twice")
  }

  it should "leave the name of a module with no surface unchecked" in {
    validate(mod("Not A Name"), mod("Not A Name"), mod("hosted", prefixes = Set("/api/hosted/x")))
      .map(_.staffPrefixes) shouldBe Right(Set("/api/hosted/x"))
  }

  it should "read each module's surfaces once and return those values" in {
    var reads = 0
    val m     = new ManagerModule:
      def name                       = "hosted"
      def changelogPath              = None
      def start(ctx: ManagerContext) = IO.unit
      def endpoints                  = Nil
      def publicPathPrefixes         = Set.empty
      override def staticMounts      = List(StaticMount("/ops", "/ops"))
      override def permissions       = { reads += 1; List(p) }
      override def staffPathPrefixes = { reads += 1; Set(s"/api/hosted/r$reads") }
      override def consolePages      =
        reads += 1
        List(ConsolePage("q", "Q", p.name, "/ops/q.js"))
      def onEvent(event: ManagerEvent) = IO.unit
      def stop                         = IO.unit
    val s = validate(m).toOption.get
    reads shouldBe 3
    s.staffPrefixes shouldBe Set("/api/hosted/r2")
  }

  "ModuleSurfaces.validate on public path prefixes" should "refuse a malformed one from any module" in {
    for pre <- List(
        "",
        "/",
        "api/hosted/x",
        "//api/hosted/x",
        "/api//hosted/x",
        "/api/hosted//x",
        "/api/hosted/x/",
        "/api/hosted/./x",
        "/api/hosted/../tenant",
        "/api/hosted/x/..",
        "/api/hosted/%2e%2e",
        "/api/hosted/a b",
        "/ops//x",
        "/ops/../api"
      )
    do
      withClue(s"'$pre'") {
        // With a platform surface and without one: the shape rules bind every module.
        refused(mod("hosted", prefixes = Set("/api/hosted/s"), public = Set(pre))) should
          include("public path prefix")
        refused(mod("plain", public = Set(pre))) should include("public path prefix")
      }
  }

  it should "refuse one over /api itself, a core API segment or another module's name" in {
    for pre <- List(
        "/api",
        "/api/auth",
        "/api/auth/login",
        "/api/tenant/list",
        "/api/platform/x",
        "/api/console/pages",
        "/api/hosted",
        "/api/other/x"
      )
    do
      withClue(s"'$pre'") {
        refused(mod("hosted", public = Set(pre)), mod("other")) should
          include(s"public path prefix '$pre'")
      }
  }

  it should "refuse one outside /api that no static mount of the module covers" in {
    for (pre, mounts) <- List(
        "/portal/x" -> List(StaticMount("/ops", "/ops")),
        "/opsx/y"   -> List(StaticMount("/ops", "/ops")),
        "/x"        -> List(StaticMount("/", "/www")),
        "/ops/x"    -> Nil
      )
    do
      withClue(s"'$pre' with $mounts") {
        refused(mod("hosted", mounts = mounts, public = Set(pre))) should
          include(s"public path prefix '$pre'")
      }
  }

  it should "accept the prefixes qod-hosted declares, under its name and its mounts" in {
    validate(
      mod(
        "hosted",
        prefixes = Set("/api/hosted/ops"),
        mounts = List(StaticMount("/portal", "/portal"), StaticMount("/", "/www")),
        public = Set(
          "/api/hosted/signup",
          "/api/hosted/portal/password/reset-request",
          "/api/hosted/portal/password/reset-complete",
          "/api/hosted/billing/webhook",
          "/api/hosted/billing/plans/public",
          "/portal",
          "/portal/reset"
        )
      )
    ).isRight shouldBe true
  }

  it should "accept a prefix under a first segment other than the module's own name" in {
    // The fixture module is named test-module and has a platform surface, while its public
    // prefix is /api/hosted-test/ping: what binds every module is that a prefix keeps off core
    // and off another loaded module's name, not that it lies under its own.
    validate(new TestModule).isRight shouldBe true
    validate(mod("plain", public = Set("/api/hosted-test/ping"))).isRight shouldBe true
  }

  it should "carry the public prefixes it validated, reading each getter once" in {
    // A getter that answers a checked prefix first and core's tenant routes after: only the value
    // validate read may reach the guard.
    var reads = 0
    val m     = new ManagerModule:
      def name                       = "plain"
      def changelogPath              = None
      def start(ctx: ManagerContext) = IO.unit
      def endpoints                  = Nil
      def publicPathPrefixes         =
        reads += 1
        if reads == 1 then Set("/api/plain/hook") else Set("/api/tenant/list")
      def onEvent(event: ManagerEvent) = IO.unit
      def stop                         = IO.unit
    val s = validate(m, mod("other", public = Set("/api/other/x"))).toOption.get
    reads shouldBe 1
    s.publicPrefixes shouldBe Set("/api/plain/hook", "/api/other/x")
    m.publicPathPrefixes shouldBe Set("/api/tenant/list")
    s.publicPrefixes should not contain "/api/tenant/list"
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

  "PlatformPermissionReport.lines" should "name each stored permission no loaded module declares" in {
    ai.starlake.quack.ondemand.rbac.PlatformPermissionReport.lines(
      List(
        ai.starlake.quack.ondemand.state
          .PlatformRole("1", "support", None, Set("gone.module.x", "a.b"))
      ),
      Set("a.b")
    ) shouldBe List(
      "WARN: platform role 'support' grants 'gone.module.x', which no loaded module declares; " +
        "it has no effect"
    )
  }

  /** A provider of wire `w` whose `editor` getter evaluates `ed` on every read. */
  private def provider(w: String, ed: => Option[SourceEditor]): FederatedSourceProvider =
    new FederatedSourceProvider:
      val wire                                               = w
      val extensionName                                      = "qod_test_connector"
      val reservedSecretPrefixes: List[String]               = Nil
      def errors(s: FederatedSource, r: Set[String])         = Nil
      def normalizedConfig(raw: String)                      = Right(raw)
      def renderTemplate(s: FederatedSource, r: Set[String]) = Right("")
      override def editor                                    = ed

  private val Editor = SourceEditor("Test connector", "/ops/editor.js")

  // `plain` declares no platform surface: an editor is checked on every module.
  "A module source type" should "be listed with its editor when the bundle lies under a mount" in {
    val s = validate(
      mod(
        "plain",
        providers = List(provider("test_connector", Some(Editor)), provider("plain_connector", None))
      )
    ).toOption.get
    s.sourceTypes shouldBe List(
      ResolvedSourceType("plain", "test_connector", "Test connector", Some("/ops/editor.js")),
      ResolvedSourceType("plain", "plain_connector", "plain_connector", None)
    )
  }

  it should "refuse an editor bundle outside every mount, naming the module" in {
    val r = refused(
      mod(
        "plain",
        providers = List(provider("test_connector", Some(SourceEditor("T", "/elsewhere/editor.js"))))
      )
    )
    r should include("module plain: source editor of 'test_connector'")
    r should include("is not under any of the module's static mounts")
  }

  it should "refuse an editor bundle under a root mount" in {
    val r = refused(
      mod(
        "plain",
        mounts = List(StaticMount("/", "/www")),
        providers = List(provider("test_connector", Some(SourceEditor("T", "/editor.js"))))
      )
    )
    r should include("module plain: source editor of 'test_connector'")
    r should include("a root mount (/) covers no bundle")
  }

  it should "refuse an editor bundle holding a bad segment" in {
    for b <- List(
        "/ops/../editor.js",
        "/ops/a b.js",
        "/ops//editor.js",
        "/ops/editor.js?v=1",
        "https://cdn.example/editor.js",
        "ops/editor.js"
      )
    do
      withClue(b) {
        refused(
          mod("plain", providers = List(provider("test_connector", Some(SourceEditor("T", b)))))
        ) should include("must be an origin-relative path of segments over [A-Za-z0-9._-]")
      }
  }

  it should "list the editor it checked, reading each editor once" in {
    var reads = 0
    val p     = provider(
      "test_connector", {
        reads += 1
        if reads == 1 then Some(Editor) else Some(SourceEditor("T", "https://cdn.example/x.js"))
      }
    )
    val s = validate(mod("plain", providers = List(p))).toOption.get
    reads shouldBe 1
    s.sourceTypes.flatMap(_.editorBundle) shouldBe List("/ops/editor.js")
  }
