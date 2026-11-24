package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.cls.ColumnCatalog
import ai.starlake.quack.ondemand.state.{RoleColumnPolicy, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Connection
import scala.util.Using

/** Gateway output never carries row predicates in SQL, labels or struct fields. CLS preserves
  * caller result names while row-only statements retain DuckDB's original labels.
  */
class PolicyResultNameSpec extends AnyFlatSpec with Matchers:
  import PolicyPipelineFixture._

  private val Secret = "424242"
  private val Salt   = "pepper9f2"
  private val kc     = DuckLake

  private val catalog = new ColumnCatalog.MapCatalog()

  private val rowPolicy =
    RoleRowPolicy("rp-t", "r-1", "*", "main", "t", s"n <> $Secret")
  private val mask = RoleColumnPolicy(
    "cp-t-email",
    "r-1",
    "*",
    "main",
    "t",
    "email",
    "mask",
    Some(s"concat('$Salt', md5(CAST(email AS VARCHAR)))")
  )

  private val rowOnly = effWith(rowPolicies = List(rowPolicy))
  private val both    = effWith(columnPolicies = List(mask), rowPolicies = List(rowPolicy))

  private def seeded(f: Connection => Unit): Unit =
    withDuckDb(kc.catalog) { conn =>
      List(
        s"CREATE TABLE ${kc.catalog}.main.t (id INTEGER, email VARCHAR, name VARCHAR, n INTEGER)",
        s"CREATE TABLE ${kc.catalog}.main.u (id INTEGER, email VARCHAR)",
        s"INSERT INTO ${kc.catalog}.main.t VALUES (1, 'a@x.com', 'Bob', 3)",
        s"INSERT INTO ${kc.catalog}.main.u VALUES (1, 'u@x.com')"
      ).foreach(exec(conn, _))
      f(conn)
    }

  /** The column labels and type names the sent statement answers with. */
  private def namesOf(conn: Connection, sent: String): List[String] =
    val UsePrefix = """(?s)^\s*(USE\s+[^;]+);\s*(.*)$""".r
    val body      = sent match
      case UsePrefix(use, rest) => exec(conn, use); rest
      case other                => other
    Using.resource(conn.createStatement()) { st =>
      Using.resource(st.executeQuery(body)) { rs =>
        val m = rs.getMetaData
        (1 to m.getColumnCount).toList.flatMap(i =>
          List(m.getColumnLabel(i), m.getColumnTypeName(i))
        )
      }
    }

  private val h = harness(kc, columnCatalog = catalog)

  /** The labels `sql` answers with through the pipeline, after checking it was row-filtered and no
    * label or type name holds the predicate, its alias or the mask.
    */
  private def labels(
      conn: Connection,
      sql: String,
      eff: ai.starlake.quack.ondemand.rbac.EffectiveSet
  ): List[String] =
    val sent = h.run(sql, eff).fold(f => fail(s"$sql: $f"), identity)
    sent should not include "__qod_rls_"
    sent should not include s"n <> $Secret"
    val names = namesOf(conn, sent)
    names.foreach { n =>
      withClue(s"$sql -> $sent: ") {
        n should not include Secret
        n should not include "__qod_rls_"
        n should not include Salt
      }
    }
    names.grouped(2).map(_.head).toList

  /** Statements reading the row-policy table where a name can reach the client, each with the
    * labels it must answer with.
    */
  private val Cases = List(
    "SELECT (SELECT max(id) FROM t)"             -> List("(SELECT max(id) FROM t)"),
    "SELECT id, (SELECT count(*) FROM t) FROM u" -> List("id", "(SELECT count(*) FROM t)"),
    "SELECT EXISTS (SELECT 1 FROM t)"            -> List("EXISTS (SELECT 1 FROM t)"),
    "SELECT id IN (SELECT id FROM t) FROM u"     -> List("id IN (SELECT id FROM t)"),
    "SELECT ARRAY(SELECT id FROM t)"             -> List("ARRAY(SELECT id FROM t)"),
    "SELECT CASE WHEN EXISTS (SELECT 1 FROM t) THEN 1 END" ->
      List("CASE WHEN EXISTS (SELECT 1 FROM t) THEN 1 END"),
    "SELECT * FROM (SELECT (SELECT max(id) FROM t)) AS d" -> List("(SELECT max(id) FROM t)"),
    "SELECT d.* FROM (SELECT (SELECT max(id) FROM t) FROM u) AS d" ->
      List("(SELECT max(id) FROM t)"),
    "WITH c AS (SELECT (SELECT max(id) FROM t) FROM u) SELECT * FROM c" ->
      List("(SELECT max(id) FROM t)"),
    "SELECT (SELECT max(id) FROM t) UNION ALL SELECT 1" -> List("(SELECT max(id) FROM t)"),
    "SELECT (SELECT max(id) FROM t) UNION BY NAME SELECT (SELECT max(id) FROM t)" ->
      List("(SELECT max(id) FROM t)"),
    "SELECT * FROM (SELECT (SELECT max(id) FROM t)) AS _qod_probe LIMIT 0" ->
      List("(SELECT max(id) FROM t)"),
    "SELECT id, (SELECT max(id) FROM t) AS mine FROM u"          -> List("id", "mine"),
    "SELECT id FROM t"                                           -> List("id"),
    "SELECT d FROM (SELECT (SELECT max(id) FROM t) FROM u) AS d" -> List("d"),
    // DISTINCT ON expressions are not result columns: they get no alias.
    "SELECT DISTINCT ON ((SELECT max(id) FROM t)) id FROM u"                      -> List("id"),
    "SELECT DISTINCT ON ((SELECT max(id) FROM t)) (SELECT min(id) FROM t) FROM u" ->
      List("(SELECT min(id) FROM t)")
  )

  "a holder of a row policy alone" should "see no predicate in any result name" in
    seeded { conn =>
      Cases.foreach((sql, _) =>
        withClue(sql)(
          labels(conn, sql, rowOnly) shouldBe namesOf(conn, sql).grouped(2).map(_.head).toList
        )
      )
    }

  "a holder of a row policy and a mask" should "see neither in any result name" in
    seeded { conn =>
      Cases.foreach((sql, _) => withClue(sql)(labels(conn, sql, both).nonEmpty shouldBe true))
      labels(conn, "SELECT (SELECT max(email) FROM t)", both) shouldBe
        List("(SELECT max(email) FROM t)")
      labels(conn, "SELECT (SELECT max(id) FROM t), lower(email) FROM t", both) shouldBe
        List("(SELECT max(id) FROM t)", "lower(email)")
      labels(conn, "SELECT EXISTS (SELECT 1 FROM t WHERE email = 'x')", both) shouldBe
        namesOf(conn, "SELECT EXISTS (SELECT 1 FROM t WHERE email = 'x')")
          .grouped(2)
          .map(_.head)
          .toList
    }

  it should "preserve caller SQL independently of signed policies" in {
    val sent = h.run("SELECT (SELECT max(email) FROM t) FROM u", both).toOption.get
    sent should endWith("SELECT (SELECT max(email) FROM t) FROM u")
    sent should not include "\" AS \""
  }
