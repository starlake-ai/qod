package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.module.ResolvedSourceType
import cats.effect.IO
import sttp.model.StatusCode

/** The module-provided federated source types (`ModuleSurfaces.sourceTypes`, checked at boot), as
  * the console's federation form lists them. The guard admits admin sessions only, as on the
  * federated-source routes; a credential that stopped resolving after the guard is refused here.
  */
final class FederatedSourceTypeHandlers(
    types: List[ResolvedSourceType],
    scopeOf: SessionScope.Resolver
):
  private val views =
    types.map(t =>
      FederatedSourceTypeView(t.module, t.sourceType, t.label, t.editorBundle, t.editsHeldSources)
    )

  def list(apiKey: Option[String]): HandlerErrors.Out[FederatedSourceTypesResponse] =
    IO.blocking {
      if scopeOf(apiKey) == SessionScope.NoAccess then
        Left(
          StatusCode.Unauthorized ->
            ErrorResponse("unauthorized", "sign in to list the federated source types")
        )
      else Right(FederatedSourceTypesResponse(views))
    }
