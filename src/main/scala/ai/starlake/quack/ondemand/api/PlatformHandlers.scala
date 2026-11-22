package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.{PlatformRole, UserKind}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import sttp.model.StatusCode

/** REST handlers for platform staff roles and staff users (issue #274), superuser-only. The
  * supervisor's platform-role methods are synchronous and wait on its versions lock, so every
  * handler runs on `IO.blocking`.
  */
final class PlatformHandlers(
    sup: PoolSupervisor,
    users: UserHandlers,
    audit: AuditRecorder = AuditRecorder.noop
):
  import HandlerErrors.Out

  private val SuperuserMessage = "platform roles are managed by superusers"

  private def view(r: PlatformRole): PlatformRoleView =
    PlatformRoleView(r.id, r.name, r.description, r.permissions.toList.sorted)

  private def supErr(e: SupervisorError): (StatusCode, ErrorResponse) =
    SupervisorErrorHttp
      .special(e)
      .getOrElse(e match
        case SupervisorError.NotFound(m) =>
          StatusCode.NotFound -> ErrorResponse("not_found", m)
        // The supervisor logged the cause; never echo driver text to the caller.
        case SupervisorError.Internal(_) =>
          StatusCode.InternalServerError -> ErrorResponse(
            "internal",
            "could not change the platform role"
          )
        case other =>
          StatusCode.BadRequest -> ErrorResponse("invalid_platform_role", other.message))

  /** Runs `f` on `IO.blocking` for a superuser, else answers `403 superuser_required` (audited as
    * denied when `action` names a write).
    */
  private def superuserOnly[A](apiKey: Option[String], action: Option[String])(
      scopeOf: SessionScope.Resolver
  )(f: => Either[(StatusCode, ErrorResponse), A]): Out[A] =
    IO.blocking {
      SuperuserCheck.reject(apiKey, SuperuserMessage)(scopeOf) match
        case Some(err) =>
          action.foreach(a => audit.rest(apiKey, "control-plane", a, "denied"))
          Left(err)
        case None => f
    }

  private def outcome(out: Either[?, ?]): String = if out.isRight then "ok" else "error"

  def listPermissions(apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PlatformPermissionListResponse] =
    superuserOnly(apiKey, None)(scopeOf) {
      val declared = sup.declaredPermissions
      val names    = declared.map(_.name).toSet
      val stored   =
        sup.listPlatformRoles().flatMap(_.permissions).toSet.diff(names).toList.sorted
      Right(
        PlatformPermissionListResponse(
          declared
            .sortBy(_.name)
            .map(d =>
              PlatformPermissionView(d.name, Some(d.module), Some(d.description), declared = true)
            ) ++
            stored.map(n => PlatformPermissionView(n, None, None, declared = false))
        )
      )
    }

  def listRoles(apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PlatformRoleListResponse] =
    superuserOnly(apiKey, None)(scopeOf)(
      Right(PlatformRoleListResponse(sup.listPlatformRoles().sortBy(_.name).map(view)))
    )

  def createRole(req: PlatformRoleCreateRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PlatformRoleView] =
    superuserOnly(apiKey, Some(AuditActions.PlatformRoleCreate))(scopeOf) {
      val out = sup
        .createPlatformRole(req.name, req.description, req.permissions.toSet)
        .map(view)
        .left
        .map(supErr)
      audit.rest(
        apiKey,
        "control-plane",
        AuditActions.PlatformRoleCreate,
        outcome(out),
        target = Some(req.name),
        detail = Map("permissions" -> req.permissions.sorted.mkString(","))
      )
      out
    }

  /** An absent `description` keeps it, an empty one clears it. */
  def updateRole(req: PlatformRoleUpdateRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[PlatformRoleView] =
    superuserOnly(apiKey, Some(AuditActions.PlatformRoleUpdate))(scopeOf) {
      val out = sup
        .updatePlatformRole(
          req.id,
          req.name,
          req.description.map(d => Option(d).filter(_.nonEmpty)),
          req.permissions.map(_.toSet)
        )
        .map(view)
        .left
        .map(supErr)
      audit.rest(
        apiKey,
        "control-plane",
        AuditActions.PlatformRoleUpdate,
        outcome(out),
        target = Some(req.id),
        detail = req.permissions
          .map(ps => Map("permissions" -> ps.sorted.mkString(",")))
          .getOrElse(Map.empty)
      )
      out
    }

  def deleteRole(req: PlatformRoleDeleteRequest, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    superuserOnly(apiKey, Some(AuditActions.PlatformRoleDelete))(scopeOf) {
      val out = sup.deletePlatformRole(req.id).left.map(supErr)
      audit.rest(
        apiKey,
        "control-plane",
        AuditActions.PlatformRoleDelete,
        outcome(out),
        target = Some(req.id)
      )
      out
    }

  /** The tenant-less `staff` rows, each with its platform role names. */
  def listStaff(apiKey: Option[String])(scopeOf: SessionScope.Resolver): Out[StaffListResponse] =
    superuserOnly(apiKey, None)(scopeOf) {
      val staff =
        sup.listSuperusers().filter(_.kind.equalsIgnoreCase(UserKind.Staff)).sortBy(_.username)
      Right(StaffListResponse(users.responsesFor(staff)))
    }
