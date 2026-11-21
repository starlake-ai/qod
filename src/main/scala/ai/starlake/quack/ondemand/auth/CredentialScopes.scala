package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.ondemand.api.SessionTokenStore
import ai.starlake.quack.ondemand.rbac.StaffPrincipal
import ai.starlake.quack.ondemand.state.UserKind

/** The one resolver from a credential (a session JWT, then a PAT) to its session and scope. A staff
  * session is checked against its live row on every call (issue #274): its JWT names the user, so a
  * row that is gone, disabled or no longer staff yields no session at all, and the permissions are
  * the row's current ones, never a copy taken at login.
  *
  * `staffPrincipal` must answer None for anything but an enabled tenant-less `staff` row
  * (`PoolSupervisor.staffPrincipal`); a lookup that answers nothing ends every staff session, so a
  * missing wiring fails closed.
  */
final class CredentialScopes(
    sessions: SessionTokenStore,
    pats: Option[PatAuthenticator],
    staffPrincipal: String => Option[StaffPrincipal]
):
  /** `PatAuthenticator.sessionOf`'s `authMethod`. */
  private val PatAuthMethod = "pat"

  private def isStaff(s: SessionTokenStore.Session): Boolean =
    s.profile.role.equalsIgnoreCase(UserKind.Staff)

  /** Whether [[sessionOf]] reads the control plane for the session JWT `s` (a staff one): the
    * lookup may block on the database, so a caller on a compute thread shifts it off. Any other
    * session JWT resolves to itself.
    */
  def readsRow(s: SessionTokenStore.Session): Boolean = isStaff(s)

  /** A staff session JWT with its live permissions, None when its row no longer qualifies (or the
    * session names a tenant, which no staff row has); any other session unchanged.
    */
  private def live(s: SessionTokenStore.Session): Option[SessionTokenStore.Session] =
    if !isStaff(s) then Some(s)
    else if s.profile.tenant.isDefined then None
    else
      staffPrincipal(s.profile.username).map(sp =>
        s.copy(scope = s.scope.copy(platformPermissions = sp.permissions))
      )

  /** A staff session with its live permissions, None when its row no longer qualifies; any other
    * session unchanged. A PAT's session is left as it is: [[PatAuthenticator]] already filled it
    * from the token's own row.
    */
  def enrich(s: SessionTokenStore.Session): Option[SessionTokenStore.Session] =
    if s.profile.authMethod == PatAuthMethod then Some(s) else live(s)

  /** The session behind `token`: a session JWT (checked live when it is a staff one), else a PAT. A
    * JWT never reaches the PAT branch, so a staff JWT whose row stopped qualifying is None.
    */
  def sessionOf(token: String): Option[SessionTokenStore.Session] =
    sessions.get(token) match
      case Some(s) => live(s)
      case None    => pats.flatMap(_.sessionOf(token))

  val scopeOf: SessionScope.Resolver =
    SessionScope.resolver(t => sessionOf(t).map(_.scope))
