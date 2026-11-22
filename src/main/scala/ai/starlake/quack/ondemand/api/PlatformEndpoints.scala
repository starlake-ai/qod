package ai.starlake.quack.ondemand.api

import Dtos.given
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Platform staff roles and the console's module pages (issue #274). Superuser-only except
  * `console/pages`, which any signed-in caller reads.
  */
object PlatformEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val listPermissions: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    PlatformPermissionListResponse,
    Any
  ] =
    base.get
      .in("platform" / "permission" / "list")
      .in(Endpoints.authToken)
      .out(jsonBody[PlatformPermissionListResponse])

  val listRoles: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    PlatformRoleListResponse,
    Any
  ] =
    base.get
      .in("platform" / "role" / "list")
      .in(Endpoints.authToken)
      .out(jsonBody[PlatformRoleListResponse])

  val createRole: PublicEndpoint[
    (PlatformRoleCreateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PlatformRoleView,
    Any
  ] =
    base.post
      .in("platform" / "role" / "create")
      .in(jsonBody[PlatformRoleCreateRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[PlatformRoleView])

  val updateRole: PublicEndpoint[
    (PlatformRoleUpdateRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    PlatformRoleView,
    Any
  ] =
    base.post
      .in("platform" / "role" / "update")
      .in(jsonBody[PlatformRoleUpdateRequest])
      .in(Endpoints.authToken)
      .out(jsonBody[PlatformRoleView])

  val deleteRole: PublicEndpoint[
    (PlatformRoleDeleteRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    Unit,
    Any
  ] =
    base.post
      .in("platform" / "role" / "delete")
      .in(jsonBody[PlatformRoleDeleteRequest])
      .in(Endpoints.authToken)

  val listStaff: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    StaffListResponse,
    Any
  ] =
    base.get
      .in("platform" / "staff" / "list")
      .in(Endpoints.authToken)
      .out(jsonBody[StaffListResponse])

  val consolePages: PublicEndpoint[
    Option[String],
    (sttp.model.StatusCode, ErrorResponse),
    ConsolePagesResponse,
    Any
  ] =
    base.get
      .in("console" / "pages")
      .in(Endpoints.authToken)
      .out(jsonBody[ConsolePagesResponse])
