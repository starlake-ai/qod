package ai.starlake.quack.ondemand.state

/** One row from `qodstate_user` viewed as a management-plane grant.
  *
  * `tenant = None` with kind `admin` is a superuser grant (a tenant-less `staff` grant is not).
  * Non-empty `tenant` is a tenant-scoped grant. `kind` is the account kind in `qodstate_user.kind`
  * (`admin` | `user` | `staff`); only `equalsIgnoreCase("admin")` carries management privileges.
  */
final case class UserGrant(tenant: Option[String], kind: String):
  /** [[UserKind.isSuperuser]] of this grant's row. */
  def isSuperuser: Boolean = UserKind.isSuperuser(tenant, kind)
