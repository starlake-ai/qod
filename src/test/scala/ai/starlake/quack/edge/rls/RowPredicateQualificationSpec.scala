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

  /** [[RowPolicyRewriter.RowAlias]], spelled out so a change of it is a deliberate one. */
  private val Alias = "__qod_rls"

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

  private def rewritten(o: Outcome): String = o match
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

  "a statement naming the internal alias" should "be refused" in
    Seq(
      s"SELECT * FROM secret, (SELECT 'eu' AS region) $Alias",
      s"SELECT * FROM secret, (SELECT 'eu' AS region) \"${Alias.toUpperCase}\""
    ).foreach { q =>
      withClue(q) {
        go(q, euOnly) match
          case Refused(reason) => reason should include(Alias)
          case other           => fail(s"expected Refused, got $other")
      }
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
