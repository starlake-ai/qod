package ai.starlake.quack.security

import ai.starlake.quack.{McpConfig, RestLimitsConfig}
import ai.starlake.quack.mcp.McpRoutes
import ai.starlake.quack.module.TestModule
import ai.starlake.quack.ondemand.ManagerServer
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.state.{RbacUser, UserKind}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.{Method, Request, Status, Uri}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import java.net.http.HttpResponse

/** The REST guard's staff allowlist (issue #274). A staff session reaches its own profile paths
  * (`ManagerServer.StaffProfilePaths`), a read of the console page list and the module paths
  * declared for staff (where the URL tenant check is left to the module), and gets
  * `403 admin_required` everywhere else. A staff PAT never reaches `/mcp`.
  *
  * The walk over every core endpoint reuses `SuperuserPatCompletenessSpec`'s listing and request
  * builder, so a new core endpoint is walked here without touching this file. No Postgres: the
  * staff row lives in the harness's in-memory store and the session is a JWT.
  */
class StaffGuardSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  import SuperuserPatCompletenessSpec.{allEndpoints, concretePath, guardRefused, methodOf}

  private val StaffName  = "sam"
  private val StaffPerms = Set("hosted.tenant.read")

  private def call(h: ManagerServerHarness.Harness, path: String, credential: Option[String]) =
    val ep = allEndpoints
      .collectFirst { case (_, e) if concretePath(e) == path => e }
      .getOrElse(fail(s"no core endpoint at $path"))
    SuperuserPatCompletenessSpec.call(h, ep, credential, RequestTimeout)

  private def describe(r: HttpResponse[String]): String = s"${r.statusCode()} ${r.body()}"

  private def adminRequired(r: HttpResponse[String]): Boolean =
    r.statusCode() == 403 && errorCode(r.body()).contains("admin_required")

  private def withHarness[A](
      moduleEndpoints: List[ServerEndpoint[Any, IO]] = Nil,
      moduleStaffPrefixes: Set[String] = Set.empty,
      slIntegration: Boolean = false
  )(f: ManagerServerHarness.Harness => A): A =
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(
      fix.store,
      moduleEndpoints = moduleEndpoints,
      moduleStaffPrefixes = moduleStaffPrefixes,
      slIntegration = slIntegration
    )
    try f(h)
    finally h.shutdown()

  "a staff session" should "get 403 admin_required on every core endpoint off its allowlist" in
    withHarness() { h =>
      val staff     = h.staffSession(StaffName, StaffPerms)
      val allowlist = ManagerServer.StaffProfilePaths + ManagerServer.ConsolePagesPath
      val guarded   = allEndpoints.filter((_, ep) =>
        guardRefused(SuperuserPatCompletenessSpec.call(h, ep, None, RequestTimeout))
      )
      // Without these, a reflection or a probe that found nothing would pass vacuously.
      guarded.size should be > 100
      val walked = guarded.filterNot((_, ep) => allowlist.contains(concretePath(ep)))
      walked.map((_, ep) => concretePath(ep)) should contain allOf (
        "/api/pool/list",
        "/api/tenant/list",
        "/api/profile/s3/list",
        "/api/profile/pools",
        "/api/profile/usage",
        "/api/profile/statements",
        "/api/profile/recoverable",
        "/api/profile/undrop"
      )
      val reached = walked.flatMap { (name, ep) =>
        val r = SuperuserPatCompletenessSpec.call(h, ep, Some(staff), RequestTimeout)
        if adminRequired(r) then None
        else Some(s"$name [${methodOf(ep)} ${concretePath(ep)}] -> ${describe(r)}")
      }
      withClue(s"endpoints a staff session was not refused admin_required on:\n${reached
          .mkString("\n")}\n") {
        reached shouldBe empty
      }
    }

  it should "reach whoami, its own PATs and /api/console/pages, then logout" in withHarness() { h =>
    val staff  = h.staffSession(StaffName, StaffPerms)
    val whoami = get(h.httpClient, s"${h.baseUrl}/api/auth/whoami", apiKey = Some(staff))
    withClue(s"GET /api/auth/whoami: ${describe(whoami)}") {
      whoami.statusCode() shouldBe 200
      val body = io.circe.parser.parse(whoami.body()).toOption.get.hcursor
      body.get[String]("username") shouldBe Right(StaffName)
      body.get[Boolean]("superuser") shouldBe Right(false)
    }
    // The PAT routes are unmounted in a harness without a PAT store: the guard admitting them
    // reads as the router's 404, never as admin_required.
    for path <- List(
        "/api/auth/pat/list",
        "/api/auth/pat/create",
        "/api/auth/pat/revoke",
        "/api/auth/pat/delete",
        "/api/auth/pat/tools"
      )
    do
      val r = call(h, path, Some(staff))
      withClue(s"$path: ${describe(r)}") {
        adminRequired(r) shouldBe false
        r.statusCode() should not be 401
      }
    val pages =
      get(h.httpClient, s"${h.baseUrl}${ManagerServer.ConsolePagesPath}", apiKey = Some(staff))
    withClue(s"GET ${ManagerServer.ConsolePagesPath}: ${describe(pages)}") {
      adminRequired(pages) shouldBe false
      pages.statusCode() should not be 401
    }
    // The page list is a read: any other method is refused.
    val pagesPost =
      post(h.httpClient, s"${h.baseUrl}${ManagerServer.ConsolePagesPath}", "{}", Some(staff))
    withClue(s"POST ${ManagerServer.ConsolePagesPath}: ${describe(pagesPost)}") {
      adminRequired(pagesPost) shouldBe true
    }
    val logout = post(h.httpClient, s"${h.baseUrl}/api/auth/logout", "", apiKey = Some(staff))
    withClue(s"POST /api/auth/logout: ${describe(logout)}") {
      logout.statusCode() shouldBe 200
    }
  }

  it should "match its allowlist on whole segments, never across an encoded slash" in
    withHarness(moduleEndpoints = ManagerRestSecuritySpec.captureRoutes) { h =>
      // `auth%2Fwhoami` reads as `/api/auth/whoami` in the joined path, while the router sees one
      // segment `auth/whoami`, which the module route `/api/{x}` serves.
      val staff = h.staffSession(StaffName, StaffPerms)
      List(
        "/api/auth%2Fwhoami",
        "/api/auth%2Fpat%2Flist",
        "/api/console%2Fpages"
      ).foreach { path =>
        val r = get(h.httpClient, s"${h.baseUrl}$path", apiKey = Some(staff))
        withClue(s"GET $path (staff): ${describe(r)}")(adminRequired(r) shouldBe true)
      }
      val logout = post(h.httpClient, s"${h.baseUrl}/api/auth%2Flogout", "{}", Some(staff))
      withClue(s"POST /api/auth%2Flogout (staff): ${describe(logout)}") {
        adminRequired(logout) shouldBe true
      }
      // The route answers a superuser, so the 403s above are the guard's.
      val admitted =
        get(h.httpClient, s"${h.baseUrl}/api/auth%2Fwhoami", apiKey = Some(h.superuserSession()))
      withClue(s"GET /api/auth%2Fwhoami (superuser): ${describe(admitted)}") {
        admitted.statusCode() shouldBe 200
      }
      admitted.body() shouldBe "module:auth/whoami"
      // Controls: the plain paths still reach their handlers.
      val whoami = get(h.httpClient, s"${h.baseUrl}/api/auth/whoami", apiKey = Some(staff))
      withClue(s"GET /api/auth/whoami (staff): ${describe(whoami)}") {
        whoami.statusCode() shouldBe 200
      }
      val pages =
        get(h.httpClient, s"${h.baseUrl}${ManagerServer.ConsolePagesPath}", apiKey = Some(staff))
      withClue(s"GET ${ManagerServer.ConsolePagesPath} (staff): ${describe(pages)}") {
        adminRequired(pages) shouldBe false
        pages.statusCode() should not be 401
      }
    }

  it should "be refused its S3 credentials, profile pools, usage, statements and undrop" in
    withHarness() { h =>
      val staff   = h.staffSession(StaffName, StaffPerms)
      val refused = ManagerServer.ProfileApiPaths -- ManagerServer.StaffProfilePaths
      refused should contain allOf (
        "/api/profile/s3/list",
        "/api/profile/s3/add",
        "/api/profile/s3/remove",
        "/api/profile/pools",
        "/api/profile/usage",
        "/api/profile/statements",
        "/api/profile/recoverable",
        "/api/profile/undrop"
      )
      for path <- refused do
        val r = call(h, path, Some(staff))
        withClue(s"$path: ${describe(r)}")(adminRequired(r) shouldBe true)
    }

  "ManagerServer.isStaffSession" should "need a tenant-less session, whatever the role's case" in {
    def session(role: String, tenant: Option[String]) =
      ai.starlake.quack.ondemand.api.SessionTokenStore.Session(
        profile = ai.starlake.quack.edge.auth.AuthenticatedProfile(
          StaffName,
          role,
          Set.empty,
          Map.empty,
          "pat",
          tenant
        ),
        scope = SessionScope.NoAccess,
        createdAt = java.time.Instant.now(),
        readOnly = false
      )
    ManagerServer.isStaffSession(session("staff", None)) shouldBe true
    ManagerServer.isStaffSession(session("Staff", None)) shouldBe true
    ManagerServer.isStaffSession(session("Staff", Some("acme"))) shouldBe false
    ManagerServer.isStaffSession(session("staff", Some("acme"))) shouldBe false
    ManagerServer.isStaffSession(session("user", None)) shouldBe false
  }

  "ManagerServer.StaffProfilePaths" should
    "be exactly whoami, logout and the caller's own PATs" in {
      ManagerServer.StaffProfilePaths shouldBe Set(
        "/api/auth/whoami",
        "/api/auth/logout",
        "/api/auth/pat/create",
        "/api/auth/pat/list",
        "/api/auth/pat/revoke",
        "/api/auth/pat/delete",
        "/api/auth/pat/tools"
      )
      ManagerServer.StaffProfilePaths.subsetOf(ManagerServer.ProfileApiPaths) shouldBe true
    }

  "a staff session" should
    "be refused the Starlake SSO ticket, which a tenant user still reaches" in
    withHarness(slIntegration = true) { h =>
      val url   = s"${h.baseUrl}/api/auth/sso/ticket"
      val staff = h.staffSession(StaffName, StaffPerms)
      val r     = post(h.httpClient, url, "{}", apiKey = Some(staff))
      withClue(s"staff POST /api/auth/sso/ticket: ${describe(r)}")(adminRequired(r) shouldBe true)
      // Positive control: the route is mounted and on a tenant user's allowlist.
      val bob = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val user = post(h.httpClient, url, "{}", apiKey = Some(bob))
      withClue(s"tenant user POST /api/auth/sso/ticket: ${describe(user)}") {
        adminRequired(user) shouldBe false
        user.statusCode() should not be 401
        user.statusCode() should not be 404
      }
    }

  it should "leave the branch routes closed" in withHarness() { h =>
    val staff = h.staffSession(StaffName, StaffPerms)
    val r     = post(h.httpClient, s"${h.baseUrl}/api/branch/create", "{}", apiKey = Some(staff))
    withClue(s"POST /api/branch/create: ${describe(r)}")(adminRequired(r) shouldBe true)
  }

  private val staffModuleEndpoints: List[ServerEndpoint[Any, IO]] = List(
    endpoint.get
      .in("api" / "test-module" / "staff" / "ping")
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("staff-pong")),
    endpoint.get
      .in("api" / "test-module" / "staffx")
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("not-for-staff"))
  )

  it should "reach a module path under a declared staff prefix, and only that" in withHarness(
    moduleEndpoints = (new TestModule).hostedTestEndpoints ++ staffModuleEndpoints,
    moduleStaffPrefixes = Set("/api/test-module/staff")
  ) { h =>
    val staff = h.staffSession(StaffName, StaffPerms)
    val ping  =
      get(h.httpClient, s"${h.baseUrl}/api/test-module/staff/ping", apiKey = Some(staff))
    withClue(s"GET /api/test-module/staff/ping: ${describe(ping)}") {
      ping.statusCode() shouldBe 200
      ping.body() shouldBe "staff-pong"
    }
    val secret = get(h.httpClient, s"${h.baseUrl}/api/hosted-test/secret", apiKey = Some(staff))
    withClue(s"GET /api/hosted-test/secret: ${describe(secret)}") {
      adminRequired(secret) shouldBe true
    }
    // Whole segments: a sibling sharing the prefix's spelling is not under it.
    val sibling = get(h.httpClient, s"${h.baseUrl}/api/test-module/staffx", apiKey = Some(staff))
    withClue(s"GET /api/test-module/staffx: ${describe(sibling)}") {
      adminRequired(sibling) shouldBe true
    }
    // A tenant user does not reach the staff prefix.
    val bob = h.mintToken(
      SecurityFixtures.BobUsername,
      SecurityFixtures.BobPassword,
      tenant = Some(SecurityFixtures.TenantId)
    )
    val bobPing = get(h.httpClient, s"${h.baseUrl}/api/test-module/staff/ping", apiKey = Some(bob))
    withClue(s"tenant user GET /api/test-module/staff/ping: ${describe(bobPing)}") {
      adminRequired(bobPing) shouldBe true
    }
  }

  it should "reach a declared staff prefix whatever tenant its query names" in withHarness(
    moduleEndpoints = staffModuleEndpoints,
    moduleStaffPrefixes = Set("/api/test-module/staff")
  ) { h =>
    // Staff permissions cross tenants by design: the module's own permission check decides, so
    // the guard's URL tenant check does not apply to a staff caller on a staff prefix.
    val staff = h.staffSession(StaffName, StaffPerms)
    for t <- List(SecurityFixtures.TenantId, SecurityFixtures.GlobexTenantId) do
      val r = get(
        h.httpClient,
        s"${h.baseUrl}/api/test-module/staff/ping?tenant=$t",
        apiKey = Some(staff)
      )
      withClue(s"staff GET /api/test-module/staff/ping?tenant=$t: ${describe(r)}") {
        r.statusCode() shouldBe 200
        r.body() shouldBe "staff-pong"
      }
    // Controls: every other caller keeps the check. A tenant admin on the staff prefix naming
    // another tenant, and a tenant user on a profile path naming another tenant.
    val alice = h.mintToken(
      SecurityFixtures.AliceUsername,
      SecurityFixtures.AlicePassword,
      tenant = Some(SecurityFixtures.TenantId)
    )
    val adminOther = get(
      h.httpClient,
      s"${h.baseUrl}/api/test-module/staff/ping?tenant=${SecurityFixtures.GlobexTenantId}",
      apiKey = Some(alice)
    )
    withClue(s"tenant admin on the staff prefix, other tenant: ${describe(adminOther)}") {
      adminOther.statusCode() shouldBe 403
      errorCode(adminOther.body()) should contain("tenant_forbidden")
    }
    val bob = h.mintToken(
      SecurityFixtures.BobUsername,
      SecurityFixtures.BobPassword,
      tenant = Some(SecurityFixtures.TenantId)
    )
    val userOther = get(
      h.httpClient,
      s"${h.baseUrl}/api/profile/pools?tenant=${SecurityFixtures.GlobexTenantId}",
      apiKey = Some(bob)
    )
    withClue(s"tenant user on a profile path, other tenant: ${describe(userOther)}") {
      userOther.statusCode() shouldBe 403
      errorCode(userOther.body()) should contain("tenant_forbidden")
    }
  }

  it should "keep the URL tenant check outside its staff prefixes" in withHarness(
    moduleEndpoints = staffModuleEndpoints,
    moduleStaffPrefixes = Set("/api/test-module/staff")
  ) { h =>
    // The skip holds on a declared staff prefix only: on its own allowlisted paths a staff
    // session naming a tenant meets the check like any caller, and manages no tenant.
    val staff = h.staffSession(StaffName, StaffPerms)
    for t <- List(SecurityFixtures.TenantId, SecurityFixtures.GlobexTenantId) do
      val r = get(h.httpClient, s"${h.baseUrl}/api/auth/whoami?tenant=$t", apiKey = Some(staff))
      withClue(s"staff GET /api/auth/whoami?tenant=$t: ${describe(r)}") {
        r.statusCode() shouldBe 403
        errorCode(r.body()) should contain("tenant_forbidden")
      }
  }

  private val segmentModuleEndpoints: List[ServerEndpoint[Any, IO]] = List(
    endpoint.get
      .in("api" / "m" / "staff" / "ping")
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("m-pong")),
    endpoint.get
      .in("api" / "m" / path[String]("segment") / "secret")
      .out(stringBody)
      .serverLogicSuccess[IO](seg => IO.pure("crossed:" + seg)),
    endpoint.get
      .in("api" / "m" / "staffx")
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("sibling"))
  )

  it should "match a staff prefix on the segments the router reads" in withHarness(
    moduleEndpoints = segmentModuleEndpoints,
    moduleStaffPrefixes = Set("/api/m/staff")
  ) { h =>
    val staff = h.staffSession(StaffName, StaffPerms)
    val ping  = get(h.httpClient, s"${h.baseUrl}/api/m/staff/ping", apiKey = Some(staff))
    withClue(s"GET /api/m/staff/ping: ${describe(ping)}") {
      ping.statusCode() shouldBe 200
      ping.body() shouldBe "m-pong"
    }
    // An encoded slash keeps "staff/x" one segment: the router reads it under /api/m, not under
    // the prefix, so the guard must not read it as /api/m/staff/x.
    for target <- List("/api/m/staff%2Fx/secret", "/api/m/staff%2fx/secret", "/api/m/staffx") do
      val r = get(h.httpClient, s"${h.baseUrl}$target", apiKey = Some(staff))
      withClue(s"GET $target: ${describe(r)}")(adminRequired(r) shouldBe true)
  }

  it should "be refused entirely once its row is disabled, with 401 at the guard" in withHarness(
    moduleEndpoints = staffModuleEndpoints,
    moduleStaffPrefixes = Set("/api/test-module/staff")
  ) { h =>
    val staff  = h.staffSession(StaffName, StaffPerms)
    val before = get(h.httpClient, s"${h.baseUrl}/api/auth/whoami", apiKey = Some(staff))
    withClue(s"whoami before: ${describe(before)}")(before.statusCode() shouldBe 200)
    h.store.upsertUserWithHash(
      None,
      StaffName,
      "not-a-password-hash",
      UserKind.Staff,
      enabled = false
    )
    h.supervisor.restore()
    for path <- List("/api/auth/whoami", "/api/test-module/staff/ping", "/api/pool/list") do
      val r = get(h.httpClient, s"${h.baseUrl}$path", apiKey = Some(staff))
      withClue(s"GET $path after disable: ${describe(r)}") {
        // The guard's own refusal: 401 with no body.
        r.statusCode() shouldBe 401
        r.body() shouldBe empty
      }
  }

  "a tenant user" should "read /api/console/pages" in withHarness() { h =>
    val bob = h.mintToken(
      SecurityFixtures.BobUsername,
      SecurityFixtures.BobPassword,
      tenant = Some(SecurityFixtures.TenantId)
    )
    val r = get(h.httpClient, s"${h.baseUrl}${ManagerServer.ConsolePagesPath}", apiKey = Some(bob))
    withClue(s"GET ${ManagerServer.ConsolePagesPath}: ${describe(r)}") {
      adminRequired(r) shouldBe false
      r.statusCode() should not be 401
    }
    val p = post(h.httpClient, s"${h.baseUrl}${ManagerServer.ConsolePagesPath}", "{}", Some(bob))
    withClue(s"POST ${ManagerServer.ConsolePagesPath}: ${describe(p)}") {
      adminRequired(p) shouldBe true
    }
  }

  // ------------------------------------------------------------------
  // MCP: a tenant-less PAT reaches it only when its owner is a superuser
  // ------------------------------------------------------------------

  private def patOf(user: RbacUser, scope: SessionScope, isAdmin: Boolean): PatPrincipal =
    PatPrincipal(user, "pat-" + user.id, scope, isAdmin, TokenRestriction.Unrestricted)

  private val StaffPat = patOf(
    RbacUser(id = "u-staff", tenant = None, username = StaffName, kind = UserKind.Staff),
    SessionScope(
      superuser = false,
      manageableTenants = Set.empty,
      platformPermissions = StaffPerms
    ),
    isAdmin = false
  )
  private val TenantlessUserPat = patOf(
    RbacUser(id = "u-loose", tenant = None, username = "loose", kind = UserKind.User),
    SessionScope.NoAccess,
    isAdmin = false
  )
  private val TenantUserPat = patOf(
    RbacUser(id = "u-bob", tenant = Some("acme"), username = "bob", kind = UserKind.User),
    SessionScope.NoAccess,
    isAdmin = false
  )
  private val SuperuserPat = patOf(
    RbacUser(id = "u-root", tenant = None, username = "root", kind = UserKind.Admin),
    SessionScope(superuser = true, manageableTenants = Set.empty),
    isAdmin = true
  )

  private val mcp = new McpRoutes(
    cfg = McpConfig(),
    resolvePat = {
      case "qod_pat_staff" => Some(StaffPat)
      case "qod_pat_loose" => Some(TenantlessUserPat)
      case "qod_pat_bob"   => Some(TenantUserPat)
      case "qod_pat_root"  => Some(SuperuserPat)
      case _               => None
    },
    tools = Nil,
    serverVersion = "test",
    limits = RestLimitsConfig(),
    textForTenant = identity
  ).routes.orNotFound

  private def mcpPing(token: String): Status =
    val req = Request[IO](Method.POST, Uri.unsafeFromString("/mcp"))
      .withEntity("""{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}""")
      .putHeaders(
        org.http4s.Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $token")
      )
    mcp.run(req).unsafeRunSync().status

  "a staff PAT" should "get 401 on /mcp" in {
    mcpPing("qod_pat_staff") shouldBe Status.Unauthorized
    // So does any other tenant-less PAT whose owner is no superuser.
    mcpPing("qod_pat_loose") shouldBe Status.Unauthorized
    // Positive controls: a tenant user's and a superuser's PAT get in.
    mcpPing("qod_pat_bob") shouldBe Status.Ok
    mcpPing("qod_pat_root") shouldBe Status.Ok
  }

  "McpRoutes.admitsPat" should
    "admit a tenant's PAT and a superuser's, and no other tenant-less one" in {
      McpRoutes.admitsPat(StaffPat) shouldBe false
      McpRoutes.admitsPat(TenantlessUserPat) shouldBe false
      McpRoutes.admitsPat(TenantUserPat) shouldBe true
      McpRoutes.admitsPat(SuperuserPat) shouldBe true
    }
