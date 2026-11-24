package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.PoolKey
import cats.effect.IO

/** DuckLake name-history checks retained while policies are keyed on current names. */
trait ColumnCatalog:
  /** Whether the name has meant one and the same table, with the same column names, in every
    * snapshot the catalog holds (`DuckLakeCatalogReader.nameHistoryStable`): what a time-travel
    * read under a column or row policy needs, since policies match today's names. False when it
    * cannot tell (fail closed). `pool` is the statement's: a branch pool attaches its own catalog
    * under the parent's alias, so the history read is the branch's, not the parent's.
    */
  def historyStable(
      tenant: String,
      pool: Option[PoolKey],
      catalog: String,
      schemaName: String,
      tableName: String
  ): IO[Boolean] =
    IO.pure(false)

object ColumnCatalog:
  /** Static history fixture; production uses [[CachedColumnCatalog]]. */
  final class MapCatalog(stable: Set[(String, String, String)] = Set.empty) extends ColumnCatalog:
    override def historyStable(
        tenant: String,
        pool: Option[PoolKey],
        c: String,
        s: String,
        t: String
    ): IO[Boolean] = IO.pure(stable.contains((c, s, t)))
