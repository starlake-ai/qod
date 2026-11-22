package ai.starlake.quack.module

import ai.starlake.quack.ondemand.api.{Endpoints, ErrorResponse}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.spi.*
import cats.effect.IO
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint

/** Fixture module. No-arg constructible for the ServiceLoader spec; other specs instantiate it
  * directly to reach `received` / `started` / `stoppedFlag`.
  *
  * Its platform surface (issue #274): the permission `test-module.ping`, the staff prefix
  * `/api/test-module/staff`, a console page whose bundle `/test-module-ui/ping.js` its static mount
  * serves, and `GET /api/test-module/staff/ping`, which answers `{"ok":true}` only to a caller
  * holding the permission and audits the call with the permission it used. `platformCallerOf` and
  * `audit` come from `start(ctx)`; a spec that does not start the module sets them.
  */
class TestModule extends ManagerModule:
  val received = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
  @volatile var started: Option[ManagerContext] = None
  @volatile var stoppedFlag: Boolean            = false

  @volatile var platformCallerOf: Option[String] => Option[PlatformCaller] = _ => None
  @volatile var audit: AuditRecorder                                       = AuditRecorder.noop

  def name: String                  = "test-module"
  def changelogPath: Option[String] = None

  def start(ctx: ManagerContext): IO[Unit] = IO {
    started = Some(ctx)
    platformCallerOf = ctx.platformCallerOf
    audit = ctx.audit
  }

  /** The routes that predate the platform surface, for specs that serve a staff route of their own
    * at the same path.
    */
  def hostedTestEndpoints: List[ServerEndpoint[Any, IO]] = List(
    endpoint.get
      .in("api" / "hosted-test" / "ping")
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("pong")),
    endpoint.get
      .in("api" / "hosted-test" / "secret")
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("classified"))
  )

  val staffPing: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("api" / "test-module" / "staff" / "ping")
      .in(Endpoints.authToken)
      .errorOut(statusCode.and(jsonBody[ErrorResponse]))
      .out(stringJsonBody)
      .serverLogic[IO] { token =>
        IO.blocking {
          val caller = platformCallerOf(token)
          PermissionCheck.require(caller, TestModule.Ping) match
            case Some(refusal) => Left(refusal)
            case None          =>
              caller.foreach { c =>
                audit.rest(
                  token,
                  "control-plane",
                  "test-module.ping",
                  "ok",
                  detail = c.auditDetail(TestModule.Ping)
                )
              }
              Right("""{"ok":true}""")
        }
      }

  def endpoints: List[ServerEndpoint[Any, IO]] = hostedTestEndpoints :+ staffPing

  def publicPathPrefixes: Set[String] = Set("/api/hosted-test/ping")

  override def staticMounts: List[StaticMount] =
    List(StaticMount("/test-module-ui", "/test-module-ui"))

  override def permissions: List[PlatformPermission] =
    List(PlatformPermission(TestModule.Ping, "ping"))

  override def staffPathPrefixes: Set[String] = Set("/api/test-module/staff")

  override def consolePages: List[ConsolePage] =
    List(ConsolePage("ping", "Ping", TestModule.Ping, "/test-module-ui/ping.js"))

  def onEvent(event: ManagerEvent): IO[Unit] = IO { received.add(event); () }

  def stop: IO[Unit] = IO { stoppedFlag = true }

object TestModule:
  val Ping: String = "test-module.ping"
