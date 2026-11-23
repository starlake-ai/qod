package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import scala.util.{Try, Using}

/** A result column's name never carries a mask. DuckDB names an unaliased
  * select item after its expression, so an item holding a masked column was named after the mask's
  * text, salt included. Every item the rewriter changes and the caller left unaliased is now named
  * after the item as it read before the rewrite, wherever a name can reach the client: an item at
  * any depth that an outer star shows, a set operation's first arm, a CTE or derived table (whose
  * column list, when written, still wins), DISTINCT ON, a scalar subquery, the FlightSQL prepare
  * probe. Each statement is run on an in-process DuckDB to read the names it really answers with.
  */
class MaskResultNameSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val Catalog = "acme_lake"
  private val Salt    = "pepper9f2"
  private val user    = RbacUser("u-1", Some("acme"), "alice", "user")
  private val ctx     = SchemaContext(Some(Catalog), Some("main"), attachedCatalogs = Set.empty)

  private val columns = Map(
    (Catalog, "main", "t") -> List("id", "email", "name", "n"),
    (Catalog, "main", "u") -> List("id", "email"),
    ("other", "main", "t") -> List("id", "email", "name", "n")
  )
  private val rw = new ColumnPolicyRewriter(new ColumnCatalog.MapCatalog(columns))

  private val mask = RoleColumnPolicy(
    "cp-t-email",
    "r-1",
    "*",
    "main",
    "t",
    "email",
    "mask",
    Some(s"concat('$Salt', md5(email))")
  )

  private def rewrite(sql: String): Outcome =
    rw.rewrite(sql, StatementKind.Select, EffectiveSet(user, Nil, Nil, Nil, Nil, List(mask)), ctx)
      .unsafeRunSync()

  private def rewritten(sql: String): String =
    rewrite(sql) match
      case Rewritten(out) => out
      case other          => fail(s"$sql: expected Rewritten, got $other")

  private def database(): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    List(
      s"ATTACH ':memory:' AS $Catalog",
      "ATTACH ':memory:' AS other",
      "CREATE TABLE other.main.t (id INTEGER, email VARCHAR, name VARCHAR, n INTEGER)",
      "INSERT INTO other.main.t VALUES (2, 'b@x.com', 'Ann', 1)",
      s"USE $Catalog.main",
      "CREATE TABLE t (id INTEGER, email VARCHAR, name VARCHAR, n INTEGER)",
      "CREATE TABLE u (id INTEGER, email VARCHAR)",
      "INSERT INTO t VALUES (1, 'a@x.com', 'Bob', 3)",
      "INSERT INTO u VALUES (1, 'u@x.com')"
    ).foreach(sql => Using.resource(conn.createStatement())(_.execute(sql)))
    conn

  private def namesOf(conn: Connection, sql: String): List[String] =
    Try {
      Using.resource(conn.createStatement()) { st =>
        Using.resource(st.executeQuery(sql)) { rs =>
          val m = rs.getMetaData
          (1 to m.getColumnCount).map(m.getColumnLabel).toList
        }
      }
    }.fold(e => fail(s"$sql: ${e.getMessage}"), identity)

  /** The names the rewritten `sql` answers with, after checking none holds the mask. */
  private def maskedNames(conn: Connection, sql: String): List[String] =
    val out = rewritten(sql)
    out should include(Salt) // the statement was masked
    val names = namesOf(conn, out)
    names.foreach(n => withClue(s"$sql -> $out: ")(n should not include Salt))
    names

  "a changed select item" should "be named after the item as written, never the mask" in
    Using.resource(database()) { conn =>
      maskedNames(conn, "SELECT lower(email) FROM t") shouldBe List("lower(email)")
      maskedNames(conn, "SELECT upper(lower(email)) FROM t") shouldBe List("upper(lower(email))")
      maskedNames(conn, "SELECT CAST(email AS VARCHAR) FROM t") shouldBe
        List("CAST(email AS VARCHAR)")
      maskedNames(conn, "SELECT email || 'x' FROM t") shouldBe List("email || 'x'")
      maskedNames(conn, "SELECT CASE WHEN n > 1 THEN email ELSE 'z' END FROM t") shouldBe
        List("CASE WHEN n > 1 THEN email ELSE 'z' END")
      maskedNames(conn, "SELECT coalesce(email, 'q') FROM t") shouldBe List("coalesce(email, 'q')")
      maskedNames(conn, "SELECT   lower( email )   FROM t") shouldBe List("lower(email)")
      maskedNames(conn, "SELECT lower(t.email) FROM t") shouldBe List("lower(t.email)")
      maskedNames(conn, "SELECT 'it''s \"q\" ' || email FROM t") shouldBe
        List("'it''s \"q\" ' || email")
      // A line break, which no quoted identifier the parser reads may hold, reads as a space.
      maskedNames(conn, "SELECT email || 'a\nb' FROM t") shouldBe List("email || 'a b'")
    }

  it should "keep a bare column's own name and the caller's alias" in
    Using.resource(database()) { conn =>
      maskedNames(conn, "SELECT email FROM t") shouldBe List("email")
      maskedNames(conn, "SELECT t.email FROM t") shouldBe List("email")
      maskedNames(conn, "SELECT lower(email) AS e, email AS f FROM t") shouldBe List("e", "f")
    }

  "an item the rewriter did not change" should "keep DuckDB's own name" in
    Using.resource(database()) { conn =>
      val sql = "SELECT id, lower(name), n + 1, count(*) OVER (), lower(email) FROM t"
      val own = namesOf(conn, sql)
      maskedNames(conn, sql) shouldBe own.init :+ "lower(email)"
      val grouped = "SELECT lower(email), count(*) FROM t GROUP BY lower(email) ORDER BY 1"
      maskedNames(conn, grouped) shouldBe List("lower(email)", namesOf(conn, grouped)(1))
    }

  "a derived table or CTE an outer star shows" should "show the item's name" in
    Using.resource(database()) { conn =>
      maskedNames(conn, "SELECT * FROM (SELECT lower(email) FROM t) s") shouldBe
        List("lower(email)")
      maskedNames(conn, "SELECT s.* FROM (SELECT id, lower(email) FROM t) s") shouldBe
        List("id", "lower(email)")
      // A CTE's output is read by the name a caller without policies would read it by.
      maskedNames(
        conn,
        "WITH c AS (SELECT lower(email) FROM t) SELECT c.\"lower(email)\" FROM c"
      ) shouldBe List("lower(email)")
      // A star over a CTE is expanded by the column resolver under a name of its own for an
      // unaliased expression, which DuckDB does not bind (as before this change): whatever the
      // statement answers, no name of it holds the mask.
      val star = rewritten("WITH c AS (SELECT lower(email) FROM t) SELECT * FROM c")
      Try(namesOf(conn, star)).toOption.toList.flatten.foreach(_ should not include Salt)
      maskedNames(
        conn,
        "SELECT * FROM (SELECT * FROM (SELECT md5(email) FROM t) a) b"
      ) shouldBe List("md5(email)")
    }

  it should "keep the column list the caller wrote" in
    Using.resource(database()) { conn =>
      maskedNames(conn, "WITH c(x) AS (SELECT lower(email) FROM t) SELECT x FROM c") shouldBe
        List("x")
      maskedNames(conn, "SELECT * FROM (SELECT lower(email) FROM t) s(y)") shouldBe List("y")
    }

  "a set operation" should "take its names from the first arm, named as written" in
    Using.resource(database()) { conn =>
      maskedNames(conn, "SELECT lower(email) FROM t UNION ALL SELECT lower(email) FROM u") shouldBe
        List("lower(email)")
      maskedNames(conn, "SELECT email || '' FROM u UNION ALL SELECT email || '' FROM t") shouldBe
        namesOf(conn, "SELECT email || '' FROM u")
      maskedNames(
        conn,
        "SELECT * FROM (SELECT lower(email) FROM t EXCEPT SELECT lower(email) FROM u) s"
      ) shouldBe List("lower(email)")
    }

  "a scalar subquery item" should "be named as written" in
    Using.resource(database()) { conn =>
      maskedNames(conn, "SELECT (SELECT max(lower(email)) FROM t)") shouldBe
        List("(SELECT max(lower(email)) FROM t)")
    }

  "DISTINCT ON and a star" should "name the masked items as written or by their column" in
    Using.resource(database()) { conn =>
      maskedNames(conn, "SELECT DISTINCT ON (id) lower(email) FROM t ORDER BY id") shouldBe
        List("lower(email)")
      maskedNames(conn, "SELECT * FROM t") shouldBe List("id", "email", "name", "n")
      maskedNames(conn, "SELECT * EXCLUDE (name) FROM t") shouldBe List("id", "email", "n")
      maskedNames(conn, "SELECT * REPLACE (lower(email) AS email) FROM t") shouldBe
        List("id", "email", "name", "n")
    }

  "COLUMNS(..) over a masked table" should "never answer a name holding the mask" in
    Using.resource(database()) { conn =>
      List("SELECT COLUMNS('e.*') FROM t", "SELECT lower(COLUMNS('e.*')) FROM t").foreach { sql =>
        rewrite(sql) match
          case Rewritten(out) => namesOf(conn, out).foreach(_ should not include Salt)
          case other          => other should not be Passthrough
      }
    }

  "the FlightSQL prepare probe" should "announce the item's name" in
    Using.resource(database()) { conn =>
      maskedNames(
        conn,
        "SELECT * FROM (SELECT lower(email), id FROM t) AS _qod_probe LIMIT 0"
      ) shouldBe List("lower(email)", "id")
    }

  "a pinned read in a select item" should "be named with the caller's clause, never a placeholder" in {
    val out = rewritten("SELECT (SELECT lower(email) FROM t AT (VERSION => 3)) FROM u")
    out should include("AS \"(SELECT lower(email) FROM t AT (VERSION => 3))\"")
    out should not include "__qod_time_travel_"
  }

  "an item reading one of two tables of one name" should "be named as the caller wrote it" in
    Using.resource(database()) { conn =>
      val names = maskedNames(
        conn,
        "SELECT (SELECT max(lower(email)) FROM other.main.t), x.email FROM acme_lake.main.t x"
      )
      names shouldBe List("(SELECT max(lower(email)) FROM other.main.t)", "email")
    }

  "the name DuckDB gives an item and the one the rewriter gives it" should "agree on calls" in
    Using.resource(database()) { conn =>
      // The forms where the two agree; the report lists every form compared, with the ones that
      // differ (operators and CASE gain parentheses in DuckDB's name, keywords change case).
      List(
        "lower(email)",
        "upper(lower(email))",
        "CAST(email AS VARCHAR)",
        "substr(email, 1, 2)",
        "md5(email)"
      ).foreach { item =>
        val sql = s"SELECT $item FROM t"
        maskedNames(conn, sql) shouldBe namesOf(conn, sql)
      }
      val compared = List(
        "lower(email)",
        "LOWER( email )",
        "upper(lower(email))",
        "CAST(email AS VARCHAR)",
        "email::VARCHAR",
        "TRY_CAST(email AS INTEGER)",
        "email || 'x'",
        "CASE WHEN n > 1 THEN email ELSE 'z' END",
        "coalesce(email, 'q')",
        "lower(t.email)",
        "lower(\"email\")",
        "email.lower()",
        "email LIKE '%x%'",
        "email IS NULL",
        "length(email) > 3",
        "(SELECT max(email) FROM t)",
        "count(email)",
        "max(lower(email))"
      )
      compared.foreach { item =>
        val sql  = s"SELECT $item FROM t"
        val own  = namesOf(conn, sql)
        val ours = maskedNames(conn, sql)
        info(s"NAMECMP | $item | ${own.mkString} | ${ours.mkString} | ${own == ours}")
      }
    }
