package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import scala.collection.mutable.ListBuffer
import scala.util.{Try, Using}

/** A mask binds where its column did (owner ruling D25). The rewriter replaces a reference to a
  * masked column with the stored mask expression; the mask names its column bare, so spliced as
  * stored it bound wherever DuckDB binds that bare name at the reference's place: in a correlated
  * subquery whose own FROM has a column of that name, the inner one, and with two FROM items
  * holding it, neither (DuckDB refuses the ambiguous name). Each column of the mask now reads the
  * reference it replaces, qualifier and quoting included, and a SQL value keyword the mask calls as
  * a function stays the function wherever it is spliced.
  */
class MaskBindingSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val Catalog = "acme_lake"
  private val user    = RbacUser("u-1", Some("acme"), "alice", "user")
  private val ctx     = SchemaContext(Some(Catalog), Some("main"), attachedCatalogs = Set.empty)

  private val columns = Map(
    (Catalog, "main", "lists")  -> List("id", "l"),
    (Catalog, "main", "secret") -> List("id", "region"),
    (Catalog, "main", "t")      -> List("id", "region"),
    (Catalog, "main", "u")      -> List("id", "current_date", "ssn"),
    (Catalog, "main", "o")      -> List("id")
  )
  private val rw = new ColumnPolicyRewriter(new ColumnCatalog.MapCatalog(columns))

  private def policy(table: String, column: String, action: String, mask: Option[String]) =
    RoleColumnPolicy(s"cp-$table-$column", "r-1", "*", "main", table, column, action, mask)

  private val maskRegion = policy("secret", "region", "mask", Some("left(region, 1) || '***'"))
  private val denyInner  = policy("t", "region", "deny", None)

  private def rewrite(sql: String, policies: List[RoleColumnPolicy]): Outcome =
    rw.rewrite(sql, StatementKind.Select, EffectiveSet(user, Nil, Nil, Nil, Nil, policies), ctx)
      .unsafeRunSync()

  private def rewritten(sql: String, policies: List[RoleColumnPolicy]): String =
    rewrite(sql, policies) match
      case Rewritten(out) => out
      case other          => fail(s"$sql: expected Rewritten, got $other")

  private def database(): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    List(
      s"ATTACH ':memory:' AS $Catalog",
      s"USE $Catalog.main",
      "CREATE TABLE secret (id INTEGER, region VARCHAR)",
      "CREATE TABLE t (id INTEGER, region VARCHAR)",
      "CREATE TABLE u (id INTEGER, current_date VARCHAR, ssn VARCHAR)",
      "CREATE TABLE o (id INTEGER)",
      "CREATE TABLE lists (id INTEGER, l VARCHAR[])",
      "INSERT INTO lists VALUES (1, ['a', 'topsecret'])",
      "INSERT INTO secret VALUES (1, 'Secret-EU')",
      "INSERT INTO t VALUES (1, 'Topsecret')",
      "INSERT INTO u VALUES (1, 'cd-secret', 'ssn-secret')",
      "INSERT INTO o VALUES (1)"
    ).foreach(sql => Using.resource(conn.createStatement())(_.execute(sql)))
    conn

  private def exec(conn: Connection, sql: String): Unit =
    Using.resource(conn.createStatement())(_.execute(sql)): Unit

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

  "a mask in a correlated subquery whose FROM has a column of the same name" should
    "read the outer column it replaces, never the inner one" in
    Using.resource(database()) { conn =>
      val sql = "SELECT (SELECT s.region FROM t o LIMIT 1) AS r FROM secret s"
      val out = rewritten(sql, List(maskRegion, denyInner))
      out shouldBe
        "SELECT (SELECT left(s.region, 1) || '***' AS region FROM t o LIMIT 1) AS r FROM secret s"
      rowsOf(conn, out) shouldBe Right(List(List("S***")))
      exec(conn, "UPDATE t SET region = 'Xylophone'")
      rowsOf(conn, out) shouldBe Right(List(List("S***")))
    }

  "a mask on a qualified column beside another FROM item holding that name" should
    "answer, masked" in
    Using.resource(database()) { conn =>
      val out = rewritten("SELECT s.region FROM secret s, t", List(maskRegion))
      out shouldBe "SELECT left(s.region, 1) || '***' AS region FROM secret s, t"
      rowsOf(conn, out) shouldBe Right(List(List("S***")))
    }

  it should "keep a quoted qualifier and a three-part name" in
    Using.resource(database()) { conn =>
      val quoted = rewritten("""SELECT "S"."region" FROM secret "S", t""", List(maskRegion))
      quoted shouldBe """SELECT left("S"."region", 1) || '***' AS "region" FROM secret "S", t"""
      rowsOf(conn, quoted) shouldBe Right(List(List("S***")))
      val full = rewritten(
        s"SELECT $Catalog.main.secret.region FROM $Catalog.main.secret, t",
        List(maskRegion)
      )
      full shouldBe
        s"SELECT left($Catalog.main.secret.region, 1) || '***' AS region " +
        s"FROM $Catalog.main.secret, t"
      rowsOf(conn, full) shouldBe Right(List(List("S***")))
    }

  "a mask naming its time keyword column bare" should "read the column it replaces" in
    Using.resource(database()) { conn =>
      val mask = policy("u", "current_date", "mask", Some("left(current_date, 2) || '***'"))
      val out  = rewritten("SELECT (SELECT u.current_date FROM o LIMIT 1) AS r FROM u", List(mask))
      out shouldBe
        "SELECT (SELECT left(u.current_date, 2) || '***' AS current_date FROM o LIMIT 1) AS r " +
        "FROM u"
      rowsOf(conn, out) shouldBe Right(List(List("cd***")))
    }

  "a mask calling a SQL value function" should "stay the function beside a column of that name" in
    Using.resource(database()) { conn =>
      val mask = policy("u", "ssn", "mask", Some("CAST(current_date AS VARCHAR)"))
      val deny = policy("u", "current_date", "deny", None)
      val out  = rewritten("SELECT ssn FROM u", List(mask, deny))
      out shouldBe "SELECT CAST(current_date() AS VARCHAR) AS ssn FROM u"
      val today = rowsOf(conn, "SELECT CAST(current_date() AS VARCHAR)")
      rowsOf(conn, out) shouldBe today
      rowsOf(conn, out) should not be Right(List(List("cd-secret")))
    }

  "a stored mask reading another column" should "be refused" in {
    val mask = policy("secret", "region", "mask", Some("coalesce(CAST(id AS VARCHAR), '***')"))
    rewrite("SELECT region FROM secret", List(mask)) match
      case Denied(reason) => reason should include("reads another column")
      case other          => fail(s"expected Denied, got $other")
  }

  "a mask on a derived table's column" should "read the derived column it replaces" in
    Using.resource(database()) { conn =>
      val out = rewritten("SELECT d.e FROM (SELECT region AS e FROM secret) d", List(maskRegion))
      out should include("left(d.e, 1) || '***'")
      rowsOf(conn, out).map(_.head.head.endsWith("***")) shouldBe Right(true)
    }

  // ---- lambdas in a mask (review round 2, I1) ------------------------------------------------

  "a lambda mask whose parameter shares its column's name" should
    "keep the parameter and read the reference only where the column is" in
    Using.resource(database()) { conn =>
      val mask = policy("lists", "l", "mask", Some("list_filter(l, lambda l: len(l) < 3)"))
      val out  = rewritten("SELECT lists.l FROM lists", List(mask))
      out shouldBe "SELECT list_filter(lists.l, lambda l : len(l) < 3) AS l FROM lists"
      rowsOf(conn, out) shouldBe Right(List(List("[a]")))
      val arrow = policy("lists", "l", "mask", Some("list_filter(l, l -> len(l) < 3)"))
      rewritten("SELECT lists.l FROM lists", List(arrow)) shouldBe
        "SELECT list_filter(lists.l, l -> len(l) < 3) AS l FROM lists"
    }

  "a lambda mask with a parameter of another name" should "be applied, not refused" in
    Using.resource(database()) { conn =>
      val mask = policy("lists", "l", "mask", Some("list_transform(l, lambda x: concat(x, '*'))"))
      val out  = rewritten("SELECT lists.l FROM lists", List(mask))
      out shouldBe "SELECT list_transform(lists.l, lambda x : concat(x, '*')) AS l FROM lists"
      rowsOf(conn, out) shouldBe Right(List(List("[a*, topsecret*]")))
      val arrow = policy("lists", "l", "mask", Some("list_transform(l, x -> concat(x, '*'))"))
      rewritten("SELECT l FROM lists", List(arrow)) shouldBe
        "SELECT list_transform(l, x -> concat(x, '*')) AS l FROM lists"
    }

  "a stored mask expanding a star or a COLUMNS(..) expression" should "be refused" in
    List("concat(region, COLUMNS('id'))", "to_json(*)", "concat(region, $1)").foreach { text =>
      val mask = policy("secret", "region", "mask", Some(text))
      withClue(text) {
        rewrite("SELECT region FROM secret", List(mask)) match
          case Denied(reason) => reason should include("reads another column")
          case other          => fail(s"expected Denied, got $other")
      }
    }
