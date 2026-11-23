package ai.starlake.quack.mcp

import ai.starlake.quack.model.TenantDb
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  BranchHandlers,
  CatalogHistoryResponse,
  CatalogSnapshotEntry,
  CatalogTableDetailResponse,
  CatalogTableEntry,
  CatalogTagEntry,
  ExecCaller,
  TenantDbGate
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader.MemberSnapshot
import ai.starlake.quack.ondemand.rbac.{Attenuation, EffectiveSet, HandshakeDenial}
import io.circe.Json

import java.util.Locale

/** How an MCP principal reaches one database's catalog reads (issue #297). The catalog handlers
  * gate on an admin grant (`TenantDbGate`), so the data tier's discovery tools refused every plain
  * member, who could still query through `run_sql`. An admin of the tenant, or a superuser, keeps
  * the handlers' own gate. Anyone else is admitted the way a member branches
  * (`BranchHandlers.admittedAsMember`): their own tenant only (`McpToolArgs.tenantOf` already pins
  * it), a pool of the database the token's axes allow and the handshake admits, and then only the
  * tables their grants cover for reading, the rule of `SHOW TABLES` under filtered metadata. The
  * catalog readers query the DuckLake catalog directly, so the filter is applied here, on every
  * name a reply carries, along with storage paths a member has no use for. So are row and file
  * counts for a member holding a row policy: the catalog's counts are the table's, not the rows the
  * policy lets them read, so they would tell how many rows it hides, and a table's file count grows
  * with it (the branch endpoints give such a viewer no counts either).
  */
private[mcp] final class McpCatalogReach(sup: PoolSupervisor, scopeOf: SessionScope.Resolver):

  import McpCatalogReach.*

  private def tenantId(tenant: String): String =
    TenantDbGate.resolveTenantId(sup, tenant).getOrElse(tenant)

  /** Whether the caller reads `tenant` through the handlers' own admin gate. */
  def asAdmin(apiKey: Option[String], tenant: String): Boolean =
    BranchHandlers.adminOf(scopeOf(apiKey), tenantId(tenant))

  /** How `caller` reaches `database` of `tenant`, or why they cannot. Blocks: a handshake decision
    * can be a round trip to the tenant's OPA.
    */
  def reach(
      apiKey: Option[String],
      caller: ExecCaller,
      tenant: String,
      database: String
  ): Either[String, Reach] =
    if asAdmin(apiKey, tenant) then Right(Reach(scopeOf, None, countsRows = true))
    else
      val tid = tenantId(tenant)
      // The one spelling pool keys and `findTenantDb` hold.
      val db = database.toLowerCase(Locale.ROOT)
      connectable(caller, tid, db).map { eff =>
        val alias = TenantDb.catalogAlias(sup.effectiveMetastoreFor(tid, db), db)
        Reach(
          decided(tid),
          Some(BranchHandlers.seenBy(eff, alias)),
          // Fail closed: any row policy withholds counts on every table, rather than matching
          // each policy's table (wildcards, name folding) here a second time.
          countsRows = eff.rowPolicies.isEmpty
        )
      }

  /** A member's `list_databases`: the databases of their tenant with a pool they may connect to,
    * each with those pools, in the token's axes. Decided lazily and in order, so the first decision
    * that cannot be made answers for the whole listing: an outage never reads as a shorter list.
    */
  def memberDatabases(caller: ExecCaller, tenant: String): Either[String, Json] =
    val tid     = tenantId(tenant)
    val decided = sup
      .list()
      .map(_.key)
      .filter(k =>
        k.tenant == tid && caller.restriction.allowsDatabase(k.tenantDb) &&
          caller.restriction.allowsPool(k.pool)
      )
      .sortBy(k => (k.tenantDb, k.pool))
      .to(LazyList)
      .map(k => k -> sup.authorizeHandshakeDetailed(caller.authzRequest(k)))
    decided.collectFirst { case (_, Left(HandshakeDenial.Unavailable(msg))) => msg } match
      case Some(msg) => Left(unavailable(msg))
      case None      =>
        val pools = decided.toList.collect { case (k, Right(_)) => k }.groupBy(_.tenantDb)
        val dbs   = pools.toList.sortBy(_._1).map { (db, keys) =>
          Json.obj(
            "name"  -> Json.fromString(db),
            "kind"  -> Json.fromString(sup.findTenantDb(tid, db).fold("")(_.kind.wireValue)),
            "pools" -> Json.arr(keys.map(_.pool).sorted.map(Json.fromString)*)
          )
        }
        Right(Json.obj("tenantDbs" -> Json.arr(dbs*)))

  /** The effective set the handshake admits `caller` with on a pool of `db`, narrowed by the token.
    * A database with no such pool, or none at all, reads the same.
    */
  private def connectable(
      caller: ExecCaller,
      tid: String,
      db: String
  ): Either[String, EffectiveSet] =
    sup
      .list()
      .map(_.key)
      .filter(k => k.tenant == tid && k.tenantDb == db && caller.restriction.allowsPool(k.pool))
      .sortBy(_.pool)
      .to(LazyList)
      .map(k => sup.authorizeHandshakeDetailed(caller.authzRequest(k)))
      .collectFirst {
        case Right(a) => Right(Attenuation.attenuatedBy(a.effectiveSet, caller.restriction))
        case Left(HandshakeDenial.Unavailable(msg)) => Left(unavailable(msg))
      }
      .getOrElse(Left(s"no_pool_access: you may connect to no pool of database '$db'"))

