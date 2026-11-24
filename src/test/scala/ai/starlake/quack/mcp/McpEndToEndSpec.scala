// src/test/scala/ai/starlake/quack/mcp/McpEndToEndSpec.scala
package ai.starlake.quack.mcp

import ai.starlake.quack.model.{SnapshotTag, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.api.{
  CatalogColumnEntry,
  CatalogDataFileEntry,
  CatalogHistoryCommit,
  CatalogSchemaEntry,
  CatalogSnapshotEntry,
  CatalogTableDetailResponse,
  CatalogTableEntry,
  CatalogTableRef
}
import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.{
  DuckLakeCatalogReader,
  HistoryOperation,
  TableHistoryFilter,
  TableHistoryPage
}
import ai.starlake.quack.ondemand.state.testkit.{PostgresFixture, TestPostgres}
import ai.starlake.quack.ondemand.state.{
  LiquibaseRunner,
  PatStore,
  PoolPermission,
  RbacRole,
  RolePermission,
  RoleRowPolicy,
  UserGrant,
  UserStore
}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import ai.starlake.quack.security.{ManagerServerHarness, SecurityFixtures}
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/** Full-wire MCP contract: real HTTP against the harness-booted manager with a Postgres PAT store,
  * covering auth arms, tool tiering per principal, and the enabled=false unmount.
  */
class McpEndToEndSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with PostgresFixture:

  TestPostgres.dropStrayTestDatabases("qodmcpe2e")

  private val dbName = s"qodmcpe2e_test_${System.nanoTime()}"

  private var users: UserStore          = null
  private var pats: PatStore            = null
  private var patAuth: PatAuthenticator = null

  override def beforeAll(): Unit =
    if TestPostgres.reachable then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      patAuth = new PatAuthenticator(
        pats,
        users.userById,
        u => List(UserGrant(u.tenant, u.kind)),
        tenantDisabled = _ => false
      )
      users.upsertUser(None, SecurityFixtures.RootUsername, SecurityFixtures.RootPassword, "admin")
      users.upsertUser(
        Some(SecurityFixtures.TenantId),
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        "admin"
      )
      users.upsertUser(
        Some(SecurityFixtures.TenantId),
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        "user"
      )

  override def afterAll(): Unit =
    if pats != null then pats.close()
    if users != null then users.close()
    Try(TestPostgres.dropDatabase(dbName))
    ()

  private def mintPat(tenant: Option[String], username: String): String =
    val uid = users.userIdOf(tenant, username).getOrElse(fail(s"user $username missing"))
    pats.mint(uid, s"mcp-$username", TokenRestriction.Unrestricted, None, 0)._2

  private def withHarness(
      mcpEnabled: Boolean = true,
      prepare: SecurityFixtures.Fixture => Unit = _ => (),
      catalogReader: Option[(String, String) => DuckLakeCatalogReader] = None,
      audit: AuditRecorder = AuditRecorder.noop,
      auditCatalogReads: Boolean = false
  )(body: ManagerServerHarness.Harness => Unit): Unit =
    TestPostgres.ensureReachable()
    val fix = SecurityFixtures.freshStore()
    prepare(fix)
    val h = ManagerServerHarness.boot(
      fix.store,
      catalogReader = catalogReader,
      patStore = Some(pats),
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth = Some(patAuth),
      mcpEnabled = mcpEnabled,
      audit = audit,
      auditCatalogReads = auditCatalogReads
    )
    try body(h)
    finally h.shutdown()

  private def rpc(method: String, id: Int = 1, params: Json = Json.obj()): String =
    Json
      .obj(
        "jsonrpc" -> Json.fromString("2.0"),
        "id"      -> Json.fromInt(id),
        "method"  -> Json.fromString(method),
        "params"  -> params
      )
      .noSpaces

  private def postMcp(
      h: ManagerServerHarness.Harness,
      body: String,
      bearer: Option[String]
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(s"${h.baseUrl}/mcp"))
      .header("Content-Type", "application/json")
      .timeout(java.time.Duration.ofSeconds(10))
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    bearer.foreach(t => b.header("Authorization", s"Bearer $t"))
    h.httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString())

  private def toolNames(body: String): List[String] =
    parse(body).toOption
      .flatMap(_.hcursor.downField("result").downField("tools").as[List[Json]].toOption)
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("name").toOption)

  // ------------------------------------------------------------------

  "POST /mcp" should "answer initialize for an admin PAT" in withHarness() { h =>
    val resp = postMcp(h, rpc("initialize"), Some(mintPat(None, SecurityFixtures.RootUsername)))
    withClue(s"body: ${resp.body()}") {
      resp.statusCode() shouldBe 200
      parse(resp.body()).toOption.get.hcursor
        .downField("result")
        .downField("serverInfo")
        .get[String]("name")
        .toOption shouldBe Some("quack-on-demand")
    }
  }

  it should "tier tools/list by principal" in withHarness() { h =>
    val adminTools =
      toolNames(
        postMcp(
          h,
          rpc("tools/list"),
          Some(mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername))
        ).body()
      )
    adminTools should contain allOf (
      "run_sql",
      "list_databases",
      "scale_pool",
      "kill_statement",
      "create_tenant",
      "create_user",
      "add_membership",
      "grant_role_permission",
      "create_column_policy",
      "grant_pool_permission",
      "create_pool",
      "create_database",
      "upsert_maintenance_policy",
      "delete_tag",
      "restore_snapshot",
      "upsert_federated_source",
      "manifest_export",
      "create_pat",
      "get_config",
      "statement_history"
    )

    val userTools =
      toolNames(
        postMcp(
          h,
          rpc("tools/list"),
          Some(mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername))
        ).body()
      )
    userTools should contain("run_sql")
    userTools should not contain "scale_pool"
  }

  it should "answer list_databases for a tenant-admin PAT with the seeded tenant-db" in
    withHarness() { h =>
      val alice  = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
      val params = Json.obj(
        "name"      -> Json.fromString("list_databases"),
        "arguments" -> Json.obj()
      )
      val resp = postMcp(h, rpc("tools/call", params = params), Some(alice))
      withClue(s"body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val result = parse(resp.body()).toOption.get.hcursor.downField("result")
        result.get[Boolean]("isError").toOption shouldBe Some(false)
        val text = result.downField("content").downN(0).get[String]("text").toOption.getOrElse("")
        text should include(SecurityFixtures.TenantDbName)
      }
    }

  it should "refuse scale_pool for a role=user PAT with -32602" in withHarness() { h =>
    val bob    = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
    val params = Json.obj(
      "name"      -> Json.fromString("scale_pool"),
      "arguments" -> Json.obj(
        "database" -> Json.fromString(SecurityFixtures.TenantDbName),
        "pool"     -> Json.fromString(SecurityFixtures.PoolName),
        "dual"     -> Json.fromInt(2)
      )
    )
    val resp = postMcp(h, rpc("tools/call", params = params), Some(bob))
    withClue(s"body: ${resp.body()}") {
      parse(resp.body()).toOption.get.hcursor
        .downField("error")
        .get[Int]("code")
        .toOption shouldBe Some(-32602)
    }
  }

  it should "401 a session JWT" in withHarness() { h =>
    val session = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
    postMcp(h, rpc("ping"), Some(session)).statusCode() shouldBe 401
  }

  it should "401 any non-PAT bearer, and a missing one" in
    withHarness() { h =>
      postMcp(h, rpc("ping"), Some("some-random-value")).statusCode() shouldBe 401
      postMcp(h, rpc("ping"), None).statusCode() shouldBe 401
    }

  it should "405 a GET" in withHarness() { h =>
    val req = HttpRequest
      .newBuilder(URI.create(s"${h.baseUrl}/mcp"))
      .timeout(java.time.Duration.ofSeconds(10))
      .GET()
      .build()
    h.httpClient.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 405
  }

  it should "404 when mcp is disabled" in withHarness(mcpEnabled = false) { h =>
    val resp =
      postMcp(h, rpc("ping"), Some(mintPat(None, SecurityFixtures.RootUsername)))
    resp.statusCode() shouldBe 404
  }

  it should "let a pooling client go on after an unresolved PAT's unread body" in
    withHarness() { h =>
      // Ember closes a connection whose body stays unread, so the 401 must say so, or the client
      // reuses it and fails: 1 call in 5 did, with a body this size.
      val body = rpc("ping") + " " * 65536
      (1 to 20).foreach(_ => postMcp(h, body, Some("qod_pat_unknown")).statusCode() shouldBe 401)
    }

  // ------------------------------------------------------------------
  // Discovery tools for a plain member (issue #297): through the real guard and scope resolver
  // ------------------------------------------------------------------

  private def snapshotEntry(id: Long, changes: String, tables: (String, String)*) =
    CatalogSnapshotEntry(
      snapshotId = id,
      committedAt = "2026-11-23T00:00:00Z",
      schemaVersion = 1L,
      changes = changes,
      rowsAdded = id * 10L + 5L,
      filesAdded = 2,
      filesRemoved = 1,
      affectedTables = tables.toList.map(CatalogTableRef(_, _)),
      author = Some("tenant:acme/user:carol"),
      commitMessage = Some(s"commit $id")
    )

  /** The catalog's snapshots, newest first. 3: `sales.orders` and `sales.secret` together, 2:
    * `sales.secret` alone, 1: `sales.orders` alone, 0: a table the catalog no longer resolves.
    */
  private val catalogSnapshots = List(
    snapshotEntry(
      3L,
      "inserted_into_table:1,inserted_into_table:2",
      "sales" -> "orders",
      "sales" -> "secret"
    ),
    snapshotEntry(2L, "inserted_into_table:2", "sales" -> "secret"),
    snapshotEntry(1L, "inserted_into_table:1", "sales" -> "orders"),
    snapshotEntry(0L, "inserted_into_table:9")
  )

  /** The `limit` the last `listSnapshots` read was given (the window the tool asked for). */
  private val lastSnapshotWindow = new AtomicInteger(-1)

  /** A catalog of two schemas: `sales.orders` (bob's grant below), `sales.secret`, `hr.salaries`,
    * whose history is `snaps` (newest first); the ids in `unaccounted` have a change no table was
    * attributed to. It reads a member's snapshots by window or by ids, like the real reader. Under
    * a policy, a table whose name history is not stable (`stableName` false) is left out of a
    * snapshot, which then counts as one with a change no visible table is attributed to.
    */
  private def memberCatalogOf(
      snaps: List[CatalogSnapshotEntry] = catalogSnapshots,
      unaccounted: Set[Long] = Set.empty,
      stableName: ((String, String)) => Boolean = _ => true
  ): (String, String) => DuckLakeCatalogReader = (_, _) =>
    new DuckLakeCatalogReader(null):
      private def asMember(s: CatalogSnapshotEntry) =
        DuckLakeCatalogReader.MemberSnapshot(s, accounted = !unaccounted(s.snapshotId))
      private def asMember(s: CatalogSnapshotEntry, underPolicy: Boolean) =
        if !underPolicy then DuckLakeCatalogReader.MemberSnapshot(s, !unaccounted(s.snapshotId))
        else
          val kept = s.affectedTables.filter(t => nameHistoryStable(t.schema, t.name))
          DuckLakeCatalogReader.MemberSnapshot(
            s.copy(affectedTables = kept),
            accounted = !unaccounted(s.snapshotId) && kept.size == s.affectedTables.size
          )
      override def nameHistoryStable(schema: String, name: String): Boolean =
        stableName((schema, name))
      override def listSnapshots(
          limit: Int,
          before: Option[Long],
          table: Option[(String, String)]
      ): List[CatalogSnapshotEntry] =
        lastSnapshotWindow.set(limit)
        snaps.take(limit)
      override def listSnapshotsForMember(limit: Int): List[DuckLakeCatalogReader.MemberSnapshot] =
        lastSnapshotWindow.set(limit)
        snaps.take(limit).map(asMember)
      override def listSnapshotsForMember(
          limit: Int,
          underPolicy: Boolean
      ): List[DuckLakeCatalogReader.MemberSnapshot] =
        lastSnapshotWindow.set(limit)
        snaps.take(limit).map(asMember(_, underPolicy))
      override def listSnapshotsByIdsForMember(
          ids: Set[Long]
      ): List[DuckLakeCatalogReader.MemberSnapshot] =
        snaps.filter(s => ids(s.snapshotId)).map(asMember)
      override def listSnapshotsByIdsForMember(
          ids: Set[Long],
          underPolicy: Boolean
      ): List[DuckLakeCatalogReader.MemberSnapshot] =
        snaps.filter(s => ids(s.snapshotId)).map(asMember(_, underPolicy))
      private def entry(schema: String, table: String) =
        CatalogTableEntry(schema, table, 10L, 1, Some(s"s3://lake/$schema/$table/"))
      private val tables = Map(
        "sales" -> List(entry("sales", "orders"), entry("sales", "secret")),
        "hr"    -> List(entry("hr", "salaries"))
      )
      override def listSchemas(): List[CatalogSchemaEntry] =
        tables.toList.sortBy(_._1).map((s, ts) => CatalogSchemaEntry(s, ts.size))
      override def listTables(schema: String): List[CatalogTableEntry] =
        tables.getOrElse(schema, Nil)
      override def getTable(schema: String, table: String, asOf: Option[Long] = None) =
        tables
          .getOrElse(schema, Nil)
          .find(_.name == table)
          .map(t =>
            CatalogTableDetailResponse(
              t,
              List(CatalogColumnEntry(0, "id", "INTEGER", false, false)),
              List(CatalogDataFileEntry(s"s3://lake/$schema/$table/f.parquet", 1L, 10L, 1L))
            )
          )
      override def maxSnapshotId(): Option[Long] = Some(3L)
      override def listTableHistory(
          schema: String,
          table: String,
          filter: TableHistoryFilter,
          limit: Int,
          before: Option[Long]
      ): Option[TableHistoryPage] =
        getTable(schema, table).map(_ =>
          TableHistoryPage(
            7L,
            List(
              CatalogHistoryCommit(
                snapshotId = 3L,
                committedAt = "2026-11-23T00:00:00Z",
                operation = HistoryOperation.Insert,
                author = None,
                commitMessage = None,
                schemaChanged = false,
                schemaVersion = 1L,
                rowsAdded = 10L,
                rowsRemoved = 2L,
                filesAdded = 1,
                filesRemoved = 0
              )
            ),
            hasMore = false
          )
        )

  private val memberCatalog: (String, String) => DuckLakeCatalogReader = memberCatalogOf()

  /** bob, a plain member, may connect to `sales` and reads `sales.orders` through a role. */
  private def grantBobOrders(fix: SecurityFixtures.Fixture): Unit =
    fix.store.insertPoolPermission(
      PoolPermission(
        id = "pp-bob-sales",
        tenantId = SecurityFixtures.TenantId,
        poolId = Some(SecurityFixtures.PoolId),
        userId = Some(fix.bobUserId)
      )
    )
    fix.store.upsertRole(RbacRole("r-orders", SecurityFixtures.TenantId, "orders_reader"))
    fix.store.insertRolePermission(
      RolePermission("rp-orders", "r-orders", "*", "sales", "orders", "RO")
    )
    fix.store.addUserRole(fix.bobUserId, "r-orders")

  /** `tools/call name(args)` as `bearer`: `(isError, text)`. */
  private def callTool(
      h: ManagerServerHarness.Harness,
      bearer: String,
      name: String,
      args: (String, Json)*
  ): (Boolean, String) =
    val params  = Json.obj("name" -> Json.fromString(name), "arguments" -> Json.obj(args*))
    val resp    = postMcp(h, rpc("tools/call", params = params), Some(bearer))
    val result  = parse(resp.body()).toOption.get.hcursor.downField("result")
    val isError = result.get[Boolean]("isError").toOption
    withClue(s"$name -> ${resp.body()}")(isError should not be empty)
    (isError.get, result.downField("content").downN(0).get[String]("text").toOption.getOrElse(""))

  private val acmeMain = "database" -> Json.fromString(SecurityFixtures.TenantDbName)

  private def tableArgs(schema: String, table: String): Seq[(String, Json)] =
    Seq(acmeMain, "schema" -> Json.fromString(schema), "table" -> Json.fromString(table))

  /** bob's grant, plus a row policy on `sales.orders` through the same role. */
  private def grantBobOrdersFiltered(fix: SecurityFixtures.Fixture): Unit =
    grantBobOrders(fix)
    fix.store.insertRowPolicy(
      RoleRowPolicy("rrp-orders", "r-orders", "*", "sales", "orders", "id > 5")
    )

  /** The numbers named `field` anywhere in `json`. */
  private def numbersOf(json: String, field: String): List[Long] =
    s"\"$field\"\\s*:\\s*(-?[0-9]+)".r.findAllMatchIn(json).map(_.group(1).toLong).toList

  "mcp discovery tools" should "give a member holding no row policy the catalog's row counts" in
    withHarness(prepare = grantBobOrders, catalogReader = Some(memberCatalog)) { h =>
      val bob          = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (_, tables)  = callTool(h, bob, "list_tables", acmeMain)
      val (_, detail)  = callTool(h, bob, "describe_table", tableArgs("sales", "orders")*)
      val (_, history) = callTool(h, bob, "table_history", tableArgs("sales", "orders")*)
      withClue(s"$tables / $detail / $history") {
        numbersOf(tables, "rowCount") shouldBe List(10L)
        numbersOf(detail, "rowCount") shouldBe List(10L)
        numbersOf(history, "rowsAdded") shouldBe List(10L)
        numbersOf(history, "rowsRemoved") shouldBe List(2L)
        numbersOf(tables, "dataFileCount") shouldBe List(1L)
        numbersOf(detail, "dataFileCount") shouldBe List(1L)
        numbersOf(history, "filesAdded") shouldBe List(1L)
        numbersOf(history, "filesRemoved") shouldBe List(0L)
      }
    }

  it should "withhold every row and file count from a member holding a row policy" in
    withHarness(prepare = grantBobOrdersFiltered, catalogReader = Some(memberCatalog)) { h =>
      // The catalog's counts are the table's, not the rows the policy lets bob read: shown, they
      // would tell him how many rows it hides.
      val bob             = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (tErr, tables)  = callTool(h, bob, "list_tables", acmeMain)
      val (dErr, detail)  = callTool(h, bob, "describe_table", tableArgs("sales", "orders")*)
      val (hErr, history) = callTool(h, bob, "table_history", tableArgs("sales", "orders")*)
      withClue(s"$tables / $detail / $history") {
        List(tErr, dErr, hErr) shouldBe List(false, false, false)
        tables should include("orders")
        numbersOf(tables, "rowCount") shouldBe List(-1L)
        numbersOf(detail, "rowCount") shouldBe List(-1L)
        numbersOf(history, "rowsAdded") shouldBe List(-1L)
        numbersOf(history, "rowsRemoved") shouldBe List(-1L)
        // File counts grow with the table too, so they go with the row counts.
        numbersOf(tables, "dataFileCount") shouldBe List(-1L)
        numbersOf(detail, "dataFileCount") shouldBe List(-1L)
        numbersOf(history, "filesAdded") shouldBe List(-1L)
        numbersOf(history, "filesRemoved") shouldBe List(-1L)
      }
    }

  it should "answer a plain member within their grants" in
    withHarness(prepare = grantBobOrders, catalogReader = Some(memberCatalog)) { h =>
      val bob = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)

      val (dbErr, dbs) = callTool(h, bob, "list_databases")
      withClue(dbs) {
        dbErr shouldBe false
        dbs should include(SecurityFixtures.TenantDbName)
        dbs should include(SecurityFixtures.PoolName)
      }

      val (tErr, tables) = callTool(h, bob, "list_tables", acmeMain)
      withClue(tables) {
        tErr shouldBe false
        tables should include("orders")
        tables should not include "secret"
        tables should not include "salaries"
        tables should not include "\"hr\""
        tables should not include "s3://"
      }

      val (dErr, detail) = callTool(h, bob, "describe_table", tableArgs("sales", "orders")*)
      withClue(detail) {
        dErr shouldBe false
        detail should include("\"id\"")
        detail should not include "s3://"
      }

      val (hErr, history) = callTool(h, bob, "table_history", tableArgs("sales", "orders")*)
      withClue(history)(hErr shouldBe false)
    }

  it should "answer a table outside the member's grants as one that does not exist" in
    withHarness(prepare = grantBobOrders, catalogReader = Some(memberCatalog)) { h =>
      val bob = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      List("describe_table", "table_history").foreach { tool =>
        val (hiddenErr, hidden)   = callTool(h, bob, tool, tableArgs("sales", "secret")*)
        val (missingErr, missing) = callTool(h, bob, tool, tableArgs("sales", "nosuch")*)
        withClue(s"$tool: $hidden / $missing") {
          hiddenErr shouldBe true
          missingErr shouldBe true
          hidden shouldBe missing.replace("nosuch", "secret")
        }
      }
    }

  it should "list nothing to a member who may connect to no pool" in
    withHarness(catalogReader = Some(memberCatalog)) { h =>
      val bob            = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (dbErr, dbs)   = callTool(h, bob, "list_databases")
      val (tErr, tables) = callTool(h, bob, "list_tables", acmeMain)
      withClue(s"$dbs / $tables") {
        dbErr shouldBe false
        dbs should not include SecurityFixtures.TenantDbName
        tErr shouldBe true
        tables should include("no_pool_access")
      }
    }

  // ------------------------------------------------------------------
  // list_snapshots for a plain member (the follow-up #297 left after #302)
  // ------------------------------------------------------------------

  /** Snapshot tags are served for a DuckLake tenant-db only; the fixture's is in-memory. Tags sit
    * on snapshot 1 (orders), 2 (secret alone) and 0 (no resolved table).
    */
  private def duckLakeWithTags(fix: SecurityFixtures.Fixture): Unit =
    duckLakeTagged("visible-tag" -> 1L, "hidden-tag" -> 2L, "unresolved-tag" -> 0L)(fix)

  /** The tenant-db as a DuckLake one, with the tags `tags` (name -> snapshot id). */
  private def duckLakeTagged(tags: (String, Long)*)(fix: SecurityFixtures.Fixture): Unit =
    fix.store.upsertTenantDb(
      TenantDb(
        id = SecurityFixtures.TenantDbId,
        tenantId = SecurityFixtures.TenantId,
        name = SecurityFixtures.TenantDbName,
        kind = TenantDbKind.DuckLake,
        metastore = Map.empty,
        dataPath = ""
      )
    )
    tags.foreach { (name, snapshotId) =>
      fix.store.createSnapshotTag(
        SnapshotTag(
          id = s"tag-$name",
          tenant = SecurityFixtures.TenantId,
          tenantDb = SecurityFixtures.TenantDbName,
          name = name,
          snapshotId = snapshotId
        )
      )
    }

  private def snapshotsForBob(fix: SecurityFixtures.Fixture): Unit =
    duckLakeWithTags(fix)
    grantBobOrders(fix)

  private def snapshotsForFilteredBob(fix: SecurityFixtures.Fixture): Unit =
    duckLakeWithTags(fix)
    grantBobOrdersFiltered(fix)

  /** A `list_snapshots` reply's snapshots by id, and its tag names. */
  private def parsedSnapshots(text: String): (Map[Long, Json], List[String]) =
    val json  = parse(text).toOption.getOrElse(fail(s"not JSON: $text"))
    val snaps = json.hcursor.downField("snapshots").as[List[Json]].toOption.get
    val tags  = json.hcursor.downField("tags").as[List[Json]].toOption.get
    (
      snaps.map(s => s.hcursor.get[Long]("snapshotId").toOption.get -> s).toMap,
      tags.flatMap(_.hcursor.get[String]("name").toOption)
    )

  private def countsOf(snapshot: Json): List[Long] =
    List("rowsAdded", "filesAdded", "filesRemoved").map(f =>
      snapshot.hcursor.get[Long](f).toOption.getOrElse(fail(s"no $f in $snapshot"))
    )

  private def tablesOf(snapshot: Json): List[(String, String)] =
    snapshot.hcursor
      .downField("affectedTables")
      .as[List[Json]]
      .toOption
      .get
      .map(t =>
        t.hcursor.get[String]("schema").toOption.get -> t.hcursor.get[String]("name").toOption.get
      )

  private def stringOf(snapshot: Json, field: String): Option[String] =
    snapshot.hcursor.get[String](field).toOption

  /** Every field of `snapshot` as the catalog stored it. */
  private def shouldBeAsStored(snapshot: Json, stored: CatalogSnapshotEntry): Unit =
    snapshot.hcursor.get[Long]("snapshotId").toOption shouldBe Some(stored.snapshotId)
    stringOf(snapshot, "committedAt") shouldBe Some(stored.committedAt)
    snapshot.hcursor.get[Long]("schemaVersion").toOption shouldBe Some(stored.schemaVersion)
    stringOf(snapshot, "changes") shouldBe Some(stored.changes)
    countsOf(snapshot) shouldBe
      List(stored.rowsAdded, stored.filesAdded.toLong, stored.filesRemoved.toLong)
    tablesOf(snapshot) shouldBe stored.affectedTables.map(t => t.schema -> t.name)
    stringOf(snapshot, "author") shouldBe stored.author
    stringOf(snapshot, "commitMessage") shouldBe stored.commitMessage

  private def stored(id: Long): CatalogSnapshotEntry =
    catalogSnapshots.find(_.snapshotId == id).get

  private val withheldCounts = List(-1L, -1L, -1L)

  "list_snapshots" should "answer a plain member instead of tenant_forbidden" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob         = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (err, text) = callTool(h, bob, "list_snapshots", acmeMain)
      withClue(text) {
        err shouldBe false
        text should not include "tenant_forbidden"
        parsedSnapshots(text)._1.keySet should contain(1L)
      }
    }

  it should "leave out a snapshot that touched only tables the member cannot read" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob       = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (_, text) = callTool(h, bob, "list_snapshots", acmeMain)
      withClue(text) {
        val ids = parsedSnapshots(text)._1.keySet
        ids should contain allOf (1L, 3L)
        ids should not contain 2L
      }
    }

  it should "leave out from a member a snapshot with no resolved table, and show it to an admin" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob             = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val alice           = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
      val (_, memberText) = callTool(h, bob, "list_snapshots", acmeMain)
      val (_, adminText)  = callTool(h, alice, "list_snapshots", acmeMain)
      withClue(s"$memberText / $adminText") {
        parsedSnapshots(memberText)._1.keySet shouldBe Set(1L, 3L)
        parsedSnapshots(memberText)._2 should not contain "unresolved-tag"
        val (adminSnaps, adminTags) = parsedSnapshots(adminText)
        adminSnaps.keySet should contain(0L)
        shouldBeAsStored(adminSnaps(0L), stored(0L))
        adminTags should contain("unresolved-tag")
      }
    }

  it should "name only the visible tables of a mixed snapshot and withhold what it cannot split" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob        = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (_, text)  = callTool(h, bob, "list_snapshots", acmeMain)
      val (snaps, _) = parsedSnapshots(text)
      withClue(text) {
        val mixed = snaps(3L)
        tablesOf(mixed) shouldBe List("sales" -> "orders")
        stringOf(mixed, "changes") shouldBe Some("")
        countsOf(mixed) shouldBe withheldCounts
        text should not include "secret"
      }
    }

  it should "keep the counts of a snapshot of visible tables only, without its raw change text" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob       = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (_, text) = callTool(h, bob, "list_snapshots", acmeMain)
      withClue(text) {
        val plain = parsedSnapshots(text)._1(1L)
        countsOf(plain) shouldBe List(15L, 2L, 1L)
        stringOf(plain, "changes") shouldBe Some("")
        tablesOf(plain) shouldBe List("sales" -> "orders")
      }
    }

  it should "show a member each kept snapshot's author, and the commit message of a snapshot of visible tables only" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob        = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (_, text)  = callTool(h, bob, "list_snapshots", acmeMain)
      val (snaps, _) = parsedSnapshots(text)
      withClue(text) {
        snaps.keySet shouldBe Set(1L, 3L)
        snaps.foreach((id, s) => stringOf(s, "author") shouldBe stored(id).author)
        stringOf(snaps(1L), "commitMessage") shouldBe stored(1L).commitMessage
        // Snapshot 3 also touched sales.secret: its message is the whole snapshot's.
        stringOf(snaps(3L), "commitMessage") shouldBe None
      }
    }

  it should "withhold every count from a member holding a row policy" in
    withHarness(prepare = snapshotsForFilteredBob, catalogReader = Some(memberCatalog)) { h =>
      val bob         = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (err, text) = callTool(h, bob, "list_snapshots", acmeMain)
      val (snaps, _)  = parsedSnapshots(text)
      withClue(text) {
        err shouldBe false
        snaps.keySet shouldBe Set(1L, 3L)
        snaps.values.foreach(s => countsOf(s) shouldBe withheldCounts)
      }
    }

  it should "keep only the tags of a snapshot the member sees" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob       = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (_, text) = callTool(h, bob, "list_snapshots", acmeMain)
      withClue(text) {
        parsedSnapshots(text)._2 shouldBe List("visible-tag")
        text should not include "hidden-tag"
      }
    }

  it should "apply limit to the snapshots a member keeps" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      // The two newest are 3 and 2: limit 2 on the raw list would leave only 3.
      val (_, two) = callTool(h, bob, "list_snapshots", acmeMain, "limit" -> Json.fromInt(2))
      val (_, one) = callTool(h, bob, "list_snapshots", acmeMain, "limit" -> Json.fromInt(1))
      withClue(s"$two / $one") {
        parsedSnapshots(two)._1.keySet shouldBe Set(1L, 3L)
        parsedSnapshots(one)._1.keySet shouldBe Set(3L)
        // A member's tags do not follow the page: the tag on snapshot 1, visible to them, stays
        // when limit leaves 1 off the page.
        parsedSnapshots(two)._2 shouldBe List("visible-tag")
        parsedSnapshots(one)._2 shouldBe List("visible-tag")
      }
    }

  it should "read a limit below 1 as 1 for a member, as the handler does for an admin" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      List(0, -5).foreach { limit =>
        lastSnapshotWindow.set(-1)
        val (err, text) =
          callTool(h, bob, "list_snapshots", acmeMain, "limit" -> Json.fromInt(limit))
        withClue(s"limit $limit: $text") {
          err shouldBe false
          parsedSnapshots(text)._1.keySet shouldBe Set(3L)
          lastSnapshotWindow.get shouldBe 50
        }
      }
    }

  it should "list a member's snapshots newest first" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob       = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (_, text) = callTool(h, bob, "list_snapshots", acmeMain)
      val ids       = parse(text).toOption.get.hcursor
        .downField("snapshots")
        .as[List[Json]]
        .toOption
        .get
        .map(_.hcursor.get[Long]("snapshotId").toOption.get)
      withClue(text)(ids shouldBe List(3L, 1L))
    }

  /** bob may connect to `sales` and reads only `hr.salaries`, which no snapshot touched. */
  private def snapshotsForBobSalaries(fix: SecurityFixtures.Fixture): Unit =
    duckLakeWithTags(fix)
    fix.store.insertPoolPermission(
      PoolPermission(
        id = "pp-bob-sales",
        tenantId = SecurityFixtures.TenantId,
        poolId = Some(SecurityFixtures.PoolId),
        userId = Some(fix.bobUserId)
      )
    )
    fix.store.upsertRole(RbacRole("r-salaries", SecurityFixtures.TenantId, "salaries_reader"))
    fix.store.insertRolePermission(
      RolePermission("rp-salaries", "r-salaries", "*", "hr", "salaries", "RO")
    )
    fix.store.addUserRole(fix.bobUserId, "r-salaries")

  it should "answer an empty list, not an error, when no snapshot touched a member's tables" in
    withHarness(prepare = snapshotsForBobSalaries, catalogReader = Some(memberCatalog)) { h =>
      val bob         = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (err, text) = callTool(h, bob, "list_snapshots", acmeMain)
      withClue(text) {
        err shouldBe false
        parsedSnapshots(text) shouldBe ((Map.empty[Long, Json], Nil))
      }
    }

  /** What the engine writes: an inlined insert and delete on `sales.orders` (the sink's small
    * batches), then one transaction that writes `sales.orders` to a data file and `sales.secret`
    * inline, then a flush (it dates `sales.secret`'s file to that transaction's snapshot), then a
    * transaction that writes `sales.orders` and creates a view, a verb no table resolves.
    */
  private val engineWrites =
    """CREATE SCHEMA lake.sales;
      |CREATE TABLE lake.sales.orders (id INTEGER, v VARCHAR);
      |CREATE TABLE lake.sales.secret (id INTEGER, v VARCHAR);
      |INSERT INTO lake.sales.orders VALUES (1, 'a'), (2, 'b');
      |DELETE FROM lake.sales.orders WHERE id = 1;
      |BEGIN;
      |INSERT INTO lake.sales.orders SELECT range, 'bulk' FROM range(1000);
      |INSERT INTO lake.sales.secret VALUES (1, 's'), (2, 't'), (3, 'u');
      |COMMIT;
      |CALL ducklake_flush_inlined_data('lake');
      |BEGIN;
      |INSERT INTO lake.sales.orders SELECT range, 'more' FROM range(1000);
      |CREATE VIEW lake.sales.secret_v AS SELECT * FROM lake.sales.secret;
      |COMMIT;
      |INSERT INTO lake.sales.orders SELECT range, 'last' FROM range(1000);
      |""".stripMargin

  it should "list a member every commit table_history shows, and withhold counts shared with a hidden table (real DuckLake)" in
    withCatalog("mcpsnaps", engineWrites) { (reader, _) =>
      val history  = reader.listTableHistory("sales", "orders").get.commits.map(_.snapshotId)
      val ordersId = reader.currentTableInfo("sales", "orders").get._1
      val stored   = reader.listSnapshots(limit = 1000).map(s => s.snapshotId -> s).toMap
      def namesOrders(entry: String): Boolean =
        entry.endsWith(s":$ordersId") || entry.endsWith("\"sales\".\"orders\"")
      def onlyOrders(id: Long): Boolean = stored(id).changes.split(",").forall(namesOrders)
      val touchingOrders                =
        stored.keySet.filter(id => stored(id).changes.split(",").exists(namesOrders))
      val withView = stored.keySet.filter(id => stored(id).changes.contains("created_view:"))
      withHarness(prepare = snapshotsForBob, catalogReader = Some((_, _) => reader)) { h =>
        val bob         = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
        val (err, text) = callTool(h, bob, "list_snapshots", acmeMain)
        val (snaps, _)  = parsedSnapshots(text)
        withClue(s"history $history, stored ${stored.values.map(_.changes)}: $text") {
          err shouldBe false
          history.size should be >= 6
          snaps.keySet should contain allElementsOf history
          // Every snapshot that wrote sales.orders, and none other (the flush of both tables too).
          snaps.keySet shouldBe touchingOrders
          // The view no table resolves keeps the whole snapshot's counts back.
          withView should have size 1
          countsOf(snaps(withView.head)) shouldBe withheldCounts
          text should not include "secret"
          snaps.foreach { (id, s) =>
            tablesOf(s) shouldBe List("sales" -> "orders")
            if !onlyOrders(id) then countsOf(s) shouldBe withheldCounts
            else
              countsOf(s) shouldBe List(
                stored(id).rowsAdded,
                stored(id).filesAdded.toLong,
                stored(id).filesRemoved.toLong
              )
          }
        }
      }
    }

  /** Maintenance on `sales.orders` alone: an inlined insert flushed on its own, then two data files
    * merged into one.
    */
  private val ordersMaintenance =
    """CREATE SCHEMA lake.sales;
      |CREATE TABLE lake.sales.orders (id INTEGER, v VARCHAR);
      |CREATE TABLE lake.sales.secret (id INTEGER, v VARCHAR);
      |INSERT INTO lake.sales.orders VALUES (1, 'a');
      |CALL ducklake_flush_inlined_data('lake');
      |INSERT INTO lake.sales.orders SELECT range, 'x' FROM range(1000);
      |INSERT INTO lake.sales.orders SELECT range, 'y' FROM range(1000);
      |CALL ducklake_merge_adjacent_files('lake', 'orders', schema => 'sales');
      |""".stripMargin

  it should "list a member the flush and maintenance snapshots of their own table (real DuckLake)" in
    withCatalog("mcpsnapsmaint", ordersMaintenance) { (reader, _) =>
      val ordersId = reader.currentTableInfo("sales", "orders").get._1
      val stored   = reader.listSnapshots(limit = 1000).map(s => s.snapshotId -> s).toMap
      def onlyVerbOnOrders(verb: String): Set[Long] =
        stored.keySet.filter(id => stored(id).changes.split(",").forall(_ == s"$verb:$ordersId"))
      val flushes = onlyVerbOnOrders("inline_flush")
      val merges  = onlyVerbOnOrders("merge_adjacent")
      withHarness(prepare = snapshotsForBob, catalogReader = Some((_, _) => reader)) { h =>
        val bob         = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
        val (err, text) = callTool(h, bob, "list_snapshots", acmeMain)
        val (snaps, _)  = parsedSnapshots(text)
        withClue(s"stored ${stored.values.map(s => s.snapshotId -> s.changes)}: $text") {
          err shouldBe false
          flushes should not be empty
          merges should not be empty
          (flushes ++ merges).foreach { id =>
            snaps.keySet should contain(id)
            tablesOf(snaps(id)) shouldBe List("sales" -> "orders")
            countsOf(snaps(id)) shouldBe List(
              stored(id).rowsAdded,
              stored(id).filesAdded.toLong,
              stored(id).filesRemoved.toLong
            )
          }
        }
      }
    }

  it should "read a member's snapshots from a window of limit * 50, newest first" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      lastSnapshotWindow.set(-1)
      val (err, text) = callTool(h, bob, "list_snapshots", acmeMain, "limit" -> Json.fromInt(2))
      withClue(text) {
        err shouldBe false
        lastSnapshotWindow.get shouldBe 100
      }
    }

  it should "answer a database the member cannot reach exactly as the #302 tools do" in {
    val bobOf = () => mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
    def sameAsListTables(h: ManagerServerHarness.Harness, database: String): Unit =
      val bob    = bobOf()
      val db     = "database" -> Json.fromString(database)
      val answer = callTool(h, bob, "list_snapshots", db)
      val oracle = callTool(h, bob, "list_tables", db)
      withClue(s"$database: list_snapshots $answer / list_tables $oracle") {
        oracle._1 shouldBe true
        answer shouldBe oracle
      }
    // A pool bob may not connect to.
    withHarness(prepare = duckLakeWithTags, catalogReader = Some(memberCatalog)) { h =>
      sameAsListTables(h, SecurityFixtures.TenantDbName)
    }
    // Another tenant's database, and one that does not exist.
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      sameAsListTables(h, SecurityFixtures.GlobexTenantDbName)
      sameAsListTables(h, "acme_nosuch")
    }
  }

  it should "offer a member the tool, and say they may get fewer than limit" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob         = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val body        = postMcp(h, rpc("tools/list"), Some(bob)).body()
      val description = parse(body).toOption
        .flatMap(_.hcursor.downField("result").downField("tools").as[List[Json]].toOption)
        .getOrElse(Nil)
        .find(_.hcursor.get[String]("name").toOption.contains("list_snapshots"))
        .flatMap(_.hcursor.get[String]("description").toOption)
      withClue(body) {
        description should not be empty
        description.get should include("fewer than")
        description.get should include("limit")
      }
    }

  it should "leave an admin's reply unchanged" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val alice         = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
      val (err, text)   = callTool(h, alice, "list_snapshots", acmeMain)
      val (snaps, tags) = parsedSnapshots(text)
      val (_, two)      = callTool(h, alice, "list_snapshots", acmeMain, "limit" -> Json.fromInt(2))
      withClue(s"$text / $two") {
        err shouldBe false
        snaps.keySet shouldBe catalogSnapshots.map(_.snapshotId).toSet
        catalogSnapshots.foreach(s => shouldBeAsStored(snaps(s.snapshotId), s))
        tags.toSet shouldBe Set("visible-tag", "hidden-tag", "unresolved-tag")
        // An admin's limit still applies to the catalog's newest snapshots.
        parsedSnapshots(two)._1.keySet shouldBe Set(3L, 2L)
        // ... but not to their tags, which name every snapshot's.
        parsedSnapshots(two)._2.toSet shouldBe Set("visible-tag", "hidden-tag", "unresolved-tag")
      }
    }

  // Second round (#311): the commit message of a snapshot the member sees only part of, and the
  // tags of any snapshot the member sees, wherever it sits in the history.

  it should "hide the commit message of a snapshot that also touched a table the member cannot read" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob        = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val alice      = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
      val (_, text)  = callTool(h, bob, "list_snapshots", acmeMain)
      val (_, admin) = callTool(h, alice, "list_snapshots", acmeMain)
      val (snaps, _) = parsedSnapshots(text)
      withClue(s"$text / $admin") {
        // Snapshot 3 also touched sales.secret: no message, but its author stays.
        stringOf(snaps(3L), "commitMessage") shouldBe None
        stringOf(snaps(3L), "author") shouldBe stored(3L).author
        stringOf(snaps(1L), "commitMessage") shouldBe Some("commit 1")
        text should not include "commit 3"
        // An admin keeps it.
        stringOf(parsedSnapshots(admin)._1(3L), "commitMessage") shouldBe Some("commit 3")
      }
    }

  it should "hide the commit message of a snapshot with a change no table is attributed to" in
    withHarness(
      prepare = snapshotsForBob,
      catalogReader = Some(memberCatalogOf(unaccounted = Set(1L)))
    ) { h =>
      val bob        = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (_, text)  = callTool(h, bob, "list_snapshots", acmeMain)
      val (snaps, _) = parsedSnapshots(text)
      withClue(text) {
        stringOf(snaps(1L), "commitMessage") shouldBe None
        stringOf(snaps(1L), "author") shouldBe stored(1L).author
        countsOf(snaps(1L)) shouldBe withheldCounts
        text should not include "commit 1"
      }
    }

  it should "keep the commit message of a member holding a row policy, whose counts are withheld" in
    withHarness(prepare = snapshotsForFilteredBob, catalogReader = Some(memberCatalog)) { h =>
      val bob        = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (_, text)  = callTool(h, bob, "list_snapshots", acmeMain)
      val (snaps, _) = parsedSnapshots(text)
      withClue(text) {
        stringOf(snaps(1L), "commitMessage") shouldBe Some("commit 1")
        countsOf(snaps(1L)) shouldBe withheldCounts
        stringOf(snaps(3L), "commitMessage") shouldBe None
      }
    }

  it should "hide every snapshot and tag of a table whose name history is not stable from a member holding a row policy" in
    withHarness(
      prepare = snapshotsForFilteredBob,
      catalogReader = Some(memberCatalogOf(stableName = _ => false))
    ) { h =>
      val bob         = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (err, text) = callTool(h, bob, "list_snapshots", acmeMain)
      withClue(text) {
        err shouldBe false
        parsedSnapshots(text) shouldBe ((Map.empty[Long, Json], Nil))
        text should not include "commit 1"
      }
    }

  /** The newest 59 snapshots touch `sales.secret` alone (ids 60 to 2), then comes the one of
    * `sales.orders` (1) and the unresolved one (0): beyond a member's window of 50 for `limit` 1.
    */
  private val longHistory =
    (60L to 2L by -1L).toList.map(id =>
      snapshotEntry(id, "inserted_into_table:2", "sales" -> "secret")
    ) ++ catalogSnapshots.filter(_.snapshotId <= 1L)

  it should "keep a member's tag on a visible snapshot older than the window, whatever limit" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalogOf(longHistory))) {
      h =>
        val bob        = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
        val alice      = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
        val (_, one)   = callTool(h, bob, "list_snapshots", acmeMain, "limit" -> Json.fromInt(1))
        val (_, admin) = callTool(h, alice, "list_snapshots", acmeMain, "limit" -> Json.fromInt(1))
        withClue(s"$one / $admin") {
          // The window of 50 holds only snapshots of sales.secret: no snapshot, yet the tag on
          // snapshot 1 (orders) stays, and the tags on 2 (secret alone) and 0 (no table) do not.
          parsedSnapshots(one)._1 shouldBe empty
          parsedSnapshots(one)._2 shouldBe List("visible-tag")
          // An admin gets every tag.
          parsedSnapshots(admin)._2.toSet shouldBe Set(
            "visible-tag",
            "hidden-tag",
            "unresolved-tag"
          )
        }
    }

  it should "keep the tag of a visible snapshot even when limit leaves it off the page" in
    withHarness(prepare = snapshotsForBob, catalogReader = Some(memberCatalog)) { h =>
      val bob      = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
      val (_, one) = callTool(h, bob, "list_snapshots", acmeMain, "limit" -> Json.fromInt(1))
      withClue(one) {
        parsedSnapshots(one)._1.keySet shouldBe Set(3L)
        parsedSnapshots(one)._2 shouldBe List("visible-tag")
      }
    }

  it should "judge a member's tags on any snapshot of the real catalog, not on the page (real DuckLake)" in
    withCatalog("mcpsnapstags", engineWrites) { (reader, _) =>
      val stored   = reader.listSnapshots(limit = 1000).map(s => s.snapshotId -> s).toMap
      val ordersId = reader.currentTableInfo("sales", "orders").get._1
      val secretId = reader.currentTableInfo("sales", "secret").get._1
      def names(id: Long, table: String, tid: Long): Boolean =
        stored(id).changes
          .split(",")
          .exists(e => e.endsWith(s":$tid") || e.endsWith(s"\"sales\".\"$table\""))
      def onlyTable(id: Long, table: String, tid: Long): Boolean =
        stored(id).changes
          .split(",")
          .forall(e => e.endsWith(s":$tid") || e.endsWith(s"\"sales\".\"$table\""))
      val ordersOldest = stored.keySet.filter(id => onlyTable(id, "orders", ordersId)).min
      val secretOnly   = stored.keySet.filter(id => onlyTable(id, "secret", secretId)).min
      val schemaOnly   =
        stored.keySet.filter(id => stored(id).changes.startsWith("created_schema")).min
      ordersOldest should be < stored.keySet.max
      names(ordersOldest, "orders", ordersId) shouldBe true
      def tagMine(fix: SecurityFixtures.Fixture): Unit =
        snapshotsForBob(fix)
        List("orders-tag" -> ordersOldest, "secret-tag" -> secretOnly, "schema-tag" -> schemaOnly)
          .foreach { (name, snapshotId) =>
            fix.store.createSnapshotTag(
              SnapshotTag(
                id = s"tag-$name",
                tenant = SecurityFixtures.TenantId,
                tenantDb = SecurityFixtures.TenantDbName,
                name = name,
                snapshotId = snapshotId
              )
            )
          }
      withHarness(prepare = tagMine, catalogReader = Some((_, _) => reader)) { h =>
        val bob           = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
        val (err, one)    = callTool(h, bob, "list_snapshots", acmeMain, "limit" -> Json.fromInt(1))
        val (snaps, tags) = parsedSnapshots(one)
        withClue(s"stored ${stored.values.map(s => s.snapshotId -> s.changes)}: $one") {
          err shouldBe false
          snaps.keySet should not contain ordersOldest
          tags should contain("orders-tag")
          tags should not contain "secret-tag"
          tags should not contain "schema-tag"
        }
      }
    }

  // Maintainer's point 5 (#311): a table renamed, and a table dropped, since the snapshots.

  /** The snapshots whose raw changes name table id `tid` (a write, a flush, a rename, a drop). */
  private def touching(stored: Map[Long, CatalogSnapshotEntry], tid: Long): Set[Long] =
    stored.keySet.filter(id => stored(id).changes.split(",").exists(_.endsWith(s":$tid")))

  it should "judge a renamed table by the name it has now, so a grant on that name shows its whole history, the rename included (real DuckLake)" in
    withCatalog(
      "mcpsnapsrename",
      """CREATE SCHEMA lake.sales;
        |CREATE TABLE lake.sales.orders_old (id INTEGER, v VARCHAR);
        |INSERT INTO lake.sales.orders_old VALUES (1, 'a');
        |INSERT INTO lake.sales.orders_old SELECT range, 'bulk' FROM range(1000);
        |""".stripMargin
    ) { (reader, _) =>
      val beforeRename = reader.maxSnapshotId().get
      runSqlOnCatalog("ALTER TABLE lake.sales.orders_old RENAME TO orders;")
      val renameId = reader.maxSnapshotId().get
      runSqlOnCatalog(
        """INSERT INTO lake.sales.orders VALUES (2, 'b');
          |INSERT INTO lake.sales.orders SELECT range, 'more' FROM range(1000);
          |""".stripMargin
      )
      val ordersId  = reader.currentTableInfo("sales", "orders").get._1
      val stored    = reader.listSnapshots(limit = 1000).map(s => s.snapshotId -> s).toMap
      val writes    = touching(stored, ordersId)
      val oldWrites = writes.filter(_ <= beforeRename)
      val newWrites = writes.filter(_ > renameId)
      // The entries that name the table by name: its creation under the old name and, in this
      // DuckLake, the rename (a `created_table` under the new name).
      val byName = stored.keySet.filter(id =>
        List("\"sales\".\"orders_old\"", "\"sales\".\"orders\"").exists(stored(id).changes.contains)
      )
      val history = writes ++ byName
      withHarness(prepare = snapshotsForBob, catalogReader = Some((_, _) => reader)) { h =>
        val bob        = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
        val alice      = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
        val (err, txt) = callTool(h, bob, "list_snapshots", acmeMain)
        val (_, admin) = callTool(h, alice, "list_snapshots", acmeMain)
        val (snaps, _) = parsedSnapshots(txt)
        withClue(s"stored ${stored.values.map(s => s.snapshotId -> s.changes)}: $txt") {
          err shouldBe false
          // The table was written under both names.
          oldWrites should not be empty
          newWrites should not be empty
          history should contain(renameId)
          parsedSnapshots(admin)._1.keySet should contain allElementsOf history
          // bob's grant names sales.orders, the table's name now: the whole history of that table
          // is his, under that name, each snapshot with its counts (it touched his table alone).
          snaps.keySet shouldBe history
          snaps.foreach { (id, s) =>
            tablesOf(s) shouldBe List("sales" -> "orders")
            countsOf(s) shouldBe
              List(
                stored(id).rowsAdded,
                stored(id).filesAdded.toLong,
                stored(id).filesRemoved.toLong
              )
          }
          // The rename snapshot is his, always: named orders, its message as stored.
          snaps.keySet should contain(renameId)
          tablesOf(snaps(renameId)) shouldBe List("sales" -> "orders")
          stringOf(snaps(renameId), "commitMessage") shouldBe stored(renameId).commitMessage
          txt should not include "orders_old"
        }
      }
    }

  it should "hide from a member every snapshot of a table dropped since, though their grant still names it, and keep them for an admin (real DuckLake)" in
    withCatalog(
      "mcpsnapsdrop",
      """CREATE SCHEMA lake.sales;
        |CREATE TABLE lake.sales.orders (id INTEGER, v VARCHAR);
        |CREATE TABLE lake.sales.secret (id INTEGER, v VARCHAR);
        |INSERT INTO lake.sales.orders VALUES (1, 'a');
        |INSERT INTO lake.sales.orders SELECT range, 'bulk' FROM range(1000);
        |INSERT INTO lake.sales.secret VALUES (1, 's');
        |""".stripMargin
    ) { (reader, _) =>
      val ordersId = reader.currentTableInfo("sales", "orders").get._1
      val lastLive = reader.maxSnapshotId().get
      runSqlOnCatalog("DROP TABLE lake.sales.orders;")
      val dropId = reader.maxSnapshotId().get
      reader.currentTableInfo("sales", "orders") shouldBe None
      val stored = reader.listSnapshots(limit = 1000).map(s => s.snapshotId -> s).toMap
      val writes = touching(stored, ordersId).filter(_ <= lastLive)
      withHarness(prepare = snapshotsForBob, catalogReader = Some((_, _) => reader)) { h =>
        val bob        = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
        val alice      = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
        val (err, txt) = callTool(h, bob, "list_snapshots", acmeMain)
        val (_, admin) = callTool(h, alice, "list_snapshots", acmeMain)
        withClue(s"stored ${stored.values.map(s => s.snapshotId -> s.changes)}: $txt") {
          err shouldBe false
          writes should not be empty
          // The node refuses a dropped table to a member at any snapshot: none of its history.
          parsedSnapshots(txt) shouldBe ((Map.empty[Long, Json], Nil))
          txt should not include "secret"
          // An admin keeps every snapshot, the writes and the drop included.
          parsedSnapshots(admin)._1.keySet shouldBe stored.keySet
          parsedSnapshots(admin)._1.keySet should contain allElementsOf (writes + dropId)
        }
      }
    }

  // Maintainer's review of #321: a member's table is the one its stable id names today, a dropped
  // table is hidden, and under a column or row policy a table whose name history is not stable is
  // hidden too (the node's time-travel rule).

  private def bobPat   = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
  private def alicePat = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)

  /** bob reads `sales.orders` (under a row policy with `rowPolicy`) of the real catalog `reader`, a
    * DuckLake tenant-db tagged with `tags` only.
    */
  private def withReader(
      reader: DuckLakeCatalogReader,
      rowPolicy: Boolean = false,
      tags: Seq[(String, Long)] = Nil,
      audit: AuditRecorder = AuditRecorder.noop,
      auditCatalogReads: Boolean = false
  )(body: ManagerServerHarness.Harness => Unit): Unit =
    def prepare(fix: SecurityFixtures.Fixture): Unit =
      duckLakeTagged(tags*)(fix)
      if rowPolicy then grantBobOrdersFiltered(fix) else grantBobOrders(fix)
    withHarness(
      prepare = prepare,
      catalogReader = Some((_, _) => reader),
      audit = audit,
      auditCatalogReads = auditCatalogReads
    )(body)

  /** The review's scenario: `orders` written, renamed `payroll`, then a new `orders` made and
    * written. `body` gets the reader, the rename snapshot and the stored snapshots.
    */
  private def renamedAway(prefix: String)(
      body: (DuckLakeCatalogReader, Long, Map[Long, CatalogSnapshotEntry]) => Unit
  ): Unit =
    withCatalog(
      prefix,
      """CREATE SCHEMA lake.sales;
        |CREATE TABLE lake.sales.orders (id INTEGER, v VARCHAR);
        |INSERT INTO lake.sales.orders VALUES (1, 'payroll a');
        |INSERT INTO lake.sales.orders SELECT range, 'payroll bulk' FROM range(1000);
        |""".stripMargin
    ) { (reader, _) =>
      runSqlOnCatalog("ALTER TABLE lake.sales.orders RENAME TO payroll;")
      val renameId = reader.maxSnapshotId().get
      runSqlOnCatalog(
        """CREATE TABLE lake.sales.orders (id INTEGER, v VARCHAR);
          |INSERT INTO lake.sales.orders VALUES (9, 'new orders');
          |""".stripMargin
      )
      body(reader, renameId, reader.listSnapshots(limit = 1000).map(s => s.snapshotId -> s).toMap)
    }

  it should "show a member the new orders and nothing of the payroll it was renamed from, the rename included (real DuckLake)" in
    renamedAway("mcpsnapcurrename") { (reader, renameId, stored) =>
      val schemaId = stored.keySet.filter(id => stored(id).changes.startsWith("created_schema")).max
      val payroll  = stored.keySet.filter(id => id > schemaId && id <= renameId)
      val newOrders = stored.keySet.filter(_ > renameId)
      withReader(reader) { h =>
        val (err, txt) = callTool(h, bobPat, "list_snapshots", acmeMain)
        val (_, admin) = callTool(h, alicePat, "list_snapshots", acmeMain)
        val (snaps, _) = parsedSnapshots(txt)
        withClue(s"stored ${stored.values.map(s => s.snapshotId -> s.changes)}: $txt") {
          err shouldBe false
          payroll.size should be >= 4
          newOrders.size shouldBe 2
          // The admin keeps every snapshot; the rename snapshot names the table it made, payroll.
          parsedSnapshots(admin)._1.keySet should contain allElementsOf payroll
          tablesOf(parsedSnapshots(admin)._1(renameId)) shouldBe List("sales" -> "payroll")
          // bob's grant names sales.orders, the table made after the rename: its snapshots are
          // his, under that name. None of payroll's, the rename's included.
          snaps.keySet shouldBe newOrders
          snaps.values.foreach(sn => tablesOf(sn) shouldBe List("sales" -> "orders"))
          txt should not include "payroll"
        }
      }
    }

  it should "hide the history of an orders whose name was another table's from a member holding a row policy on it (real DuckLake)" in
    renamedAway("mcpsnapcurpolicy") { (reader, _, stored) =>
      withReader(reader, rowPolicy = true) { h =>
        val (err, txt) = callTool(h, bobPat, "list_snapshots", acmeMain)
        withClue(s"stored ${stored.values.map(s => s.snapshotId -> s.changes)}: $txt") {
          err shouldBe false
          // The name has been another table's: as for time travel under a policy, none of it.
          parsedSnapshots(txt) shouldBe ((Map.empty[Long, Json], Nil))
          txt should not include "payroll"
        }
      }
    }

  it should "keep the history of a table whose column was renamed from a member holding no policy, and hide it under a row policy (real DuckLake)" in
    withCatalog(
      "mcpsnapcurcolumn",
      """CREATE SCHEMA lake.sales;
        |CREATE TABLE lake.sales.orders (id INTEGER, v VARCHAR);
        |INSERT INTO lake.sales.orders VALUES (1, 'a');
        |""".stripMargin
    ) { (reader, _) =>
      runSqlOnCatalog(
        """ALTER TABLE lake.sales.orders RENAME COLUMN v TO label;
          |INSERT INTO lake.sales.orders VALUES (2, 'b');
          |""".stripMargin
      )
      reader.nameHistoryStable("sales", "orders") shouldBe false
      val stored   = reader.listSnapshots(limit = 1000).map(s => s.snapshotId -> s).toMap
      val ordersId = reader.currentTableInfo("sales", "orders").get._1
      val orders   = touching(stored, ordersId) ++ stored.keySet.filter(id =>
        stored(id).changes.contains("\"sales\".\"orders\"")
      )
      withReader(reader) { h =>
        val (err, txt) = callTool(h, bobPat, "list_snapshots", acmeMain)
        val (snaps, _) = parsedSnapshots(txt)
        withClue(s"stored ${stored.values.map(s => s.snapshotId -> s.changes)}: $txt") {
          err shouldBe false
          orders.size should be >= 3
          snaps.keySet shouldBe orders
          snaps.values.foreach(sn => tablesOf(sn) shouldBe List("sales" -> "orders"))
        }
      }
      // The same member under a row policy loses it all: a column's name has changed.
      withReader(reader, rowPolicy = true) { h =>
        val (err, txt) = callTool(h, bobPat, "list_snapshots", acmeMain)
        withClue(txt) {
          err shouldBe false
          parsedSnapshots(txt) shouldBe ((Map.empty[Long, Json], Nil))
        }
      }
    }

  it should "keep a snapshot of a stable table that also touched a dropped one, its counts and message withheld (real DuckLake)" in
    withCatalog(
      "mcpsnapcurmixed",
      """CREATE SCHEMA lake.sales;
        |CREATE TABLE lake.sales.orders (id INTEGER, v VARCHAR);
        |CREATE TABLE lake.sales.gone (id INTEGER, v VARCHAR);
        |BEGIN;
        |CALL ducklake_set_commit_message('lake', 'tenant:acme/user:alice', 'orders and gone');
        |INSERT INTO lake.sales.orders SELECT range, 'both' FROM range(1000);
        |INSERT INTO lake.sales.gone SELECT range, 'both' FROM range(1000);
        |COMMIT;
        |INSERT INTO lake.sales.gone SELECT range, 'alone' FROM range(1000);
        |INSERT INTO lake.sales.orders SELECT range, 'alone' FROM range(1000);
        |""".stripMargin
    ) { (reader, _) =>
      runSqlOnCatalog("DROP TABLE lake.sales.gone;")
      val stored = reader.listSnapshots(limit = 1000).map(s => s.snapshotId -> s).toMap
      val both   = stored.keySet.filter(id => stored(id).commitMessage.contains("orders and gone"))
      withReader(reader) { h =>
        val (err, text) = callTool(h, bobPat, "list_snapshots", acmeMain)
        val (snaps, _)  = parsedSnapshots(text)
        withClue(s"stored ${stored.values.map(s => s.snapshotId -> s.changes)}: $text") {
          err shouldBe false
          both should have size 1
          // The shared snapshot shows orders alone and nothing else of it.
          snaps.keySet should contain(both.head)
          tablesOf(snaps(both.head)) shouldBe List("sales" -> "orders")
          countsOf(snaps(both.head)) shouldBe withheldCounts
          stringOf(snaps(both.head), "commitMessage") shouldBe None
          // His: orders' creation, the shared write, orders' own write. The dropped table's own
          // creation, write and drop are not.
          snaps.keySet.size shouldBe 3
          snaps.values.foreach(s => tablesOf(s) shouldBe List("sales" -> "orders"))
          text should not include "gone"
        }
      }
    }

  /** Test texts are written with `'` for `"`. */
  private def q(text: String): String = text.replace('\'', '"')

  /** bob may connect to `sales` and reads the tables `names` of it, no policy. */
  private def bobReadsSales(names: String*)(fix: SecurityFixtures.Fixture): Unit =
    duckLakeTagged()(fix)
    fix.store.insertPoolPermission(
      PoolPermission(
        id = "pp-bob-sales",
        tenantId = SecurityFixtures.TenantId,
        poolId = Some(SecurityFixtures.PoolId),
        userId = Some(fix.bobUserId)
      )
    )
    fix.store.upsertRole(RbacRole("r-named", SecurityFixtures.TenantId, "named_reader"))
    names.zipWithIndex.foreach { (name, i) =>
      fix.store.insertRolePermission(
        RolePermission(s"rp-named-$i", "r-named", "*", "sales", name, "RO")
      )
    }
    fix.store.addUserRole(fix.bobUserId, "r-named")

  it should "judge a table whose name holds a quote, a doubled quote or a comma by that exact name (real DuckLake)" in
    withCatalog("mcpsnapquotes", "CREATE SCHEMA lake.sales;") { (reader, _) =>
      def run(sql: String): Long =
        runSqlOnCatalog(q(sql))
        reader.maxSnapshotId().get
      val createdOk  = run("CREATE TABLE lake.sales.'o''k' (id INTEGER);")   // o"k
      val createdOOk = run("CREATE TABLE lake.sales.'o''''k' (id INTEGER);") // o""k
      val createdAB  = run("CREATE TABLE lake.sales.'a,b' (id INTEGER);")
      val insertAB   = run("INSERT INTO lake.sales.'a,b' VALUES (1), (2);")
      List(createdOk, createdOOk, createdAB, insertAB).distinct should have size 4
      val stored = reader.listSnapshots(limit = 1000).map(s => s.snapshotId -> s).toMap
      def storedCounts(id: Long) =
        List(stored(id).rowsAdded, stored(id).filesAdded.toLong, stored(id).filesRemoved.toLong)
      val okAndComma = bobReadsSales("o\"k", "a,b")
      withHarness(prepare = okAndComma, catalogReader = Some((_, _) => reader)) { h =>
        val (err, txt) = callTool(h, bobPat, "list_snapshots", acmeMain)
        val (snaps, _) = parsedSnapshots(txt)
        withClue(s"stored ${stored.values.map(s => s.snapshotId -> s.changes)}: $txt") {
          err shouldBe false
          // o"k's creation is his; o""k's, a table he cannot read, is not.
          snaps.keySet shouldBe Set(createdOk, createdAB, insertAB)
          tablesOf(snaps(createdOk)) shouldBe List("sales" -> "o\"k")
          // A table named with a comma is attributed: counts as stored, not withheld.
          List(createdAB, insertAB).foreach { id =>
            tablesOf(snaps(id)) shouldBe List("sales" -> "a,b")
            countsOf(snaps(id)) shouldBe storedCounts(id)
          }
        }
      }
      withHarness(prepare = bobReadsSales("o\"\"k"), catalogReader = Some((_, _) => reader)) { h =>
        val (err, txt) = callTool(h, bobPat, "list_snapshots", acmeMain)
        val (snaps, _) = parsedSnapshots(txt)
        withClue(s"stored ${stored.values.map(s => s.snapshotId -> s.changes)}: $txt") {
          err shouldBe false
          // Granted the table literally named o""k: its creation only, never o"k's.
          snaps.keySet shouldBe Set(createdOOk)
          tablesOf(snaps(createdOOk)) shouldBe List("sales" -> "o\"\"k")
          countsOf(snaps(createdOOk)) shouldBe storedCounts(createdOOk)
        }
      }
    }

  it should "write one CatalogRead row per list_snapshots call, for a member as for an admin, whatever the tags (real DuckLake)" in
    withCatalog(
      "mcpsnapcuraudit",
      """CREATE SCHEMA lake.sales;
        |CREATE TABLE lake.sales.orders (id INTEGER, v VARCHAR);
        |INSERT INTO lake.sales.orders VALUES (1, 'a');
        |INSERT INTO lake.sales.orders SELECT range, 'bulk' FROM range(1000);
        |""".stripMargin
    ) { (reader, _) =>
      val newest = reader.maxSnapshotId().get
      val rows   = List("alice" -> (() => alicePat), "bob" -> (() => bobPat)).map { (who, pat) =>
        val store = new RecordingTelemetryStore
        withReader(
          reader,
          tags = Seq("newest" -> newest),
          audit = new AuditRecorder(store, _ => None),
          auditCatalogReads = true
        ) { h =>
          val (err, text) = callTool(h, pat(), "list_snapshots", acmeMain)
          withClue(s"$who: $text") {
            err shouldBe false
            parsedSnapshots(text)._2 shouldBe List("newest")
          }
        }
        who -> store.events.count(e =>
          e.action == AuditActions.CatalogRead && e.detail.get("endpoint").contains("snapshots")
        )
      }
      rows shouldBe List("alice" -> 1, "bob" -> 1)
    }

  "mcp admin surface" should "drive the full provisioning flow" in withHarness() { h =>
    val root = mintPat(None, SecurityFixtures.RootUsername)

    // Issues one tools/call, asserts isError:false (withClue prints the body on failure), and
    // returns the tool's result payload parsed back to Json (every tool's "content[0].text" is
    // itself a JSON-encoded object/array).
    def call(name: String, args: (String, Json)*): Json =
      val params = Json.obj(
        "name"      -> Json.fromString(name),
        "arguments" -> Json.obj(args*)
      )
      val resp   = postMcp(h, rpc("tools/call", params = params), Some(root))
      val result = parse(resp.body()).toOption.get.hcursor.downField("result")
      withClue(s"$name -> ${resp.body()}") {
        result.get[Boolean]("isError").toOption shouldBe Some(false)
      }
      val text = result.downField("content").downN(0).get[String]("text").toOption.getOrElse("")
      parse(text).toOption.getOrElse(fail(s"$name: non-JSON result text: $text"))

    def field(json: Json, name: String): String =
      json.hcursor.get[String](name).toOption.getOrElse(fail(s"missing '$name' in $json"))

    // A tenant name holds letters and digits only (Names.normalizeTenantId).
    val tenant = "e2ecorp"

    call("create_tenant", "id" -> Json.fromString(tenant))

    // create_database's "name" argument is a SUFFIX; the supervisor composes the stored
    // tenant-db name as "<tenant>_<suffix>" (Names.normalizeTenantDbName), so create_pool
    // below must address it by the response's "name", not the suffix we passed in.
    val db = call(
      "create_database",
      "tenant" -> Json.fromString(tenant),
      "name"   -> Json.fromString("e2e_db"),
      "kind"   -> Json.fromString("memory")
    )
    val dbName = field(db, "name")

    call(
      "create_pool",
      "tenant"   -> Json.fromString(tenant),
      "database" -> Json.fromString(dbName),
      "pool"     -> Json.fromString("bi"),
      "dual"     -> Json.fromInt(1)
    )

    val role = call(
      "create_role",
      "tenant" -> Json.fromString(tenant),
      "name"   -> Json.fromString("reader")
    )
    val roleId = field(role, "id")

    val user = call(
      "create_user",
      "tenant"   -> Json.fromString(tenant),
      "username" -> Json.fromString("dana"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val userId = field(user, "id")

    call(
      "grant_role_permission",
      "role_id" -> Json.fromString(roleId),
      "verb"    -> Json.fromString("RO")
    )

    call(
      "add_membership",
      "kind"    -> Json.fromString("user_role"),
      "user_id" -> Json.fromString(userId),
      "role_id" -> Json.fromString(roleId)
    )

    call(
      "create_column_policy",
      "role_id"       -> Json.fromString(roleId),
      "column_name"   -> Json.fromString("c_phone"),
      "action"        -> Json.fromString("mask"),
      "transform_sql" -> Json.fromString("'***'")
    )

    val effective = call("user_effective_permissions", "id" -> Json.fromString(userId))
    effective.hcursor.downField("tablePerms").values.get should not be empty
  }
