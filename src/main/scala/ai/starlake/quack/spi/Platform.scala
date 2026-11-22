package ai.starlake.quack.spi

import ai.starlake.quack.ondemand.api.ErrorResponse
import sttp.model.StatusCode

/** A permission a module declares (issue #274). `name` is `<module name>.<rest>`, `rest` over
  * `[a-z0-9._]`; boot refuses anything else. Platform roles bundle these names for staff users.
  */
final case class PlatformPermission(name: String, description: String)

/** A page a module adds to the admin console, at `/ui/x/<module>/<id>`. `permission` is one the
  * module declares; `bundle` is an origin-relative path under one of the module's own
  * `staticMounts`, serving an ES module that exports `qodConsoleContract = 1` and `mount(el, ctx)`
  * (console page contract 1).
  */
final case class ConsolePage(id: String, label: String, permission: String, bundle: String)

/** A caller of the platform: a superuser (every permission) or a staff user (its platform roles'
  * permissions). Part of the stable SPI.
  */
final case class PlatformCaller(
    userId: Option[String],
    username: String,
    superuser: Boolean,
    permissions: Set[String]
):
  def has(permission: String): Boolean = superuser || permissions.contains(permission)

  /** Merge into the `detail` of the module's `ctx.audit` row for the action. */
  def auditDetail(permission: String): Map[String, String] =
    Map("permission" -> permission, "actorKind" -> (if superuser then "superuser" else "staff"))

object PermissionCheck:
  /** None when the caller holds `permission`; else 403 permission_required naming it. */
  def require(
      caller: Option[PlatformCaller],
      permission: String
  ): Option[(StatusCode, ErrorResponse)] =
    if caller.exists(_.has(permission)) then None
    else
      Some(
        StatusCode.Forbidden -> ErrorResponse(
          "permission_required",
          s"this action requires the permission '$permission'"
        )
      )
