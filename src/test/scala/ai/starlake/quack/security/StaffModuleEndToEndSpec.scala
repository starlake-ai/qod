package ai.starlake.quack.security

import ai.starlake.quack.module.TestModule
import ai.starlake.quack.ondemand.api.SessionTokenStore
import ai.starlake.quack.ondemand.module.ModuleSurfaces
import ai.starlake.quack.ondemand.telemetry.{AuditEvent, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.http.HttpResponse

/** A module's platform surface end to end (issue #274), through the real manager wiring: the
  * fixture [[TestModule]] declares the permission `test-module.ping`, the staff prefix
  * `/api/test-module/staff`, a console page and the static mount serving its bundle. The surfaces
  * are validated as Main validates them (`ModuleSurfaces.validate` over the core API segments), and
  * the module reads its callers through the server's own `platformCallerOf` and audits into the
  * server's recorder, as `start(ctx)` would wire them.
  */
class StaffModuleEndToEndSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val PingPath = "/api/test-module/staff/ping"

  private def describe(r: HttpResponse[String]): String = s"${r.statusCode()} ${r.body()}"

  private final case class Env(
      h: ManagerServerHarness.Harness,
      module: TestModule,
      telemetry: RecordingTelemetryStore
  ):
    def ping(credential: String, query: String = ""): HttpResponse[String] =
      get(h.httpClient, s"${h.baseUrl}$PingPath$query", apiKey = Some(credential))

    def pingRows: List[AuditEvent] =
      telemetry.events.toList.filter(_.action == "test-module.ping")

  private def withModule[A](f: Env => A): A =
    val module   = new TestModule
    val surfaces = ModuleSurfaces.validate(List(module), ModuleSurfaces.coreApiSegments)
    withClue(s"ModuleSurfaces.validate: $surfaces") {
      surfaces.isRight shouldBe true
    }
    val s         = surfaces.toOption.get
    val telemetry = new RecordingTelemetryStore
    val sessions  = new SessionTokenStore
    val audit     = new AuditRecorder(telemetry, t => sessions.get(t))
    val fix       = SecurityFixtures.freshStore()
    val h         = ManagerServerHarness.boot(
      fix.store,
      audit = audit,
      sessions = sessions,
      moduleEndpoints = module.endpoints,
      modulePublicPrefixes = module.publicPathPrefixes,
      moduleStaticMounts = module.staticMounts,
      moduleStaffPrefixes = s.staffPrefixes,
      consolePages = s.pages
    )
    try
      h.supervisor.setDeclaredPermissions(s.permissions)
      module.platformCallerOf = h.platformCallerOf
      module.audit = audit
      f(Env(h, module, telemetry))
    finally h.shutdown()

  "the test module's surfaces" should "pass the boot validation Main runs" in {
    val module = new TestModule
    val s      = ModuleSurfaces
      .validate(List(module), ModuleSurfaces.coreApiSegments)
      .fold(msg => fail(s"refused: $msg"), identity)
    s.permissions.map(_.name) shouldBe List(TestModule.Ping)
    s.staffPrefixes shouldBe Set(PingPath.stripSuffix("/ping"))
    s.pages.map(p => (p.module, p.id, p.path, p.bundle)) shouldBe List(
      ("test-module", "ping", "/ui/x/test-module/ping", "/test-module-ui/ping.js")
    )
  }

  "a staff session holding the permission" should
    "reach the module route, audited with the permission it used" in withModule { env =>
      val staff = env.h.staffSession("sam", Set(TestModule.Ping))
      val r     = env.ping(staff)
      withClue(s"staff GET $PingPath: ${describe(r)}") {
        r.statusCode() shouldBe 200
        io.circe.parser.parse(r.body()) shouldBe io.circe.parser.parse("""{"ok":true}""")
      }
      val rows = env.pingRows
      withClue(s"audit rows: $rows") {
        rows should have size 1
        rows.head.actor shouldBe "sam"
        rows.head.outcome shouldBe "ok"
        rows.head.detail.get("permission") shouldBe Some(TestModule.Ping)
        rows.head.detail.get("actorKind") shouldBe Some("staff")
      }
    }

  it should "reach it whatever tenant its query names, as the module decides" in withModule { env =>
    val staff = env.h.staffSession("sam", Set(TestModule.Ping))
    val r     = env.ping(staff, s"?tenant=${SecurityFixtures.GlobexTenantId}")
    withClue(s"staff GET $PingPath?tenant=${SecurityFixtures.GlobexTenantId}: ${describe(r)}") {
      r.statusCode() shouldBe 200
    }
  }

  "a staff session without the permission" should "get 403 permission_required" in withModule {
    env =>
      val staff = env.h.staffSession("pat", Set("test-module.other"))
      val r     = env.ping(staff)
      withClue(s"staff without the permission GET $PingPath: ${describe(r)}") {
        r.statusCode() shouldBe 403
        errorCode(r.body()) shouldBe Some("permission_required")
        r.body() should include(TestModule.Ping)
      }
      env.pingRows shouldBe empty
  }

  "a tenant admin" should "not reach the module route" in withModule { env =>
    val alice = env.h.mintToken(
      SecurityFixtures.AliceUsername,
      SecurityFixtures.AlicePassword,
      tenant = Some(SecurityFixtures.TenantId)
    )
    val r = env.ping(alice)
    // The guard admits an admin session to every module route under /api (it is no staff path
    // rule), so the module's own check refuses it: a tenant admin is no platform caller.
    withClue(s"tenant admin GET $PingPath: ${describe(r)}") {
      r.statusCode() shouldBe 403
      errorCode(r.body()) shouldBe Some("permission_required")
    }
    env.pingRows shouldBe empty
  }

  "a tenant user" should "be refused by the guard with 403 admin_required" in withModule { env =>
    val bob = env.h.mintToken(
      SecurityFixtures.BobUsername,
      SecurityFixtures.BobPassword,
      tenant = Some(SecurityFixtures.TenantId)
    )
    val r = env.ping(bob)
    withClue(s"tenant user GET $PingPath: ${describe(r)}") {
      r.statusCode() shouldBe 403
      errorCode(r.body()) shouldBe Some("admin_required")
    }
    env.pingRows shouldBe empty
  }

  "a superuser" should "reach the module route, audited as a superuser" in withModule { env =>
    val r = env.ping(env.h.superuserSession())
    withClue(s"superuser GET $PingPath: ${describe(r)}") {
      r.statusCode() shouldBe 200
    }
    val rows = env.pingRows
    withClue(s"audit rows: $rows") {
      rows should have size 1
      rows.head.actor shouldBe SecurityFixtures.RootUsername
      rows.head.detail.get("permission") shouldBe Some(TestModule.Ping)
      rows.head.detail.get("actorKind") shouldBe Some("superuser")
    }
  }

  "GET /api/console/pages" should "list the page to the staff holding its permission only" in
    withModule { env =>
      def pages(credential: String): List[(String, String, String, String, String)] =
        val r = get(
          env.h.httpClient,
          s"${env.h.baseUrl}/api/console/pages",
          apiKey = Some(credential)
        )
        withClue(s"GET /api/console/pages: ${describe(r)}")(r.statusCode() shouldBe 200)
        io.circe.parser
          .parse(r.body())
          .fold(e => fail(s"not JSON ($e): ${r.body()}"), identity)
          .hcursor
          .downField("pages")
          .values
          .getOrElse(fail(s"no pages array: ${r.body()}"))
          .toList
          .map { p =>
            val c            = p.hcursor
            def f(k: String) = c.get[String](k).fold(e => fail(s"$k: $e"), identity)
            (f("module"), f("id"), f("label"), f("path"), f("bundle"))
          }
      val holder = env.h.staffSession("sam", Set(TestModule.Ping))
      val other  = env.h.staffSession("pat", Set("test-module.other"))
      pages(holder) shouldBe List(
        ("test-module", "ping", "Ping", "/ui/x/test-module/ping", "/test-module-ui/ping.js")
      )
      pages(other) shouldBe empty
    }

  "the page bundle" should "be served by the module's static mount" in withModule { env =>
    val r = get(env.h.httpClient, s"${env.h.baseUrl}/test-module-ui/ping.js")
    withClue(s"GET /test-module-ui/ping.js: ${describe(r)}") {
      r.statusCode() shouldBe 200
      r.body() should include("export const qodConsoleContract = 1;")
      r.body() should include("export function mount(el, ctx)")
    }
  }
