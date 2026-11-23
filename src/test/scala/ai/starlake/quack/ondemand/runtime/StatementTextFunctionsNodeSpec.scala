package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.edge.FlightSqlRouter
import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.authz.{
  AuthzDecider,
  DecidePurpose,
  DecideRoute,
  DescribedFunction,
  Description,
  DryRunResults,
  MintedHandles,
  NodeKeys,
  NodePrincipal,
  PolicyVersions,
  ReadOnlyCatalogs
}
import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.observability.metrics.{AuthzInstruments, OpaInstruments}
import ai.starlake.quack.ondemand.PoolState
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{
  RbacRole,
  RbacUser,
  RoleColumnPolicy,
  RolePermission,
  RoleRowPolicy
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import io.circe.parser.parse
import org.apache.arrow.memory.RootAllocator
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The statement-text functions (`current_query()`, its `pg_catalog` macro,
  * `duckdb_prepared_statements()` and the `pg_catalog.pg_prepared_statements` view) on a real
  * qod-node decided by the manager's decision route (owner ruling D30): what the node describes for
  * each route a plan reaches them by, and the decider's answer to a principal without policies, a
  * column policy holder and a row policy holder. Every plan description the node sends is kept, so
  * each case states what the decider was shown. Cancelled without a qod-node build.
  */
class StatementTextFunctionsNodeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val allocator          = new RootAllocator()
  private val client             = new QuackHttpClient(allocator)
  private var dir: Path          = null
  private var live: LiveQodNode  = null
  private var server: HttpServer = null

  private val Pool = PoolKey("acme", "acme_mem", "bi")

  private val pools = Map(
    Pool -> PoolState(
      Pool,
      Nil,
      RoleDistribution(0, 0, 1),
      Map("dbName" -> "memory"),
      Map.empty,
      kindWire = TenantDbKind.InMemory.wireValue
    )
  )

  private def routed(sql: String): String =
    FlightSqlRouter.usePath(pools(Pool)).fold(sql)(path => s"USE $path; $sql")

  private val secret         = NodeKeys.randomSecret()
  private val minted         = new MintedHandles(maxEntries = 200_000L)
  private var keys: NodeKeys = null

  /** Every plan description the node sent, in order. */
  private val plans = new ConcurrentLinkedQueue[Description]()

  private val decider = new AuthzDecider(
    tenantOf = t => Option.when(t == "acme")(Tenant("acme")),
    tenantCatalogs = t => if t == "acme" then Set("acme_mem") else Set.empty,
    poolCatalog = key =>
      pools.get(key).flatMap(st => AuthzDecider.attachedCatalog(st.kindWire, st.metastore)),
    parentPoolsOf = _ => Nil,
    locations = new LocationRulesSource {
      def rulesFor(tenantId: String, userId: String): Either[String, LocationRules] =
        Right(LocationRules.Empty)
    },
    opa = new OpaAuthorizer(
      OpaConfig.default,
      new OpaClient(500, (_, _, _) => Left("no OPA in this spec")),
      new OpaDecisionCache(0),
      OpaInstruments.noop
    ),
    opaCfg = OpaConfig.default
  )

  private val AllTablesPerm = RolePermission("p-all", "r-all", "*", "*", "*", "ALL")

  private def user(
      id: String,
      columns: List[RoleColumnPolicy] = Nil,
      rows: List[RoleRowPolicy] = Nil
  ): EffectiveSet =
    EffectiveSet(
      RbacUser(id, Some("acme"), id, "user"),
      List(RbacRole("r-all", "acme", "qod_all_tables")),
      Nil,
      List(AllTablesPerm),
      Nil,
      columnPolicies = columns,
      rowPolicies = rows
    )

  // Policies on a table no statement here reads: the rule counts every policy the set carries.
  private val Free   = user("u-free")
  private val Masked = user(
    "u-masked",
    columns =
      List(RoleColumnPolicy("cp-1", "r-all", "*", "*", "elsewhere", "v", "mask", Some("'***'")))
  )
  private val Filtered =
    user("u-filtered", rows = List(RoleRowPolicy("rp-1", "r-all", "*", "*", "elsewhere", "v = 1")))

  override def beforeAll(): Unit =
    if QodNodeTestBinary.path.nonEmpty then
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      keys = new NodeKeys(
        secret,
        s"http://127.0.0.1:${server.getAddress.getPort}",
        minted = Some(minted),
        paths = key => pools.get(key).flatMap(FlightSqlRouter.usePath).getOrElse(""),
        readOnlyCatalogs = ReadOnlyCatalogs.none,
        versions = PolicyVersions.zero
      )
      val app = new DecideRoute(
        keys,
        minted,
        decider,
        new DryRunResults(),
        key => pools.get(key).flatMap(FlightSqlRouter.usePath).getOrElse(""),
        AuthzInstruments.noop
      ).routes.orNotFound
      server.createContext(
        "/",
        exchange =>
          try
            val body    = exchange.getRequestBody.readAllBytes()
            val headers = exchange.getRequestHeaders.asScala.toList.flatMap((name, values) =>
              values.asScala.map(v => Header.Raw(CIString(name), v))
            )
            keep(body)
            val request = Request[IO](
              Method.fromString(exchange.getRequestMethod).toOption.get,
              Uri.unsafeFromString(exchange.getRequestURI.toString),
              headers = Headers(headers)
            ).withBodyStream(fs2.Stream.emits(body.toSeq))
            val response = app.run(request).unsafeRunSync()
            val bytes    = response.body.compile.to(Array).unsafeRunSync()
            exchange.getResponseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(response.status.code, bytes.length.toLong)
            exchange.getResponseBody.write(bytes)
          finally exchange.close()
      )
      server.start()
      dir = Files.createTempDirectory("qod-node-stmt-text")
      live = LiveQodNode.start(dir, Init, keys = keys)

  /** The plan description of a decide request body, kept in [[plans]]. */
  private def keep(body: Array[Byte]): Unit =
    parse(new String(body, java.nio.charset.StandardCharsets.UTF_8)).toOption.foreach { json =>
      val c = json.hcursor
      if c.get[String]("kind").contains("plan") then
        c.get[String]("description")
          .toOption
          .flatMap(d => Description.decode(Base64.getUrlDecoder.decode(d)).toOption)
          .foreach(plans.add)
    }

  /** The objects every route below reaches the functions through, made by the trusted init SQL. */
  private val Init: String =
    """CREATE TABLE memory.main.t AS SELECT 1 AS v;
      |CREATE VIEW memory.main.qv AS SELECT current_query() AS q;
      |CREATE VIEW memory.main.qv_pg AS SELECT pg_catalog.current_query() AS q;
      |CREATE VIEW memory.main.psv AS SELECT name FROM duckdb_prepared_statements();
      |CREATE VIEW memory.main.pgpsv AS SELECT name FROM pg_catalog.pg_prepared_statements;
      |CREATE SECURE VIEW memory.main.sqv AS SELECT current_query() AS q;
      |CREATE SECURE VIEW memory.main.spsv AS SELECT name FROM duckdb_prepared_statements();
      |CREATE SECURE VIEW memory.main.spgpsv AS SELECT name FROM pg_catalog.pg_prepared_statements;
      |CREATE MACRO memory.main.mq() AS current_query();
      |CREATE MACRO memory.main.tmq() AS TABLE SELECT current_query() AS q;
      |CREATE TABLE memory.main.g (v INTEGER, q VARCHAR GENERATED ALWAYS AS (current_query()));
      |INSERT INTO memory.main.g (v) VALUES (1);
      |CREATE TABLE memory.main.d (v INTEGER, q VARCHAR DEFAULT current_query());
      |CREATE TABLE memory.main.c (v INTEGER CHECK (length(current_query()) > 0));
      |""".stripMargin

  override def afterAll(): Unit =
    try
      if live != null then live.stop()
      if server != null then server.stop(0)
      allocator.close()
    finally
      if dir != null then
        val tree = Files.walk(dir)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        catch
          case e: Exception =>
            System.err.println(s"[StatementTextFunctionsNodeSpec] afterAll: $dir: $e")
        finally tree.close()

  private def node: LiveQodNode =
    assume(live != null, "no qod-node binary (QOD_NODE_BIN or a local build)")
    live

  /** `sql` as `who`, as the router sends it (its USE prelude first): the rows, or the node's error,
    * and every function of every plan description the node sent for it (the prelude's has none).
    */
  private def as(who: EffectiveSet, sql: String): (Either[String, List[String]], List[String]) =
    plans.clear()
    val principal = NodePrincipal.of(Some(who), Pool, DecidePurpose.Live)
    val onPool    = node.node.copy(poolKey = Pool)
    val out       = LiveQodNode.lines(
      client
        .query(node.endpoint, node.token, routed(sql), None, () => keys.prelude(onPool, principal))
        .unsafeRunSync()
    )
    (out, plans.asScala.toList.flatMap(_.functions).map(shown).distinct)

  private def shown(f: DescribedFunction): String =
    s"${f.catalog}.${f.schema}.${f.name}:${f.tpe}" + f.via.fold("")(v => s" via $v")

  private def refusal(name: String): String =
    s"Permission Error: QoD: function $name is not available to principals with column or row " +
      "policies"

  private val fresh = new java.util.concurrent.atomic.AtomicInteger()

  private val CurrentQuery = "system.main.current_query:scalar"
  private val Prepared     = "system.main.duckdb_prepared_statements:table"

  /** A route the node describes: `described` among the functions it sent the decider for a
    * policy-free reader, who is answered; refused to both policy holders with `name`.
    */
  private def described(sql: String, described: String, name: String): Unit =
    // Fresh principals per route: the node caches a verdict per principal and fingerprint, and
    // several routes bind to one plan, so a shared principal would be answered from the cache.
    val n                        = fresh.incrementAndGet()
    def renamed(e: EffectiveSet) = e.copy(user = e.user.copy(id = s"${e.user.id}-$n"))
    val (free, functions)        = as(renamed(Free), sql)
    info(s"$sql | described: ${functions.mkString(", ")}")
    withClue(s"$sql (free reader) -> ")(free shouldBe a[Right[?, ?]])
    withClue(s"$sql: functions described -> ")(
      functions.exists(_.startsWith(described)) shouldBe
        true
    )
    List(Masked, Filtered).map(renamed).foreach { who =>
      withClue(s"$sql (${who.user.username}) -> ")(as(who, sql)._1 shouldBe Left(refusal(name)))
    }

  /** A route the node refuses on its own, to every non-superuser: `why` in the error. */
  private def refusedByNode(sql: String, why: String): Unit =
    List(Free, Masked, Filtered).foreach { who =>
      withClue(s"$sql (${who.user.username}) -> ") {
        as(who, sql)._1 match
          case Left(err) => err should include(why)
          case Right(r)  => fail(s"expected a refusal, got $r")
      }
    }

  "a direct call" should "be described and refused to a principal with policies" in {
    described("SELECT current_query()", CurrentQuery, "current_query")
    described(
      "SELECT name FROM duckdb_prepared_statements()",
      Prepared,
      "duckdb_prepared_statements"
    )
    described("SELECT (SELECT current_query())", CurrentQuery, "current_query")
    described(
      "SELECT list_transform([1], lambda x: current_query())",
      CurrentQuery,
      "current_query"
    )
  }

  "the pg_catalog.current_query macro" should "be described as the function its body calls" in
    described("SELECT pg_catalog.current_query()", CurrentQuery, "current_query")

  "a plain view" should "be described with the function its body calls" in {
    described("SELECT q FROM qv", CurrentQuery, "current_query")
    described("SELECT q FROM qv_pg", CurrentQuery, "current_query")
    described("SELECT name FROM psv", Prepared, "duckdb_prepared_statements")
  }

  "a secure view" should "be described with the function its body calls, through the view" in {
    described("SELECT q FROM sqv", s"$CurrentQuery via memory.main.sqv", "current_query")
    described(
      "SELECT name FROM spsv",
      s"$Prepared via memory.main.spsv",
      "duckdb_prepared_statements"
    )
    // pg_catalog's view inside a secure view: inlined as the listing function it calls.
    described(
      "SELECT name FROM spgpsv",
      s"$Prepared via memory.main.spgpsv",
      "duckdb_prepared_statements"
    )
  }

  "pg_catalog.pg_prepared_statements" should "be refused by the node outside a secure view" in {
    refusedByNode(
      "SELECT name FROM pg_catalog.pg_prepared_statements",
      "pg_catalog.pg_prepared_statements is reserved to superusers"
    )
    refusedByNode(
      "SELECT name FROM pg_prepared_statements",
      "pg_catalog.pg_prepared_statements is reserved to superusers"
    )
    // In a plain view's body too.
    refusedByNode("SELECT name FROM pgpsv", "pg_catalog.pg_prepared_statements is reserved")
  }

  "a superuser-made macro" should "be refused as unclassified to every non-superuser" in {
    refusedByNode("SELECT mq()", "function mq is not classified")
    refusedByNode("SELECT q FROM tmq()", "table function tmq is not available to non-superusers")
  }

  "a generated column" should "be described with the function its expression calls" in
    described("SELECT q FROM g", CurrentQuery, "current_query")

  "a written table's DEFAULT" should "be described with the function it calls" in
    described("INSERT INTO d (v) VALUES (1)", CurrentQuery, "current_query")

  "a written table's CHECK constraint" should "be described with the function it calls" in
    described("INSERT INTO c VALUES (1)", CurrentQuery, "current_query")

  "query() with a literal" should "be described with the function its query calls" in
    described("SELECT q FROM query('SELECT current_query() AS q')", CurrentQuery, "current_query")

  "a temporary view of the reader's own" should "be described with the function it calls" in
    described(
      "CREATE TEMP VIEW tv AS SELECT current_query() AS q; SELECT q FROM tv",
      CurrentQuery,
      "current_query"
    )

  "prepared statements" should "keep working for a principal with policies" in
    List(Free, Masked, Filtered).foreach { who =>
      withClue(who.user.username)(
        as(who, "PREPARE p AS SELECT v FROM t; EXECUTE p")._1 shouldBe Right(List("1"))
      )
    }

  // ---- slots DuckDB evaluates while binding: the plan holds their result ----

  "a statement-text function in a SET VARIABLE value, a LIMIT or a planned argument" should
    "be described and refused to a principal with policies" in {
      described("SET VARIABLE x = current_query()", CurrentQuery, "current_query")
      described("SELECT 1 LIMIT length(current_query())", CurrentQuery, "current_query")
      described(
        "SELECT count(*) FROM range((SELECT length(current_query())))",
        CurrentQuery,
        "current_query"
      )
      described(
        "SELECT name FROM duckdb_prepared_statements() WHERE name = current_query()",
        CurrentQuery,
        "current_query"
      )
    }
