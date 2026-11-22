package ai.starlake.quack.ondemand.module

import ai.starlake.quack.ondemand.rbac.{DeclaredPermission, PlatformPermissions}
import ai.starlake.quack.spi.{ConsolePage, ManagerModule, PlatformPermission, StaticMount}
import sttp.tapir.AnyEndpoint

final case class ResolvedConsolePage(
    module: String,
    id: String,
    label: String,
    permission: String,
    bundle: String
):
  def path: String = s"/ui/x/$module/$id"

/** The platform surfaces of the loaded modules (issue #274), validated once at boot after every
  * module's start(). A Left aborts the boot, naming the module and the item. `publicPrefixes` are
  * every module's public path prefixes as validated, the values the server is given: a getter is
  * read once, so a second read can never hand the guard a prefix nobody checked.
  */
final case class ModuleSurfaces(
    permissions: List[DeclaredPermission],
    staffPrefixes: Set[String],
    pages: List[ResolvedConsolePage],
    publicPrefixes: Set[String]
)

object ModuleSurfaces:
  val Empty: ModuleSurfaces = ModuleSurfaces(Nil, Set.empty, Nil, Set.empty)

  /** First segments after `api` that core reserves for routes of its own to come (the platform API
    * and the console page list), on top of those its endpoints already hold.
    */
  val ReservedApiSegments: Set[String] = Set("platform", "console")

  /** The first path segment after `api` of each endpoint under `/api` (a capture there reads as its
    * `{name}` template, which no module name can be).
    */
  def apiSegments(endpoints: List[AnyEndpoint]): Set[String] =
    endpoints.flatMap { e =>
      e.showPathTemplate().takeWhile(_ != '?').split('/').toList.filter(_.nonEmpty) match
        case "api" :: segment :: _ => Some(segment)
        case _                     => None
    }.toSet

  /** The first path segments after `api` that core serves or reserves, as Main hands them to
    * [[validate]]. Every core endpoint ManagerServer serves under /api is in EndpointModules
    * (DocEndpoints.all) except the undocumented security audit, added by hand; /data/v1 lies
    * outside /api.
    */
  def coreApiSegments: Set[String] =
    apiSegments(
      ai.starlake.quack.docs.DocEndpoints.all :+
        ai.starlake.quack.ondemand.api.SecurityAuditEndpoints.audit
    ) ++ ReservedApiSegments

  private val PageId     = "[a-z0-9-]+".r
  private val ModuleName = "[a-z0-9-]+".r

  /** A path segment a browser and the router read as itself: no escape, backslash, whitespace or
    * control character, and never `.` or `..`.
    */
  private val PlainSegment = "[A-Za-z0-9._-]+".r

  private def plain(segment: String): Boolean =
    PlainSegment.matches(segment) && segment != "." && segment != ".."

  /** The segments of an absolute path, or None when it does not start with exactly one '/' or holds
    * a segment that is empty (a trailing '/' included) or not plain.
    */
  private def plainSegments(path: String): Option[List[String]] =
    if !path.startsWith("/") then None
    else
      val segs = path.substring(1).split("/", -1).toList
      Option.when(segs.forall(plain))(segs)

  private def bundleRefusal(mounts: List[StaticMount], bundle: String): Option[String] =
    plainSegments(bundle) match
      case None =>
        Some(
          s"bundle '$bundle' must be an origin-relative path of segments over [A-Za-z0-9._-], " +
            "none empty, '.' or '..'"
        )
      case Some(segs) =>
        // Whole segments: a mount's own segments must start the bundle's, and a root mount (/)
        // has none, so it covers no bundle.
        val covered = mounts.exists { sm =>
          val ms = sm.urlPrefix.split('/').toList.filter(_.nonEmpty)
          ms.nonEmpty && segs.length > ms.length && segs.take(ms.length) == ms
        }
        Option.unless(covered)(
          s"bundle '$bundle' is not under any of the module's static mounts (a root mount (/) " +
            "covers no bundle)"
        )

  private def staffPrefixRefusal(module: String, prefix: String): Option[String] =
    val ok = plainSegments(prefix).exists {
      case "api" :: `module` :: rest => rest.nonEmpty
      case _                         => false
    }
    Option.unless(ok)(
      s"staff path prefix '$prefix' must lie under '/api/$module/', as segments over " +
        "[A-Za-z0-9._-], none empty, '.' or '..'"
    )

  /** Why a module may not declare `prefix` public, if it may not. Every module, with or without a
    * platform surface: segments over [A-Za-z0-9._-], none empty, `.` or `..` (a trailing `/`
    * included), and then either `/api/<first>/<more>` with `<first>` neither a core API segment
    * (the guard would admit core routes with no credential) nor another loaded module's name, or a
    * path at or under one of the module's own static mounts other than the root one (outside `/api`
    * the guard admits everything anyway). Not `/api/<module name>/` alone: a module's routes need
    * not lie under its name (the fixture module `test-module` serves `/api/hosted-test/`).
    */
  private def publicPrefixRefusal(
      prefix: String,
      mounts: List[StaticMount],
      coreApiSegments: Set[String],
      otherModules: Set[String]
  ): Option[String] =
    def refusal(why: String) = Some(s"public path prefix '$prefix' $why")
    plainSegments(prefix) match
      case None =>
        refusal(
          "must be an absolute path of segments over [A-Za-z0-9._-], none empty, '.' or '..'"
        )
      case Some("api" :: first :: rest) =>
        if rest.isEmpty then refusal(s"must lie under '/api/$first/', not at it")
        else if coreApiSegments.contains(first) then
          refusal(s"lies under the core API segment '/api/$first/'")
        else if otherModules.contains(first) then
          refusal(s"lies under '/api/$first/', the name of another loaded module")
        else None
      case Some("api" :: Nil) => refusal("would cover every /api route")
      case Some(segs)         =>
        val covered = mounts.exists { sm =>
          val ms = sm.urlPrefix.split('/').toList.filter(_.nonEmpty)
          ms.nonEmpty && segs.startsWith(ms)
        }
        if covered then None
        else
          refusal(
            "must lie under '/api/<segment>/' or under one of the module's static mounts (a " +
              "root mount (/) covers none)"
          )

  /** What one module declares, read once. */
  private final case class Declared(
      module: ManagerModule,
      permissions: List[PlatformPermission],
      staffPrefixes: Set[String],
      pages: List[ConsolePage],
      mounts: List[StaticMount],
      publicPrefixes: Set[String]
  ):
    def hasSurface: Boolean = permissions.nonEmpty || staffPrefixes.nonEmpty || pages.nonEmpty

  /** `coreApiSegments`: the first segments after `api` that core serves or reserves. Every module's
    * public path prefixes are checked first (see [[publicPrefixRefusal]]). The REST guard leaves
    * the URL tenant check of a staff caller on a staff prefix to the module, trusting that the
    * prefix lies under `/api/<module name>/`; a module named after a core segment would make such a
    * prefix cover core routes, so a module with any platform surface may not take one. Such a
    * module's name must also match [a-z0-9-]+ and be unique among them; a module with no surface is
    * not checked. Each module's surfaces are read once, and the result is built from those same
    * values.
    */
  def validate(
      modules: List[ManagerModule],
      coreApiSegments: Set[String]
  ): Either[String, ModuleSurfaces] =
    val declared = modules.map(m =>
      Declared(
        m,
        m.permissions,
        m.staffPathPrefixes,
        m.consolePages,
        m.staticMounts,
        m.publicPathPrefixes
      )
    )
    val errs     = List.newBuilder[String]
    val seen     = scala.collection.mutable.Set.empty[String]
    val names    = scala.collection.mutable.Set.empty[String]
    val perms    = List.newBuilder[DeclaredPermission]
    val prefixes = Set.newBuilder[String]
    val pages    = List.newBuilder[ResolvedConsolePage]
    declared.zipWithIndex.foreach { (d, i) =>
      val others = declared.zipWithIndex.collect { case (o, j) if j != i => o.module.name }.toSet
      d.publicPrefixes.toList.sorted.foreach { pre =>
        publicPrefixRefusal(pre, d.mounts, coreApiSegments, others).foreach(r =>
          errs += s"module ${d.module.name}: $r"
        )
      }
    }
    declared.filter(_.hasSurface).foreach { d =>
      val name                   = d.module.name
      def err(msg: String): Unit = errs += s"module $name: $msg"
      if !ModuleName.matches(name) then
        err(s"module name '$name' must match [a-z0-9-]+ to declare a platform surface")
      if !names.add(name) then err(s"module name '$name' is used twice")
      if coreApiSegments.contains(name) then
        err(
          s"the name is the core API segment '$name' (/api/$name/); a module declaring " +
            "permissions, staff path prefixes or console pages must take another name"
        )
      d.permissions.foreach { p =>
        PlatformPermissions.nameRefusal(name, p.name).foreach(err)
        if !seen.add(p.name) then err(s"permission '${p.name}' is declared twice")
        perms += DeclaredPermission(p.name, name, p.description)
      }
      d.staffPrefixes.foreach { pre =>
        staffPrefixRefusal(name, pre).foreach(err)
        prefixes += pre
      }
      val own     = d.permissions.map(_.name).toSet
      val pageIds = scala.collection.mutable.Set.empty[String]
      d.pages.foreach { pg =>
        if !PageId.matches(pg.id) then err(s"console page id '${pg.id}' must match [a-z0-9-]+")
        if !pageIds.add(pg.id) then err(s"console page id '${pg.id}' is declared twice")
        if !own.contains(pg.permission) then
          err(
            s"console page '${pg.id}' needs permission '${pg.permission}', which the module " +
              "does not declare"
          )
        bundleRefusal(d.mounts, pg.bundle).foreach(r => err(s"console page '${pg.id}': $r"))
        pages += ResolvedConsolePage(name, pg.id, pg.label, pg.permission, pg.bundle)
      }
    }
    val all = errs.result()
    if all.nonEmpty then Left(all.mkString("; "))
    else
      Right(
        ModuleSurfaces(
          perms.result(),
          prefixes.result(),
          pages.result(),
          declared.flatMap(_.publicPrefixes).toSet
        )
      )
