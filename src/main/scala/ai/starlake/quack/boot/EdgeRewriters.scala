package ai.starlake.quack.boot

import ai.starlake.quack.edge.cls.{CachedColumnCatalog, ColumnCatalog}
import ai.starlake.quack.model.{PoolKey, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.route.{StatementClassifier, StatementClassifierConfig}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

/** Statement-pipeline components built from operator config, extracted from Main.bootManager: the
  * routing classifier and the shared policy guards.
  */
object EdgeRewriters extends LazyLogging:

  /** Build the routing classifier from operator config. Defaults live in application.conf under
    * `quack-on-demand.statementClassifier.*`; matching `QOD_CLASSIFIER_*` env vars override.
    * Comma-separated.
    */
  def statementClassifier(): StatementClassifier =
    val classifierRoot =
      com.typesafe.config.ConfigFactory.load().getConfig("quack-on-demand.statementClassifier")
    val classifierCfg = StatementClassifierConfig(
      select = StatementClassifierConfig.parseCsv(classifierRoot.getString("select")),
      dml = StatementClassifierConfig.parseCsv(classifierRoot.getString("dml")),
      ddl = StatementClassifierConfig.parseCsv(classifierRoot.getString("ddl")),
      begin = StatementClassifierConfig.parseCsv(classifierRoot.getString("begin")),
      commit = StatementClassifierConfig.parseCsv(classifierRoot.getString("commit")),
      rollback = StatementClassifierConfig.parseCsv(classifierRoot.getString("rollback"))
    )
    new StatementClassifier(classifierCfg)

  /** Historical-name checks use DuckLake metadata and never probe a node for its schema. */
  def columnCatalog(
      sup: PoolSupervisor,
      readerOf: (String, String) => DuckLakeCatalogReader
  ): ColumnCatalog =
    new CachedColumnCatalog(
      history = (tenant, pool, cat, sch, tab) =>
        IO.blocking(
          duckLakeReader(sup, readerOf, tenant, pool, cat).exists(_.nameHistoryStable(sch, tab))
        ),
      latestSnapshot = (tenant, pool, cat) =>
        IO.blocking(
          duckLakeReader(sup, readerOf, tenant, pool, cat).flatMap(_.maxSnapshotIdBounded())
        )
    )

  /** The reader of [[duckLakeRow]]'s row. */
  private def duckLakeReader(
      sup: PoolSupervisor,
      readerOf: (String, String) => DuckLakeCatalogReader,
      tenant: String,
      pool: Option[PoolKey],
      cat: String
  ): Option[DuckLakeCatalogReader] =
    duckLakeRow(sup, tenant, pool, cat)
      .flatMap(td => sup.getTenantById(td.tenantId).map(t => readerOf(t.id, td.name)))

  /** The DuckLake row whose catalog `cat` names for a statement on `pool`: the pool's own row when
    * it attaches under `cat` (a branch attaches under its parent's alias, so its history is its
    * own), else the tenant's DuckLake row of that name or alias. None for anything else (a
    * federated or built-in catalog): no history, which the guard reads as unstable.
    */
  private def duckLakeRow(
      sup: PoolSupervisor,
      tenant: String,
      pool: Option[PoolKey],
      cat: String
  ): Option[TenantDb] =
    val own = pool
      .filter(_.tenant == tenant)
      .flatMap(k => sup.findTenantDb(k.tenant, k.tenantDb).map(td => (k, td)))
      .collect {
        case (k, td)
            if td.kind == TenantDbKind.DuckLake &&
              TenantDb
                .catalogAlias(sup.effectiveMetastoreFor(k.tenant, k.tenantDb))
                .equalsIgnoreCase(cat) =>
          td
      }
    own.orElse(sup.findTenantDbByCatalogName(tenant, cat).filter(_.kind == TenantDbKind.DuckLake))

  /** Write and history guard using the same policy enable flags as signed transport. */
  def protectedWriteGuard(
      catalog: ColumnCatalog
  ): ai.starlake.quack.edge.policy.ProtectedWriteGuard =
    val clsEnabled =
      com.typesafe.config.ConfigFactory
        .load()
        .getConfig("quack-on-demand.cls")
        .getBoolean("enabled")
    val rlsEnabled =
      com.typesafe.config.ConfigFactory.load().getBoolean("quack-on-demand.rls.enabled")
    new ai.starlake.quack.edge.policy.ProtectedWriteGuard(
      catalog,
      clsEnabled,
      rlsEnabled
    )
