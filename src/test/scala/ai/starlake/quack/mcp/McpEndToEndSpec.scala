// src/test/scala/ai/starlake/quack/mcp/McpEndToEndSpec.scala
package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.api.{
  CatalogColumnEntry,
  CatalogDataFileEntry,
  CatalogHistoryCommit,
  CatalogSchemaEntry,
  CatalogTableDetailResponse,
  CatalogTableEntry
}
import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.{
  DuckLakeCatalogReader,
  HistoryOperation,
  TableHistoryFilter,
  TableHistoryPage
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
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
import ai.starlake.quack.security.{ManagerServerHarness, SecurityFixtures}
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.util.Try

/** Full-wire MCP contract: real HTTP against the harness-booted manager with a Postgres PAT store,
  * covering auth arms, tool tiering per principal, and the enabled=false unmount.
  */
class McpEndToEndSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

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
      catalogReader: Option[(String, String) => DuckLakeCatalogReader] = None
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
      mcpEnabled = mcpEnabled
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

  /** A catalog of two schemas: `sales.orders` (bob's grant below), `sales.secret`, `hr.salaries`.
    */
  private val memberCatalog: (String, String) => DuckLakeCatalogReader = (_, _) =>
    new DuckLakeCatalogReader(null):
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
