package ai.starlake.quack.edge.rls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import scala.collection.mutable.ListBuffer
import scala.util.{Try, Using}

/** A row predicate reads only its own table (owner ruling D26). The filtered view aliases its base
  * table with [[RowPolicyRewriter.RowAlias]] and qualifies every column of the predicate with it,
  * so a column the table no longer has is a binder error, never a column of a FROM item the caller
  * put beside the table (DuckDB 2.0 binds an unqualified name of a derived table laterally).
  */
class RowPredicateQualificationSpec extends AnyFlatSpec with Matchers:
  import RowPolicyRewriter._

  private val user = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
  private val ctx  =
    SchemaContext(defaultDatabase = Some("acme_tpch"), defaultSchema = Some("tpch1"))
  private val rw = new RowPolicyRewriter(enabled = true)

  /** The alias as the assertions read it: every fresh alias ([[AliasPattern]]) is replaced by it.
    */
  private val Alias = "__qod_rls"

  /** A fresh alias: the prefix and 128 random bits in hex, never pinned by an assertion. */
  private val AliasPattern = "__qod_rls_[0-9a-f]{32}".r

  private def normalized(sql: String): String = AliasPattern.replaceAllIn(sql, Alias)

  private def policy(predicate: String, table: String = "secret"): List[RoleRowPolicy] =
    List(RoleRowPolicy("rp-1", "r-1", "*", "tpch1", table, predicate))

  private def go(sql: String, policies: List[RoleRowPolicy]): Outcome =
    val outcome = rw.rewrite(
      sql,
      StatementKind.Select,
      EffectiveSet(user, Nil, Nil, Nil, Nil, Nil, policies),
      ctx
    )
    info(s"original: $sql")
    info(s"result:   $outcome")
    outcome

  /** The shipped text, its fresh alias read as [[Alias]]. */
  private def rewritten(o: Outcome): String = o match
    case Rewritten(sql) => normalized(sql)
    case other          => fail(s"expected Rewritten, got $other")

  /** The shipped text as it is, for execution. */
  private def shippedAsIs(o: Outcome): String = o match
    case Rewritten(sql) => sql
    case other          => fail(s"expected Rewritten, got $other")

  private val euOnly = policy("region = 'eu'")

  // ---------- the printed filter ----------

  "a row predicate" should "read its table through the internal alias" in {
    rewritten(go("SELECT s.id FROM secret s", euOnly)) shouldBe
      s"SELECT s.id FROM (SELECT * FROM secret AS $Alias WHERE ($Alias.region = 'eu')) s"
  }

  it should "replace a qualifier naming its own table" in
    Seq(
      "secret.region = 'eu'",
      "SECRET.region = 'eu'",
      "tpch1.secret.region = 'eu'",
      "acme_tpch.tpch1.secret.region = 'eu'"
    ).foreach { p =>
      withClue(p)(
        rewritten(go("SELECT * FROM secret", policy(p))) should include(
          s"WHERE ($Alias.region = 'eu')"
        )
      )
    }

  it should "keep a struct field path under the alias" in {
    rewritten(go("SELECT * FROM secret", policy("info.country = 'fr'"))) should include(
      s"WHERE ($Alias.info.country = 'fr')"
    )
    rewritten(go("SELECT * FROM secret", policy("secret.info.country = 'fr'"))) should include(
      s"WHERE ($Alias.info.country = 'fr')"
    )
  }

  it should "leave lambda parameters and keyword functions as written" in {
    val lambda = rewritten(
      go("SELECT * FROM secret", policy("len(list_filter(tags, lambda x: x = 'eu')) > 0"))
    )
    lambda should include(s"list_filter($Alias.tags, lambda x : x = 'eu')")
    rewritten(go("SELECT * FROM secret", policy("owner = current_user"))) should include(
      s"$Alias.owner = current_user"
    )
  }

  it should "qualify a quoted name and keep its quotes" in {
    rewritten(go("SELECT * FROM secret", policy("\"Region\" = 'eu'"))) should include(
      s"$Alias.\"Region\" = 'eu'"
    )
  }

  it should "fail closed on a subquery column it cannot place" in {
    Seq(
      "region IN (SELECT r FROM allowed)",
      "region IN (SELECT a.r FROM allowed a WHERE a.k = id)"
    ).foreach { p =>
      withClue(p) {
        go("SELECT * FROM secret", policy(p)) match
          case Failed(_) => succeed
          case other     => fail(s"expected Failed, got $other")
      }
    }
    rewritten(
      go(
        "SELECT * FROM secret",
        policy("region IN (SELECT a.r FROM allowed a WHERE a.k = secret.id)")
      )
    ) should include(s"$Alias.region IN (SELECT a.r FROM allowed a WHERE a.k = $Alias.id)")
  }

  it should "compare its own table's name as DuckDB does" in {
    val kelvin = "\u212Ax"
    kelvin.exists(_ == '\u212A') shouldBe true
    // The table's own spelling is its qualifier; an ASCII lookalike is a struct path (which
    // DuckDB then refuses, as the table has no such column).
    rewritten(go(s"SELECT * FROM \"$kelvin\"", policy(s"\"$kelvin\".v = 1", kelvin))) should
      include(s"WHERE ($Alias.v = 1)")
    rewritten(go(s"SELECT * FROM \"$kelvin\"", policy("kx.v = 1", kelvin))) should
      include(s"WHERE ($Alias.kx.v = 1)")
  }

  it should "put a time-travel clause after the alias" in {
    rewritten(go("SELECT id FROM secret AT (VERSION => 3)", euOnly)) should include(
      s"FROM secret AS $Alias AT (VERSION => 3) WHERE"
    )
  }

  "the alias" should "be fresh for each rewrite and unpredictable" in {
    val sql   = "SELECT s.id FROM secret s"
    val first = shippedAsIs(go(sql, euOnly))
    val again = shippedAsIs(go(sql, euOnly))
    val a     = AliasPattern.findAllIn(first).toSet
    val b     = AliasPattern.findAllIn(again).toSet
    a.size shouldBe 1
    b.size shouldBe 1
    a should not equal b
    first should not include s"$Alias "
    first should not include s"$Alias."
  }

  "a statement naming __qod_rls" should "not be refused for it" in
    Seq(
      s"SELECT s.id, '$Alias' AS note FROM secret s",
      s"SELECT * FROM secret, (SELECT 'eu' AS region) $Alias"
    ).foreach { q =>
      withClue(q)(rewritten(go(q, euOnly)) should include(s"$Alias.region = 'eu'"))
    }

  // ---------- executed in DuckDB ----------

  /** `secret` with the predicate's columns (current) or without `region` (stale), and `allowed`. */
  private def database(stale: Boolean): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn                    = DriverManager.getConnection("jdbc:duckdb:")
    def exec(sql: String): Unit = Using.resource(conn.createStatement())(_.execute(sql)): Unit
    exec("ATTACH ':memory:' AS acme_tpch")
    exec("CREATE SCHEMA acme_tpch.tpch1")
    exec("USE acme_tpch.tpch1")
    if stale then
      exec("CREATE TABLE secret (id INTEGER, name VARCHAR)")
      exec("INSERT INTO secret VALUES (1, 'a'), (2, 'b')")
    else
      exec(
        "CREATE TABLE secret (id INTEGER, region VARCHAR, info STRUCT(country VARCHAR), " +
          "tags VARCHAR[], owner VARCHAR)"
      )
      exec(
        "INSERT INTO secret VALUES (1, 'eu', {'country': 'fr'}, ['eu'], 'alice'), " +
          "(2, 'us', {'country': 'us'}, ['us'], 'bob')"
      )
    exec("CREATE TABLE allowed (r VARCHAR, k INTEGER)")
    exec("INSERT INTO allowed VALUES ('eu', 1)")
    // Columns named like the alias's prefix, brought in without the statement spelling them.
    exec(s"CREATE TABLE mine ($Alias STRUCT(region VARCHAR))")
    exec("INSERT INTO mine VALUES ({'region': 'eu'})")
    exec("CREATE TABLE kv (k VARCHAR, v STRUCT(region VARCHAR))")
    exec(s"INSERT INTO kv VALUES ('$Alias', {'region': 'eu'})")
    conn

  private def rowsOf(conn: Connection, sql: String): Either[String, List[List[String]]] =
    Try {
      Using.resource(conn.createStatement()) { st =>
        Using.resource(st.executeQuery(sql)) { rs =>
          val n   = rs.getMetaData.getColumnCount
          val out = ListBuffer.empty[List[String]]
          while rs.next() do out += (1 to n).map(i => rs.getString(i)).toList
          out.toList
        }
      }
    }.toEither.left.map(_.getMessage)

  /** Statements putting a FROM item with a `region` column beside, or around, the policy table. */
  private val shapes = List(
    "lateral comma join" -> "SELECT s.id FROM (SELECT 'eu' AS region) v, secret s ORDER BY s.id",
    "correlated scalar subquery" ->
      ("SELECT (SELECT string_agg(s.id::VARCHAR, ',' ORDER BY s.id) FROM secret s) AS ids " +
        "FROM (SELECT 'eu' AS region) o"),
    "CTE" -> "WITH v AS (SELECT 'eu' AS region) SELECT s.id FROM v, secret s ORDER BY s.id",
    "JOIN ON true" ->
      "SELECT s.id FROM (SELECT 'eu' AS region) v JOIN secret s ON true ORDER BY s.id"
  )

  for (label, sql) <- shapes do
    s"a $label beside the policy table" should "return only the rows the predicate admits" in
      Using.resource(database(stale = false)) { conn =>
        rowsOf(conn, rewritten(go(sql, euOnly))) shouldBe Right(List(List("1")))
      }

    it should "be refused by DuckDB when the table no longer has the predicate's column" in
      Using.resource(database(stale = true)) { conn =>
        rowsOf(conn, rewritten(go(sql, euOnly))) match
          case Left(message) => message should include("Binder Error")
          case Right(rows)   => fail(s"expected a binder error, got $rows")
      }

  "every predicate shape" should "filter as written when the column exists" in
    Using.resource(database(stale = false)) { conn =>
      Seq(
        "secret.region = 'eu'",
        "tpch1.secret.region = 'eu'",
        "info.country = 'fr'",
        "len(list_filter(tags, lambda x: x = 'eu')) > 0",
        "\"REGION\" = 'eu'",
        "owner = 'alice' OR owner = current_user AND false",
        "region IN (SELECT a.r FROM allowed a WHERE a.k = secret.id)"
      ).foreach { p =>
        withClue(p) {
          val sql =
            "SELECT s.id FROM (SELECT 'eu' AS region, 'fr' AS country) v, secret s ORDER BY 1"
          rowsOf(conn, rewritten(go(sql, policy(p)))) shouldBe Right(List(List("1")))
        }
      }
    }

  // ---------- an outer struct column named like the alias (review R3-I1) ----------

  private val guard = new ai.starlake.quack.edge.policy.ProtectedWriteGuard(
    new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(Map.empty),
    clsEnabled = true,
    rlsEnabled = true
  )

  /** The guard, then the rewrite, as the router runs them for a row-policy holder: Left(reason)
    * when either refuses, else the text that would ship.
    */
  private def pipeline(sql: String): Either[String, String] =
    val clsCtx = ai.starlake.quack.edge.cls.SchemaContext(
      Some("acme_tpch"),
      Some("tpch1"),
      attachedCatalogs = Set.empty
    )
    val eff = EffectiveSet(user, Nil, Nil, Nil, Nil, Nil, euOnly)
    guard.check(sql, StatementKind.Select, eff, clsCtx) match
      case ai.starlake.quack.edge.policy.GuardOutcome.Deny(reason) => Left(reason)
      case _                                                       =>
        go(sql, euOnly) match
          case Rewritten(out) => Right(out)
          case Passthrough    => Right(sql)
          case other          => Left(other.toString)

  private val capturing = List(
    "a table column, lateral comma join"  -> "SELECT s.id FROM mine m, secret s ORDER BY 1",
    "a table column, correlated subquery" ->
      "SELECT (SELECT string_agg(s.id::VARCHAR, ',' ORDER BY s.id) FROM secret s) FROM mine m",
    "a table column, JOIN ON true" -> "SELECT s.id FROM mine m JOIN secret s ON true ORDER BY 1",
    "a PIVOT, lateral comma join"  ->
      "SELECT s.id FROM (PIVOT kv ON k USING first(v)) p, secret s ORDER BY 1",
    "a PIVOT, correlated subquery" ->
      ("SELECT (SELECT string_agg(s.id::VARCHAR, ',' ORDER BY s.id) FROM secret s) " +
        "FROM (PIVOT kv ON k USING first(v)) p"),
    "a PIVOT, JOIN ON true" ->
      "SELECT s.id FROM (PIVOT kv ON k USING first(v)) p JOIN secret s ON true ORDER BY 1"
  )

  for (label, sql) <- capturing do
    s"$label holding a struct column named $Alias" should
      "return only the rows the predicate admits" in
      Using.resource(database(stale = false)) { conn =>
        pipeline(sql) match
          case Right(out) => rowsOf(conn, out) shouldBe Right(List(List("1")))
          case Left(why)  => fail(s"refused: $why")
      }

    it should "be refused or fail to bind when the table no longer has the column" in
      Using.resource(database(stale = true)) { conn =>
        pipeline(sql) match
          case Left(_)    => succeed
          case Right(out) =>
            rowsOf(conn, out) match
              case Left(message) => message should include("Binder Error")
              case Right(rows)   => fail(s"expected a binder error, got $rows")
      }
