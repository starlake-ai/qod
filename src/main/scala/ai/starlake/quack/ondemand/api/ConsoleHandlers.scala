package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.module.ResolvedConsolePage
import ai.starlake.quack.spi.PlatformCaller
import cats.effect.IO

/** The console's module pages (issue #274): each page whose permission the caller holds. A
  * superuser holds every permission, a staff user its roles' permissions, and anyone else none, so
  * a tenant admin or user reads an empty list. `platformCallerOf` can read the control plane (a
  * PAT's verify, a cold staff cache), so the lookup runs on `IO.blocking`.
  */
final class ConsoleHandlers(
    pages: List[ResolvedConsolePage],
    platformCallerOf: Option[String] => Option[PlatformCaller]
):
  def list(apiKey: Option[String]): HandlerErrors.Out[ConsolePagesResponse] = IO.blocking {
    val caller = platformCallerOf(apiKey)
    Right(
      ConsolePagesResponse(
        pages
          .filter(p => caller.exists(_.has(p.permission)))
          .map(p => ConsolePageView(p.module, p.id, p.label, p.path, p.bundle))
      )
    )
  }
