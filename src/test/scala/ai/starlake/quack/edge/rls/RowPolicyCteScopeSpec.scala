package ai.starlake.quack.edge.rls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import scala.collection.mutable.ListBuffer
import scala.util.Using

/** A row policy filters a table reference only where DuckDB binds the reference to that table. An
  * unqualified FROM name that binds to a CTE in scope is the CTE, whatever table shares its name,
  * so it is never wrapped: inside the wrap the name would again mean the CTE, and the predicate's
  * columns (and `rowid`), which the CTE lacks, would bind to an earlier FROM item instead. The
  * scoping follows DuckDB 2.0 (checked on its CLI): a WITH's CTEs are visible in its body and the
  * body's subqueries, a CTE in later CTEs of its WITH and in itself only in the recursive term of a
  * WITH RECURSIVE union, an inner WITH shadows an outer one, a qualified name is never a CTE, and
  * names compare by DuckDB's ASCII-only fold.
  */
class RowPolicyCteScopeSpec extends AnyFlatSpec with Matchers:
  import RowPolicyRewriter._

  private val user = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
  private val ctx  =
    SchemaContext(defaultDatabase = Some("acme_tpch"), defaultSchema = Some("tpch1"))
  private val rw = new RowPolicyRewriter(enabled = true)

  private def policy(table: String, predicate: String, id: String = "rp-1"): RoleRowPolicy =
    RoleRowPolicy(id, "r-1", "*", "tpch1", table, predicate)

  private val secretPolicy = policy("secret", "region = 'eu'")

  private def go(sql: String, policies: List[RoleRowPolicy] = List(secretPolicy)): Outcome =
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

  /** How many filtered views the rewrite holds for `predicate`. */
  private def wraps(sql: String, predicate: String): Int =
    sql.sliding(predicate.length).count(_ == predicate)

  // ---------- a CTE named like a row-policy table ----------

  "a CTE named like a row-policy table" should "not be wrapped" in {
    go("WITH secret AS (SELECT 1 AS y) SELECT t.id, s.y FROM t, secret s") shouldBe Passthrough
    go("WITH secret AS (SELECT 1 AS y) SELECT * FROM secret") shouldBe Passthrough
    go("WITH secret AS (SELECT 1 AS y) SELECT t.id FROM t JOIN secret ON true") shouldBe
      Passthrough
  }

  it should "not be wrapped when the CTE and the reference differ in ASCII case" in {
    go("WITH \"SECRET\" AS (SELECT 1 AS y) SELECT t.id, s.y FROM t, secret s") shouldBe Passthrough
    go("WITH secret AS (SELECT 1 AS y) SELECT t.id, s.y FROM t, SeCrEt s") shouldBe Passthrough
    go("WITH SECRET AS (SELECT 1 AS y) SELECT t.id, s.y FROM t, \"secret\" s") shouldBe Passthrough
  }

  it should "not be wrapped under MATERIALIZED or NOT MATERIALIZED" in {
    go("WITH secret AS MATERIALIZED (SELECT 1 AS y) SELECT t.id FROM t, secret s") shouldBe
      Passthrough
    go("WITH secret AS NOT MATERIALIZED (SELECT 1 AS y) SELECT t.id FROM t, secret s") shouldBe
      Passthrough
  }

  it should "not be wrapped in either arm of a set operation under the WITH" in {
    go(
      "WITH secret AS (SELECT 1 AS y) SELECT y FROM secret UNION ALL SELECT y FROM secret"
    ) shouldBe
      Passthrough
    go(
      "WITH secret AS (SELECT 1 AS y) SELECT 0 AS y FROM t INTERSECT SELECT y FROM secret"
    ) shouldBe
      Passthrough
  }

  it should "not be wrapped in a FROM-first body" in {
    go("WITH secret AS (SELECT 1 AS y) FROM secret") shouldBe Passthrough
    go("WITH secret AS (SELECT 1 AS y) SELECT * FROM (FROM secret)") shouldBe Passthrough
  }

  it should "not be wrapped when it carries a time-travel clause (DuckDB reads the CTE)" in {
    go("WITH secret AS (SELECT 1 AS y) SELECT * FROM t, secret AT (VERSION => 2)") shouldBe
      Passthrough
  }

  // ---------- names DuckDB holds apart ----------

  /** (a non-ASCII letter Java folds to an ASCII one, that ASCII letter, its code point). */
  private val nonAscii =
    List(('\u0130', 'i', 0x130), ('\u212A', 'k', 0x212a), ('\u017F', 's', 0x17f))

  for (u, a, cp) <- nonAscii do
    val nonAsciiName = s"${u}x"
    val asciiName    = s"${a}x"
    val hex          = f"U+$cp%04X"
    val both         = List(
      policy(asciiName, "v = 'ascii'", "rp-a"),
      policy(nonAsciiName, "v = 'nonascii'", "rp-n")
    )

    s"a CTE named with $hex" should "leave the ASCII-named table it does not shadow wrapped" in {
      nonAsciiName.exists(_ == u) shouldBe true
      nonAsciiName.head.toInt shouldBe cp
      val sql = rewritten(
        go(
          s"WITH \"$nonAsciiName\" AS (SELECT 1 AS y) SELECT * FROM $asciiName p, \"$nonAsciiName\" c",
          both
        )
      )
      wraps(sql, "v = 'ascii'") shouldBe 1
      wraps(sql, "v = 'nonascii'") shouldBe 0
      sql should include(s"\"$nonAsciiName\" c")
    }

    s"an ASCII-named CTE beside a $hex table" should "leave that table wrapped" in {
      nonAsciiName.exists(_ == u) shouldBe true
      nonAsciiName.head.toInt shouldBe cp
      val sql = rewritten(
        go(
          s"WITH $asciiName AS (SELECT 1 AS y) SELECT * FROM $asciiName p, \"$nonAsciiName\" c",
          both
        )
      )
      wraps(sql, "v = 'nonascii'") shouldBe 1
      wraps(sql, "v = 'ascii'") shouldBe 0
      sql should include(s"FROM $asciiName p")
    }

  // ---------- scopes ----------

  "a CTE reference in a nested scope" should "not be wrapped" in {
    go(
      "WITH secret AS (SELECT 1 AS y) SELECT * FROM t WHERE t.id IN (SELECT y FROM secret)"
    ) shouldBe
      Passthrough
    go("WITH secret AS (SELECT 1 AS y) SELECT (SELECT max(y) FROM secret) AS m FROM t") shouldBe
      Passthrough
    go("WITH secret AS (SELECT 1 AS y), b AS (SELECT * FROM secret) SELECT * FROM t, b") shouldBe
      Passthrough
    go(
      "WITH secret AS (SELECT 1 AS y) SELECT * FROM (SELECT * FROM (SELECT * FROM secret) a) b"
    ) shouldBe
      Passthrough
    go(
      "WITH a AS (WITH secret AS (SELECT 1 AS y) SELECT * FROM secret) SELECT * FROM t, a"
    ) shouldBe
      Passthrough
  }

  "an inner WITH" should "shadow an outer CTE and leave both unwrapped" in {
    go(
      "WITH secret AS (SELECT 1 AS y) " +
        "SELECT * FROM secret s, (WITH secret AS (SELECT 2 AS y) SELECT * FROM secret) q"
    ) shouldBe Passthrough
  }

  it should "shadow the table only inside its own query" in {
    val sql = rewritten(
      go("SELECT * FROM secret r, (WITH secret AS (SELECT 1 AS y) SELECT * FROM secret) c")
    )
    wraps(sql, "region = 'eu'") shouldBe 1
    sql should include("(SELECT * FROM secret WHERE (region = 'eu')) r")
    sql should include("(WITH secret AS (SELECT 1 AS y) SELECT * FROM secret) c")
  }

  it should "shadow the table only inside its own set-operation arm" in {
    val sql = rewritten(
      go("SELECT id FROM secret UNION ALL (WITH secret AS (SELECT 5 AS id) SELECT id FROM secret)")
    )
    wraps(sql, "region = 'eu'") shouldBe 1
    sql should include("SELECT id FROM secret)")
  }

  "a WITH RECURSIVE self-reference" should "not be wrapped" in {
    go(
      "WITH RECURSIVE secret AS (SELECT 1 AS y UNION ALL SELECT y + 1 FROM secret WHERE y < 3) " +
        "SELECT * FROM secret"
    ) shouldBe Passthrough
  }

  it should "leave the anchor's read of the real table wrapped" in {
    val sql = rewritten(
      go(
        "WITH RECURSIVE secret AS (SELECT id AS y FROM secret UNION ALL " +
          "SELECT y + 1 FROM secret WHERE y < 3) SELECT * FROM secret"
      )
    )
    wraps(sql, "region = 'eu'") shouldBe 1
    sql should include("SELECT id AS y FROM (SELECT * FROM secret WHERE (region = 'eu')) AS secret")
  }

  "a reference to the real table outside the CTE's scope" should "be wrapped" in {
    // A CTE's own body, and an earlier sibling's, read the table.
    val own = rewritten(go("WITH secret AS (SELECT id FROM secret) SELECT * FROM t"))
    wraps(own, "region = 'eu'") shouldBe 1
    val earlier = rewritten(
      go("WITH a AS (SELECT id FROM secret), secret AS (SELECT 1 AS y) SELECT * FROM a")
    )
    wraps(earlier, "region = 'eu'") shouldBe 1
    // A scalar subquery's WITH does not reach the outer query.
    val outer = rewritten(
      go("SELECT (WITH secret AS (SELECT 1 AS y) SELECT max(y) FROM secret) AS m FROM secret")
    )
    wraps(outer, "region = 'eu'") shouldBe 1
    outer should include("FROM (SELECT * FROM secret WHERE (region = 'eu')) AS secret")
  }

  "a qualified reference" should "be wrapped although a CTE has the table's name" in
    Seq(
      "WITH secret AS (SELECT 1 AS y) SELECT * FROM tpch1.secret s",
      "WITH secret AS (SELECT 1 AS y) SELECT * FROM acme_tpch.tpch1.secret s",
      "WITH secret AS (SELECT 1 AS y) SELECT * FROM \"tpch1\".\"secret\" s"
    ).foreach { q =>
      withClue(q)(wraps(rewritten(go(q)), "region = 'eu'") shouldBe 1)
    }

  "a statement reading both the CTE and the real table" should "wrap only the table" in {
    val qualified = rewritten(
      go("WITH secret AS (SELECT 1 AS y) SELECT * FROM secret c, tpch1.secret r")
    )
    wraps(qualified, "region = 'eu'") shouldBe 1
    qualified should include("secret c")
    qualified should include("(SELECT * FROM tpch1.secret WHERE (region = 'eu')) r")
    val body = rewritten(
      go("WITH secret AS (SELECT id FROM secret WHERE id > 0) SELECT * FROM t, secret s")
    )
    wraps(body, "region = 'eu'") shouldBe 1
    body should include(
      "SELECT id FROM (SELECT * FROM secret WHERE (region = 'eu')) AS secret WHERE"
    )
    body should include("FROM t, secret s")
  }

  // ---------- a recursive body opening with a WITH clause of its own ----------

  "a WITH RECURSIVE self-reference in a body with its own WITH" should "not be wrapped" in
    Seq(
      "WITH RECURSIVE secret AS (WITH z AS (SELECT 1) SELECT 0 AS n UNION ALL " +
        "SELECT t.id + 10 FROM t, secret WHERE secret.n < 1) SELECT * FROM secret",
      "WITH RECURSIVE secret AS ((WITH z AS (SELECT 1) SELECT 0 AS n UNION ALL " +
        "SELECT n + 1 FROM secret WHERE n < 3)) SELECT * FROM secret",
      "WITH RECURSIVE secret AS (WITH z AS (SELECT 1) (SELECT 0 AS n UNION ALL " +
        "SELECT n + 1 FROM secret WHERE n < 3)) SELECT * FROM secret",
      "WITH RECURSIVE secret AS (WITH RECURSIVE z AS (SELECT 1) SELECT 0 AS n UNION ALL " +
        "SELECT n + 1 FROM secret WHERE n < 3) SELECT * FROM secret",
      "WITH RECURSIVE secret AS (WITH z AS (SELECT 7 AS q) SELECT 0 AS n UNION ALL " +
        "SELECT n + q FROM secret, z WHERE n < 20) SELECT * FROM secret",
      // The inner clause declares the name too: the anchor reads it, the term the recursive CTE.
      "WITH RECURSIVE secret AS (WITH secret AS (SELECT 50 AS n) SELECT n FROM secret " +
        "UNION ALL SELECT n + 1 FROM secret WHERE n < 52) SELECT * FROM secret",
      "WITH RECURSIVE a AS (SELECT 1 AS k), secret AS (WITH z AS (SELECT 1) SELECT 0 AS n " +
        "UNION ALL SELECT n + 1 FROM secret WHERE n < 3) SELECT * FROM secret"
    ).foreach(q => withClue(q)(go(q) shouldBe Passthrough))

  it should "leave a CTE of the inner clause unwrapped in the term" in {
    go(
      "WITH RECURSIVE r AS (WITH secret AS (SELECT 1 AS y) SELECT 0 AS n UNION ALL " +
        "SELECT n + 1 FROM r, secret WHERE n < 3) SELECT * FROM r"
    ) shouldBe Passthrough
  }

  it should "leave the table wrapped where the body reads it" in
    // The anchor, and the inner clause's own body, read the table.
    Seq(
      "WITH RECURSIVE secret AS (WITH z AS (SELECT 1) SELECT id AS n FROM secret UNION ALL " +
        "SELECT n + 1 FROM secret WHERE n < 3) SELECT * FROM secret",
      "WITH RECURSIVE secret AS (WITH z AS (SELECT count(*) AS q FROM secret) SELECT 0 AS n " +
        "UNION ALL SELECT n + q FROM secret, z WHERE n < 5) SELECT * FROM secret",
      // Not recursive in DuckDB: a BY NAME or EXCEPT top level, or no set operation.
      "WITH RECURSIVE secret AS (WITH z AS (SELECT 1) SELECT 0 AS id UNION ALL BY NAME " +
        "SELECT id FROM secret) SELECT * FROM t",
      "WITH RECURSIVE secret AS (WITH z AS (SELECT 1) SELECT 0 AS id EXCEPT " +
        "SELECT id FROM secret) SELECT * FROM t",
      "WITH RECURSIVE secret AS (WITH z AS (SELECT 1) SELECT id FROM secret) SELECT * FROM t"
    ).foreach { q =>
      withClue(q)(wraps(rewritten(go(q)), "region = 'eu'") shouldBe 1)
    }

  // ---------- CTE spellings JSqlParser reads differently from DuckDB ----------

  /** Each names, to DuckDB, a CTE other than `secret` (checked on the 2.0 CLI), while JSqlParser's
    * own unquoting reads it as `secret`.
    */
  private val unlikeSpellings = List(
    "\"secret\"\"\"",
    "\"\"\"secret\"",
    "`secret\"`",
    "`\"secret`",
    "\"[secret\"",
    "`[secret`",
    "\"secret]\"",
    "`secret]`",
    "\"secret`\"",
    "\"`secret\"",
    "`secret```",
    "```secret`"
  )

  for spelling <- unlikeSpellings do
    s"a CTE spelled $spelling" should "leave the table secret wrapped" in {
      val sql = rewritten(
        go(s"WITH $spelling AS (SELECT 5 AS id, 'q' AS region) SELECT * FROM secret")
      )
      wraps(sql, "region = 'eu'") shouldBe 1
    }

  "a CTE spelled between backticks or double quotes" should "be a CTE as DuckDB reads it" in {
    go("WITH `secret` AS (SELECT 1 AS y) SELECT * FROM t, secret s") shouldBe Passthrough
    go("WITH secret AS (SELECT 1 AS y) SELECT * FROM t, `SECRET` s") shouldBe Passthrough
    go("WITH `Secret` AS (SELECT 1 AS y) SELECT * FROM t, \"secret\" s") shouldBe Passthrough
  }

  // ---------- executed in DuckDB ----------

  /** `t` with a column the policy names (`region`) and `secret`, the row-policy table. */
  private def database(): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn                    = DriverManager.getConnection("jdbc:duckdb:")
    def exec(sql: String): Unit = Using.resource(conn.createStatement())(_.execute(sql)): Unit
    exec("ATTACH ':memory:' AS acme_tpch")
    exec("CREATE SCHEMA acme_tpch.tpch1")
    exec("USE acme_tpch.tpch1")
    exec("CREATE TABLE t (id INTEGER, region VARCHAR)")
    exec("INSERT INTO t VALUES (0, 'eu'), (1, 'us'), (2, 'eu')")
    exec("CREATE TABLE secret (id INTEGER, region VARCHAR)")
    exec("INSERT INTO secret VALUES (10, 'eu'), (11, 'us')")
    conn

  private def rowsOf(conn: Connection, sql: String): List[List[String]] =
    Using.resource(conn.createStatement()) { st =>
      Using.resource(st.executeQuery(sql)) { rs =>
        val n   = rs.getMetaData.getColumnCount
        val out = ListBuffer.empty[List[String]]
        while rs.next() do out += (1 to n).map(i => rs.getString(i)).toList
        out.toList
      }
    }

  private def shipped(sql: String): String = go(sql) match
    case Rewritten(out) => out
    case Passthrough    => sql
    case other          => fail(s"expected Rewritten or Passthrough, got $other")

  "a statement reading a CTE named like a row-policy table" should
    "return rows that do not depend on an earlier table's columns" in
    Using.resource(database()) { conn =>
      val sql   = "WITH secret AS (SELECT 1 AS y) SELECT t.id, s.y FROM t, secret s ORDER BY t.id"
      val ship  = shipped(sql)
      val first = rowsOf(conn, ship)
      first shouldBe List(List("0", "1"), List("1", "1"), List("2", "1"))
      Using.resource(conn.createStatement())(_.execute("UPDATE t SET region = 'us'")): Unit
      rowsOf(conn, ship) shouldBe first
    }

  it should "still filter the real table it reads beside the CTE" in
    Using.resource(database()) { conn =>
      val sql =
        "WITH secret AS (SELECT 1 AS y) SELECT r.id, s.y FROM tpch1.secret r, secret s ORDER BY r.id"
      rowsOf(conn, shipped(sql)) shouldBe List(List("10", "1"))
    }

  "a recursive body with its own WITH" should
    "return rows that do not depend on an earlier table's columns" in
    Using.resource(database()) { conn =>
      val sql =
        "WITH RECURSIVE secret AS (WITH z AS (SELECT 1) SELECT 0 AS n UNION ALL " +
          "SELECT t.id + 10 FROM t, secret WHERE secret.n < 1) SELECT n FROM secret ORDER BY n"
      val ship  = shipped(sql)
      val first = rowsOf(conn, ship)
      first shouldBe List(List("0"), List("10"), List("11"), List("12"))
      Using.resource(conn.createStatement())(_.execute("UPDATE t SET region = 'us'")): Unit
      rowsOf(conn, ship) shouldBe first
    }

  "a CTE spelled so DuckDB reads another name" should "not hide the table from its filter" in
    Using.resource(database()) { conn =>
      val sql = "WITH \"[secret\" AS (SELECT 5 AS id, 'q' AS region) SELECT id FROM secret"
      rowsOf(conn, shipped(sql)) shouldBe List(List("10"))
    }
