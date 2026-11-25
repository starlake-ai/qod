package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.rls.StaleRowPolicyReport.Database
import ai.starlake.quack.model.{TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.{InProcessDuckDb, PoolSupervisor}
import ai.starlake.quack.ondemand.api.CatalogColumnEntry
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, RbacRole, RoleColumnPolicy}
import ai.starlake.sql.IdentifierFold

import java.sql.{Connection, DriverManager}
import scala.util.{Try, Using}

/** The check of column masks whose result does not have their column's type, at a write
  * ([[refusalOf]], through `PoolSupervisor.maskTypeRefusal`) and at boot ([[report]]). qod-node
  * applies a mask only when its result has exactly the type of the column it replaces (a bare
  * `NULL` is cast to it), and refuses every read of the column otherwise ("column mask cannot be
  * applied safely with its original column type"), so such a mask makes the column unreadable for
  * every principal it covers. A create or an update of such a mask is refused where the tenant's
  * catalogs show the column (a manifest import is not, so a backup always restores); at boot, one
  * line per policy and database, on stderr (the logger's default level is ERROR), naming the
  * tenant, the role, the policy, the column and both types, never the mask's text.
  *
  * Only a mask that passes the checks a new mask meets ([[TransformSqlValidator]]) is bound, and
  * only a policy naming its schema, table and column, against the tenant's DuckLake databases its
  * catalog covers (branches left out), as `StaleRowPolicyReport` does. The column's type is read
  * from the catalog, the mask is bound over a NULL of that type in a private in-memory DuckDB with
  * external access off, as the node binds it (`TRY(<mask>)`). A table a database does not hold, a
  * type the report does not know (a nested type, a type a later DuckLake adds) and a catalog that
  * cannot be read are skipped: the report reads, it never decides.
  */
