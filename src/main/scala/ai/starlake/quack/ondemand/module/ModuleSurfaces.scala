package ai.starlake.quack.ondemand.module

import ai.starlake.quack.ondemand.rbac.{DeclaredPermission, PlatformPermissions}
import ai.starlake.quack.spi.ManagerModule
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
  * module's start(). A Left aborts the boot, naming the module and the item.
  */
final case class ModuleSurfaces(
    permissions: List[DeclaredPermission],
    staffPrefixes: Set[String],
    pages: List[ResolvedConsolePage]
)

object ModuleSurfaces:
  val Empty: ModuleSurfaces = ModuleSurfaces(Nil, Set.empty, Nil)

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

  private val PageId = "[a-z0-9-]+".r

  private def under(path: String, prefix: String): Boolean =
    val p = prefix.stripSuffix("/")
    p.nonEmpty && (path == p || path.startsWith(p + "/"))

  private def bundleRefusal(m: ManagerModule, bundle: String): Option[String] =
    val shaped =
      bundle.startsWith("/") && !bundle.startsWith("//") && !bundle.contains("://") &&
        !bundle.contains("?") && !bundle.contains("#") &&
        !bundle.split('/').exists(seg => seg == ".." || seg == ".")
    if !shaped then
      Some(s"bundle '$bundle' must be an origin-relative path with no '..', '?' or '#'")
    else if !m.staticMounts.exists(sm => under(bundle, sm.urlPrefix)) then
      Some(s"bundle '$bundle' is not under any of the module's static mounts")
    else None

  /** `coreApiSegments`: the first segments after `api` that core serves or reserves. The REST guard
    * leaves the URL tenant check of a staff caller on a staff prefix to the module, trusting that
    * the prefix lies under `/api/<module name>/`; a module named after a core segment would make
    * such a prefix cover core routes, so a module with any platform surface may not take one.
    */
  def validate(
      modules: List[ManagerModule],
      coreApiSegments: Set[String]
  ): Either[String, ModuleSurfaces] =
    val errs  = List.newBuilder[String]
    val seen  = scala.collection.mutable.Set.empty[String]
    val perms = List.newBuilder[DeclaredPermission]
    val pages = List.newBuilder[ResolvedConsolePage]
    val core  = coreApiSegments.map(_.toLowerCase(java.util.Locale.ROOT))
    modules.foreach { m =>
      def err(msg: String): Unit = errs += s"module ${m.name}: $msg"
      val hasSurface             =
        m.permissions.nonEmpty || m.staffPathPrefixes.nonEmpty || m.consolePages.nonEmpty
      val segment = m.name.toLowerCase(java.util.Locale.ROOT)
      if hasSurface && core.contains(segment) then
        err(
          s"the name is the core API segment '$segment' (/api/$segment/); a module declaring " +
            "permissions, staff path prefixes or console pages must take another name"
        )
      m.permissions.foreach { p =>
        PlatformPermissions.nameRefusal(m.name, p.name).foreach(err)
        if !seen.add(p.name) then err(s"permission '${p.name}' is declared twice")
        perms += DeclaredPermission(p.name, m.name, p.description)
      }
      m.staffPathPrefixes.foreach { pre =>
        val root = s"/api/${m.name}/"
        if !pre.startsWith(root) || pre.length == root.length then
          err(s"staff path prefix '$pre' must lie under '$root'")
      }
      val own     = m.permissions.map(_.name).toSet
      val pageIds = scala.collection.mutable.Set.empty[String]
      m.consolePages.foreach { pg =>
        if !PageId.matches(pg.id) then err(s"console page id '${pg.id}' must match [a-z0-9-]+")
        if !pageIds.add(pg.id) then err(s"console page id '${pg.id}' is declared twice")
        if !own.contains(pg.permission) then
          err(
            s"console page '${pg.id}' needs permission '${pg.permission}', which the module " +
              "does not declare"
          )
        bundleRefusal(m, pg.bundle).foreach(r => err(s"console page '${pg.id}': $r"))
        pages += ResolvedConsolePage(m.name, pg.id, pg.label, pg.permission, pg.bundle)
      }
    }
    val all = errs.result()
    if all.nonEmpty then Left(all.mkString("; "))
    else
      Right(
        ModuleSurfaces(perms.result(), modules.flatMap(_.staffPathPrefixes).toSet, pages.result())
      )