object McpCatalogReach:

  /** The row count a reply carries in place of one withheld: the catalog readers' own "unknown". */
  val Withheld: Long = -1L

  /** The file count a reply carries in place of one withheld. */
  val WithheldFiles: Int = -1

  /** What the catalog handlers are handed and what a reply may name. `sees` None: an admin, who
    * sees every table. `countsRows` false: the reply carries [[Withheld]] for every row count and
    * [[WithheldFiles]] for every file count.
    */
  final case class Reach(
      scopeOf: SessionScope.Resolver,
      sees: Option[(String, String) => Boolean],
      countsRows: Boolean
  ):
    def visible(schema: String, table: String): Boolean = sees.forall(_(schema, table))

    def isMember: Boolean = sees.isDefined

    private def entry(t: CatalogTableEntry): CatalogTableEntry =
      if countsRows then t.copy(folder = None)
      else t.copy(folder = None, rowCount = Withheld, dataFileCount = WithheldFiles)

    def tables(ts: List[CatalogTableEntry]): List[CatalogTableEntry] =
      if !isMember then ts
      else ts.filter(t => visible(t.schema, t.name)).map(entry)

    def detail(d: CatalogTableDetailResponse): CatalogTableDetailResponse =
      if !isMember then d else d.copy(table = entry(d.table), dataFiles = Nil)

    def history(h: CatalogHistoryResponse): CatalogHistoryResponse =
      if countsRows then h
      else
        h.copy(commits =
          h.commits.map(
            _.copy(
              rowsAdded = Withheld,
              rowsRemoved = Withheld,
              filesAdded = WithheldFiles,
              filesRemoved = WithheldFiles
            )
          )
        )

    /** A member's snapshots: those that touched a table they can see, naming only those tables and
      * no raw change text (it lists the whole database's activity). The counts are the whole
      * snapshot's, so they are withheld when it also touched a table the member cannot see, when
      * some entry of its changes named no table the reader resolved (fail closed: that entry may be
      * a hidden table's), and for a member who does not count rows.
      */
    def snapshots(ss: List[MemberSnapshot]): List[CatalogSnapshotEntry] =
      if !isMember then ss.map(_.entry)
      else
        ss.flatMap { case MemberSnapshot(s, accounted) =>
          val seen = s.affectedTables.filter(t => visible(t.schema, t.name))
          if seen.isEmpty then None
          else
            val whole = countsRows && accounted && seen.size == s.affectedTables.size
            val named = s.copy(changes = "", affectedTables = seen)
            Some(
              if whole then named
              else
                named.copy(
                  rowsAdded = Withheld,
                  filesAdded = WithheldFiles,
                  filesRemoved = WithheldFiles
                )
            )
        }

    /** The tags a member sees: those on a snapshot they were shown. */
    def tags(ts: List[CatalogTagEntry], keptSnapshots: Set[Long]): List[CatalogTagEntry] =
      if !isMember then ts else ts.filter(t => keptSnapshots.contains(t.snapshotId))

    /** The handlers' own answer for a table that does not exist, so a hidden one reads the same. */
    def notFound(schema: String, table: String): String =
      s"not_found: table $schema.$table not found"

  /** The scope the catalog handlers are handed for a member: this tenant, admitted. [[reach]] has
    * decided who may come this far, and it filters what the reply names.
    */
  private def decided(tid: String): SessionScope.Resolver =
    _ => SessionScope(superuser = false, manageableTenants = Set(tid))

  private def unavailable(msg: String): String =
    McpToolArgs.serverFailure(s"authz_unavailable: authorization service unavailable: $msg")