object MaskTypeReport:

  /** The DuckDB type of a column DuckLake stores as `ducklakeType`, for the scalar types; None for
    * the nested ones (`struct`, `list`, `map`), `variant`, `geometry` and any other.
    */
  def duckDbType(ducklakeType: String): Option[String] =
    val t = Option(ducklakeType).map(_.trim.toLowerCase(java.util.Locale.ROOT)).getOrElse("")
    Scalar.get(t).orElse(DecimalType.unapplySeq(t).map(g => s"DECIMAL(${g(0)},${g(1)})"))

  private val DecimalType = "decimal\\((\\d{1,2}),(\\d{1,2})\\)".r

  private val Scalar: Map[String, String] = Map(
    "boolean"      -> "BOOLEAN",
    "int8"         -> "TINYINT",
    "int16"        -> "SMALLINT",
    "int32"        -> "INTEGER",
    "int64"        -> "BIGINT",
    "int128"       -> "HUGEINT",
    "uint8"        -> "UTINYINT",
    "uint16"       -> "USMALLINT",
    "uint32"       -> "UINTEGER",
    "uint64"       -> "UBIGINT",
    "uint128"      -> "UHUGEINT",
    "float32"      -> "FLOAT",
    "float64"      -> "DOUBLE",
    "date"         -> "DATE",
    "time"         -> "TIME",
    "timetz"       -> "TIMETZ",
    "timestamp"    -> "TIMESTAMP",
    "timestamp_s"  -> "TIMESTAMP_S",
    "timestamp_ms" -> "TIMESTAMP_MS",
    "timestamp_ns" -> "TIMESTAMP_NS",
    "timestamptz"  -> "TIMESTAMPTZ",
    "interval"     -> "INTERVAL",
    "varchar"      -> "VARCHAR",
    "blob"         -> "BLOB",
    "uuid"         -> "UUID",
    "json"         -> "JSON"
  )

  /** How a mask binds over a column of a type: the two types DuckDB gives, or [[Unbound]]. */
  enum Binding:
    case Types(mask: String, column: String)
    case Unbound

  /** A private in-memory DuckDB that reaches no file, network or extension. */
  def binder(): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val props = InProcessDuckDb.properties
    List(
      "enable_external_access"       -> "false",
      "autoinstall_known_extensions" -> "false",
      "autoload_known_extensions"    -> "false",
      "lock_configuration"           -> "true"
    ).foreach((k, v) => props.setProperty(k, v))
    DriverManager.getConnection("jdbc:duckdb:", props)

  private def quoteIdent(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

  /** How `mask` binds over a column `column` of the DuckDB type `columnType` (one of
    * [[duckDbType]]'s), wrapped as the node wraps it; None when the column type itself does not
    * bind here.
    */
  def bind(conn: Connection, mask: String, column: String, columnType: String): Option[Binding] =
    val source = s"(SELECT CAST(NULL AS $columnType) AS ${quoteIdent(column)})"
    def describe(item: String): Option[String] =
      Try(
        Using.resource(conn.createStatement())(st =>
          Using.resource(st.executeQuery(s"DESCRIBE SELECT $item AS v FROM $source")) { rs =>
            Option.when(rs.next())(rs.getString("column_type"))
          }
        )
      ).toOption.flatten
    describe(quoteIdent(column)).map { col =>
      describe(s"TRY($mask)").fold(Binding.Unbound)(m => Binding.Types(m, col))
    }

  private def wildcard(s: String): Boolean = s == RoleColumnPolicy.Wildcard

  private def fold(s: String): String = IdentifierFold.fold(s)

  private def quoted(s: String): String = "'" + s + "'"

  /** A mask that does not fit its column in one database: the column's DuckDB type and how the mask
    * bound over it.
    */
  final case class Mismatch(database: Database, columnType: String, binding: Binding)

  private def fits(b: Binding): Boolean = b match
    case Binding.Types(m, c) => m == c || m.replace("\"", "") == "NULL"
    case Binding.Unbound     => false

  /** Where `p`, a policy of a role of `tenantId`, holds a mask its column's type refuses: one entry
    * per database of the tenant its catalog covers whose table has the column with a type the
    * report knows. Empty for a deny, a wildcard schema, table or column, a mask the validator
    * refuses, and wherever the column cannot be found or read.
    */
  def mismatches(
      tenantId: String,
      p: RoleColumnPolicy,
      databases: List[Database],
      columnsOf: (Database, String, String) => List[CatalogColumnEntry],
      bindOf: (String, String, String) => Option[Binding]
  ): List[Mismatch] =
    if p.action != RoleColumnPolicy.ActionMask || wildcard(p.schemaName) ||
      wildcard(p.tableName) || wildcard(p.columnName)
    then Nil
    else
      for
        mask <- p.transformSql.toList
        if TransformSqlValidator.refusedParts(mask, p.columnName).isEmpty
        d <- databases
        if d.tenantId == tenantId &&
          (wildcard(p.catalogName) || fold(p.catalogName) == fold(d.catalog))
        col <- columnsOf(d, p.schemaName, p.tableName)
          .find(c => fold(c.name) == fold(p.columnName))
          .toList
        colType <- duckDbType(col.typeName).toList
        binding <- bindOf(mask, col.name, colType).toList
        if !fits(binding)
      yield Mismatch(d, colType, binding)

  private val Consequence =
    "qod-node refuses every read of the column under it (\"column mask cannot be applied safely " +
      "with its original column type\")"

  /** What a mismatch means and how to fix it, after the place it names. */
  private def explain(m: Mismatch): String = m.binding match
    case Binding.Types(mask, column) =>
      s"its mask returns $mask, but the column is $column: $Consequence. Make the mask return " +
        s"$column (TRY_CAST(<a DOUBLE, text or constant form> AS $column)), or use NULL or a deny."
    case Binding.Unbound =>
      s"its mask cannot be bound over a column of type ${m.columnType}: $Consequence. Rewrite the " +
        s"mask to return ${m.columnType}, or use NULL or a deny."

  private def place(m: Mismatch, p: RoleColumnPolicy): String =
    s"${m.database.catalog}.${p.schemaName}.${p.tableName}.${p.columnName}"

  /** The refusal of a column policy write whose mask does not fit its column, or None: the first
    * database where it does not, by catalog name. A write the check cannot judge is not refused.
    */
  def refusal(
      tenantId: String,
      p: RoleColumnPolicy,
      databases: List[Database],
      columnsOf: (Database, String, String) => List[CatalogColumnEntry],
      bindOf: (String, String, String) => Option[Binding]
  ): Option[String] =
    mismatches(tenantId, p, databases, columnsOf, bindOf)
      .sortBy(_.database.catalog)
      .headOption
      .map(m => s"invalid transformSql: on ${place(m, p)} ${explain(m)}")

  /** The WARN lines, sorted. `columnsOf(database, schema, table)` lists the table's own columns
    * with their DuckLake types, empty when the database does not hold it or they cannot be read;
    * `bindOf(mask, column, duckDbType)` is [[bind]].
    */
  def lines(
      roles: List[RbacRole],
      policies: List[RoleColumnPolicy],
      databases: List[Database],
      columnsOf: (Database, String, String) => List[CatalogColumnEntry],
      bindOf: (String, String, String) => Option[Binding]
  ): List[String] =
    val byId = roles.map(r => r.id -> r).toMap
    policies
      .flatMap { p =>
        byId.get(p.roleId).toList.flatMap { r =>
          mismatches(r.tenantId, p, databases, columnsOf, bindOf).map { m =>
            s"WARN: tenant ${quoted(r.tenantId)} role ${quoted(r.name)}: column policy ${p.id} " +
              s"on ${place(m, p)}: ${explain(m)}"
          }
        }
      }
      .map(_.map(c => if c.isControl then '?' else c))
      .distinct
      .sorted

  /** The DuckLake databases `sup` holds, branches left out, by the catalog name their statements
    * read them under. Call it after the supervisor's first `restore()`.
    */
  def databasesOf(store: ControlPlaneStore, sup: PoolSupervisor): List[Database] =
    store
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

  private def columnsVia(
      readerOf: (String, String) => DuckLakeCatalogReader
  ): (Database, String, String) => List[CatalogColumnEntry] =
    (d, schema, table) =>
      Try(readerOf(d.tenantId, d.name).topLevelColumns(schema, table)).getOrElse(Nil)

  /** [[refusal]] for a write of `p` by a role of `tenantId`, against the tenant's DuckLake
    * databases, their columns read through `readerOf`; None when the check cannot run.
    */
  def refusalOf(
      tenantId: String,
      p: RoleColumnPolicy,
      store: ControlPlaneStore,
      sup: PoolSupervisor,
      readerOf: (String, String) => DuckLakeCatalogReader
  ): Option[String] =
    if p.action != RoleColumnPolicy.ActionMask then None
    else
      Try(
        Using.resource(binder()) { conn =>
          refusal(
            tenantId,
            p,
            databasesOf(store, sup).filter(_.tenantId == tenantId),
            columnsVia(readerOf),
            (mask, column, colType) => bind(conn, mask, column, colType)
          )
        }
      ).toOption.flatten

  /** [[lines]] of every tenant's column masks, against the DuckLake databases `sup` holds, their
    * columns read through `readerOf(tenantId, database)`. Call it after the supervisor's first
    * `restore()`, as it resolves each database's catalog name from the supervisor.
    */
  def linesOf(
      store: ControlPlaneStore,
      sup: PoolSupervisor,
      readerOf: (String, String) => DuckLakeCatalogReader
  ): List[String] =
    val roles    = store.listTenants().flatMap(t => store.listRoles(t.id))
    val policies = roles.flatMap(r => store.listColumnPolicies(r.id))
    if !policies.exists(_.action == RoleColumnPolicy.ActionMask) then Nil
    else
      Using.resource(binder()) { conn =>
        lines(
          roles,
          policies,
          databasesOf(store, sup),
          columnsVia(readerOf),
          (mask, column, colType) => bind(conn, mask, column, colType)
        )
      }

  /** Prints [[linesOf]] on stderr. */
  def report(
      store: ControlPlaneStore,
      sup: PoolSupervisor,
      readerOf: (String, String) => DuckLakeCatalogReader
  ): Unit = linesOf(store, sup, readerOf).foreach(System.err.println)
