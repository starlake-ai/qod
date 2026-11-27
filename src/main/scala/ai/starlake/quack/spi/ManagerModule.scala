package ai.starlake.quack.spi

import cats.effect.IO
import sttp.tapir.server.ServerEndpoint

/** A static content mount contributed by a module.
  *
  * `diskDir`: when set AND the directory exists, content is served from the filesystem instead of
  * the classpath (live-updatable without a redeploy, e.g. the marketing site's QOD_WWW_DIR);
  * otherwise the bundled classpath resources under `classpathDir` serve as the fallback.
  *
  * `spaFallback`: true (default) routes any unmatched path to `index.html` with status 200 (SPA
  * client routing, e.g. the portal); false serves the mount's `404.html` with a genuine 404 status
  * (marketing pages must not soft-404).
  */
final case class StaticMount(
    urlPrefix: String,
    classpathDir: String,
    diskDir: Option[String] = None,
    spaFallback: Boolean = true
)

/** The plug-in contract for jars discovered via
  * `META-INF/services/ai.starlake.quack.spi.ManagerModule`.
  *
  * Lifecycle: no-arg construction (ServiceLoader) -> changelogPath applied -> prepare(controlPlane, config) -> pool restore -> start(ctx) ->
  * endpoints/publicPathPrefixes/staticMounts read once -> onEvent for the process lifetime -> stop
  * during graceful shutdown. Boot-time failures (construction, migration, start) abort the manager
  * boot; onEvent failures are contained by the dispatcher.
  */
trait ManagerModule:
  def name: String
  def changelogPath: Option[String]

  /** Runs once at boot, after every module's changelog and BEFORE pool restore, which renders the
    * federated sources of every tenant-db (so before [[start]]): the place for a module whose
    * providers need stored state to render, such as per-tenant settings. A failure stops the boot.
    */
  def prepare(controlPlane: javax.sql.DataSource, config: com.typesafe.config.Config): IO[Unit] =
    IO.unit
  def start(ctx: ManagerContext): IO[Unit]

  /** The module's REST routes. A route that reads a request body lives under `/api`: the REST
    * limits (`quack-on-demand.restLimits`) are applied there.
    */
  def endpoints: List[ServerEndpoint[Any, IO]]
  def publicPathPrefixes: Set[String]
  def staticMounts: List[StaticMount] = Nil

  /** Veto hooks for structure mutations (quota policy). Read by the manager AFTER start() returns,
    * same contract as endpoints: a module may build gates inside start().
    */
  def mutationGates: List[MutationGate] = Nil

  /** Permissions this module checks (issue #274). Read after start(), like endpoints; boot
    * validates them (`ModuleSurfaces.validate`) and refuses to start on a bad one.
    */
  def permissions: List[PlatformPermission] = Nil

  /** REST path prefixes, each under `/api/<name>/`, that staff sessions may reach. The routes still
    * check their permission with `ctx.platformCallerOf`. Read after start().
    */
  def staffPathPrefixes: Set[String] = Set.empty

  /** Pages this module adds to the admin console's Platform section. Read after start(). */
  def consolePages: List[ConsolePage] = Nil

  /** Federated source types this module contributes. Read right after construction, before
    * `start()`: pool restore renders sources before modules start. Read once more after `start()`,
    * with `staticMounts`, to check and list each provider's console `editor`.
    */
  def federatedSourceProviders: List[FederatedSourceProvider] = Nil

  /** Classes for functions of extensions this module's nodes link: (name, type, class) with type in
    * scalar/aggregate/table and class allowed or listing. Read right after construction.
    */
  def functionClasses: List[(String, String, String)] = Nil
  def onEvent(event: ManagerEvent): IO[Unit]
  def stop: IO[Unit]
