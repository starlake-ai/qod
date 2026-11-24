package ai.starlake.quack.edge.rls

import ai.starlake.quack.model.{TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, RbacRole, RoleRowPolicy}
import ai.starlake.sql.IdentifierFold

import scala.util.Try

/** The boot report of stored row policies that cannot apply: a predicate naming a column its table
  * does not have (the column was dropped or renamed after the policy was written), or one the
  * rewriter cannot place at all. Each denies every statement reading its table under its role ("row
  * policy failed to apply"), so an administrator learns of it here rather than from users. One line
  * per policy and database, on stderr (the logger's default level is ERROR), naming the tenant, the
  * role, the policy, the table and the missing columns, never the predicate.
  *
  * Only a policy naming its schema and table is checked, against the tenant's DuckLake databases
  * its catalog covers (a wildcard catalog covers every one of them, as at rewrite time; branches
  * are left out). A table a database does not hold, or whose columns cannot be read, is skipped:
  * the report reads, it never decides.
  */
object StaleRowPolicyReport:

  /** A DuckLake database of a tenant, by the catalog name its statements read it under. */
  final case class Database(tenantId: String, name: String, catalog: String)

  private def wildcard(s: String): Boolean = s == RoleRowPolicy.Wildcard

  private def fold(s: String): String = IdentifierFold.fold(s)

  /** The WARN lines, sorted. `columnsOf(database, schema, table)` lists the table's columns, empty
    * when the database does not hold it or they cannot be read.
    */
  def lines(
      roles: List[RbacRole],
      policies: List[RoleRowPolicy],
      databases: List[Database],
      columnsOf: (Database, String, String) => List[String]
  ): List[String] =
    val byId = roles.map(r => r.id -> r).toMap
    policies
      .filterNot(p => wildcard(p.schemaName) || wildcard(p.tableName))
      .flatMap { p =>
        byId.get(p.roleId).toList.flatMap { r =>
          databases
            .filter(d =>
              d.tenantId == r.tenantId &&
                (wildcard(p.catalogName) || fold(p.catalogName) == fold(d.catalog))
            )
            .flatMap { d =>
              val columns = columnsOf(d, p.schemaName, p.tableName)
              Option.when(columns.nonEmpty)(()).flatMap { _ =>
                val where =
                  s"WARN: tenant '${r.tenantId}' role '${r.name}': row policy ${p.id} on " +
                    s"${d.catalog}.${p.schemaName}.${p.tableName}"
                val consequence =
                  " Every statement reading the table under this role is denied (\"row policy " +
                    "failed to apply\"): update the policy, or delete it."
                RowPolicyRewriter.predicateColumns(
                  p.predicateSql,
                  d.catalog,
                  p.schemaName,
                  p.tableName
                ) match
                  case Left(cause) =>
                    val why =
                      if cause == RowPolicyRewriter.UnqualifiableCause then
                        "holds a subquery whose columns are not all qualified by its own FROM " +
                          "items or by the table's name"
                      else "cannot be read as a predicate"
                    Some(s"$where cannot be applied: its predicate $why.$consequence")
                  case Right(read) =>
                    val missing = (read -- columns.map(fold).toSet).toList.sorted
                    Option.when(missing.nonEmpty)(
                      s"$where names ${if missing.size == 1 then "a column" else "columns"} " +
                        s"the table does not have: ${missing.mkString(", ")}.$consequence"
                    )
              }
            }
        }
      }
      .sorted

  /** [[lines]] of every tenant's row policies, against the DuckLake databases `sup` holds, their
    * columns read through `readerOf(tenantId, database)`. Call it after the supervisor's first
    * `restore()`: it resolves each database's catalog name from the supervisor.
    */
  def linesOf(
      store: ControlPlaneStore,
      sup: PoolSupervisor,
      readerOf: (String, String) => DuckLakeCatalogReader
  ): List[String] =
    val roles     = store.listTenants().flatMap(t => store.listRoles(t.id))
    val policies  = roles.flatMap(r => store.listRowPolicies(r.id))
    val databases = store
      .snapshot()
      .tenantDbs
      .filter(td => td.kind == TenantDbKind.DuckLake && td.branchOf.isEmpty)
      .map(td =>
        Database(
          td.tenantId,
          td.name,
          TenantDb.catalogAlias(sup.effectiveMetastoreFor(td.tenantId, td.name), td.name)
        )
      )
    lines(
      roles,
      policies,
      databases,
      (d, schema, table) =>
        Try(readerOf(d.tenantId, d.name).columnNames(schema, table)).getOrElse(Nil)
    )

  /** Stored expressions are checked even when a wildcard or an absent table prevents column checks.
    */
  def invalidLines(roles: List[RbacRole], policies: List[RoleRowPolicy]): List[String] =
    val byId = roles.map(r => r.id -> r).toMap
    policies
      .flatMap { p =>
        RowPredicateValidator.validate(p.predicateSql) match
          case _: RowPredicateValidator.Invalid =>
            byId
              .get(p.roleId)
              .map(r =>
                s"WARN: tenant '${r.tenantId}' role '${r.name}': row policy ${p.id} on " +
                  s"${p.catalogName}.${p.schemaName}.${p.tableName} is not supported by native RLS; " +
                  "matching table reads will be denied. Update or delete this policy."
              )
          case _ => None
      }
      .map(_.map(c => if c.isControl then '?' else c))
      .sorted

  /** Prints [[linesOf]] on stderr. */
  def report(
      store: ControlPlaneStore,
      sup: PoolSupervisor,
      readerOf: (String, String) => DuckLakeCatalogReader
  ): Unit =
    val roles    = store.listTenants().flatMap(t => store.listRoles(t.id))
    val policies = roles.flatMap(r => store.listRowPolicies(r.id))
    (linesOf(store, sup, readerOf) ++ invalidLines(roles, policies)).distinct.sorted
      .foreach(System.err.println)
