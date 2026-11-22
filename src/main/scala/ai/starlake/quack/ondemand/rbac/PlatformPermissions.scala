package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.ondemand.state.RbacUser

/** A permission a loaded manager module declares (issue #274). */
final case class DeclaredPermission(name: String, module: String, description: String)

/** A tenant-less `staff` row and the union of its platform roles' permissions. */
final case class StaffPrincipal(user: RbacUser, permissions: Set[String])

object PlatformPermissions:
  /** Reserved for core's own permissions (none in v1). */
  val ReservedPrefix: String = "platform."

  /** Superusers hold every permission implicitly; no stored role may take the name. */
  val ReservedRoleName: String = "superuser"

  /** Dot-separated segments of a-z, 0-9 and '_', none empty. */
  private val Rest = "[a-z0-9_]+(\\.[a-z0-9_]+)*".r

  /** Some(reason) when `name` is not a valid permission name for `module`. */
  def nameRefusal(module: String, name: String): Option[String] =
    val prefix = s"$module."
    if name.startsWith(ReservedPrefix) then
      Some(s"permission '$name' uses the reserved prefix '$ReservedPrefix'")
    else if !name.startsWith(prefix) then
      Some(s"permission '$name' must start with the module name: '$prefix'")
    else
      val rest = name.stripPrefix(prefix)
      if !Rest.matches(rest) then
        Some(
          s"permission '$name': after '$prefix' only a-z, 0-9 and '_' segments joined by '.' " +
            "are allowed"
        )
      else None

/** Boot's report of stored permissions no loaded module declares (issue #274). Such a permission is
  * kept, so a backup always restores and a module loaded again finds its roles intact, but it
  * grants nothing while no module declares it.
  */
object PlatformPermissionReport:
  /** One stderr `WARN:` line per stored permission outside `declared`, by role then permission. */
  def lines(
      roles: List[ai.starlake.quack.ondemand.state.PlatformRole],
      declared: Set[String]
  ): List[String] =
    roles
      .sortBy(_.name)
      .flatMap(r =>
        r.permissions
          .diff(declared)
          .toList
          .sorted
          .map(p =>
            s"WARN: platform role '${r.name}' grants '$p', which no loaded module declares; it has " +
              "no effect"
          )
      )
