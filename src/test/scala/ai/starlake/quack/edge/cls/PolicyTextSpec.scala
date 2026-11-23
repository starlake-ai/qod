package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.FlightSqlRouter
import ai.starlake.quack.edge.policy.{GuardOutcome, ProtectedWriteGuard}
import ai.starlake.quack.edge.rls.RowPolicyRewriter
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import scala.collection.mutable.ListBuffer
import scala.util.{Try, Using}

/** One text for every policy step (review round 3): the protected-write guard, the column rewriter
  * and the row rewriter read the statement with every word JSqlParser misreads quoted
  * (`MisreadNames.normalize`), so none of them judges another statement than the others; and a call
  * JSqlParser kept fewer arguments of than its text gives (`list(output ORDER BY id)` parsed as
  * `list()`) is refused, whatever the word.
  */
class PolicyTextSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val Catalog = "acme_lake"
  private val user    = RbacUser("u-1", Some("acme"), "alice", "user")
  private val ctx     = SchemaContext(Some(Catalog), Some("main"), attachedCatalogs = Set.empty)
  private val rctx    = ai.starlake.quack.edge.rls.SchemaContext(Some(Catalog), Some("main"))

  /** Every word the enumeration found (MisreadNames.QuotedWords). */
  private val Words = (MisreadNames.QuotedWords ++ Set("output", "preferring", "xor")).toList.sorted

  private val columns = Map(
    (Catalog, "main", "kv") -> (List("id") ++ Words ++ List("year", "ssn")),
    (Catalog, "main", "w")  -> List("v", "x")
  )
  private val catalog = new ColumnCatalog.MapCatalog(columns)
  private val cls     = new ColumnPolicyRewriter(catalog)
  private val guard   = new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true)
  private val rls     = new RowPolicyRewriter(enabled = true)

  private def deny(column: String) =
    RoleColumnPolicy(s"cd-$column", "r-1", "*", "main", "kv", column, "deny", None)
  private def mask(column: String) =
    RoleColumnPolicy(s"cp-$column", "r-1", "*", "main", "kv", column, "mask", Some("'***'"))
  private val rowPolicy = RoleRowPolicy("rp-1", "r-1", "*", "main", "kv", "id = 1")
  private def eff(cols: List[RoleColumnPolicy], rows: List[RoleRowPolicy] = Nil) =
    EffectiveSet(user, Nil, Nil, Nil, Nil, cols, rows)

  private def refused(outcome: Outcome): Boolean =
    outcome match
      case Denied(_) | DeniedUnresolvedTable | PassthroughParseFailed => true
      case _                                                          => false

  // ---- a dropped argument (R-C2) ---------------------------------------------------------------

  private def aggregateForms(word: String) = List(
    s"SELECT list($word ORDER BY id) AS l FROM kv",
    s"SELECT max($word ORDER BY id DESC) AS m FROM kv",
    s"SELECT array_agg($word ORDER BY id) AS a FROM kv",
    s"SELECT list(DISTINCT $word ORDER BY id) AS l FROM kv",
    s"SELECT id, list($word ORDER BY id) AS l FROM kv GROUP BY id"
  )

  "a column JSqlParser drops before an aggregate's ORDER BY" should "be masked or refused" in {
    for
      word <- Words ++ List("year")
      sql  <- aggregateForms(word)
    do
      withClue(s"$sql (mask)") {
        cls.rewrite(sql, StatementKind.Select, eff(List(mask(word))), ctx).unsafeRunSync() should
          not be Passthrough
      }
      withClue(s"$sql (deny)") {
        refused(
          cls.rewrite(sql, StatementKind.Select, eff(List(deny(word))), ctx).unsafeRunSync()
        ) shouldBe true
      }
  }

  "the argument check" should "refuse a call JSqlParser kept fewer arguments of than its text" in {
    val text = "SELECT list(output ORDER BY id) FROM kv"
    val stmt = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(text)
    ResidualColumns.droppedArgument(text, List(stmt)) shouldBe defined
    val quoted = MisreadNames.normalize(text)
    ResidualColumns.droppedArgument(
      quoted,
      List(net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(quoted))
    ) shouldBe None
  }

  it should "pass the calls JSqlParser reads whole" in
    List(
      "SELECT count(*) FROM kv",
      "SELECT count(DISTINCT id) FROM kv",
      "SELECT string_agg(ssn, ',' ORDER BY id, ssn) FROM kv",
      "SELECT list_reduce([1, 2], lambda x, y: x + y) FROM kv",
      "SELECT list_reduce([1, 2], lambda x, y: x + y, 0) FROM kv",
      "SELECT struct_pack(a := 1, b := 2) FROM kv",
      "SELECT lag(id, 1, 0) OVER (ORDER BY id) FROM kv",
      "SELECT row_number() OVER (ORDER BY id) FROM kv",
      "SELECT count(*) FILTER (WHERE id > 0) FROM kv",
      "SELECT sum(id) FILTER (WHERE id > 0) FROM kv",
      "SELECT substring(ssn, 1, 2) FROM kv",
      "SELECT ssn.upper() FROM kv",
      "SELECT coalesce(ssn, 'x'), now() FROM kv",
      "SELECT \"max\"(id) FROM kv",
      "SELECT list(ssn ORDER BY id LIMIT 2) FROM kv",
      "SELECT 'max(1, 2)' AS s, max(id) FROM kv"
    ).foreach { text =>
      val stmt = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(text)
      withClue(text)(ResidualColumns.droppedArgument(text, List(stmt)) shouldBe None)
    }

  // ---- the write guard reads the normalized text (R-C3) ----------------------------------------

  private def writes(word: String) = List(
    s"INSERT INTO w SELECT id, $word FROM kv",
    s"INSERT INTO w SELECT id, $word FROM kv WHERE id > 0",
    s"CREATE TABLE z AS SELECT id, $word FROM kv",
    s"INSERT INTO w SELECT $word k, id FROM kv",
    s"INSERT INTO w SELECT id, list($word ORDER BY id) FROM kv GROUP BY id"
  )

  "the protected-write guard" should "refuse a write copying out a denied column of any such name" in {
    for
      word <- Words ++ List("year")
      sql  <- writes(word)
    do
      val kind = if sql.startsWith("CREATE") then StatementKind.Ddl else StatementKind.Dml
      withClue(sql)(
        guard.check(sql, kind, eff(List(deny(word))), ctx) shouldBe a[GuardOutcome.Deny]
      )
  }

  // ---- every step reads one text ---------------------------------------------------------------

  private val Statements = Words.flatMap(w =>
    List(s"SELECT id, $w FROM kv", s"SELECT $w k FROM kv", s"SELECT list($w ORDER BY id) FROM kv")
  ) ++ List("SELECT ssn FROM kv WHERE id = $key", "SELECT max(year) FROM kv")

  "the guard, the column rewriter and the row rewriter" should "read one text" in
    Statements.foreach { raw =>
      val text = MisreadNames.normalize(raw)
      withClue(raw) {
        MisreadNames.normalize(text) shouldBe text
        val withPolicies = eff(List(mask("ssn")), List(rowPolicy))
        FlightSqlRouter.policyText(raw, Some(withPolicies)) shouldBe text
        FlightSqlRouter.policyText(raw, Some(eff(Nil))) shouldBe raw
        FlightSqlRouter.policyText(raw, None) shouldBe raw
        guard.check(raw, StatementKind.Dml, withPolicies, ctx) shouldBe
          guard.check(text, StatementKind.Dml, withPolicies, ctx)
        cls.rewrite(raw, StatementKind.Select, withPolicies, ctx).unsafeRunSync() shouldBe
          cls.rewrite(text, StatementKind.Select, withPolicies, ctx).unsafeRunSync()
        rls.rewrite(raw, StatementKind.Select, withPolicies, rctx) shouldBe
          rls.rewrite(text, StatementKind.Select, withPolicies, rctx)
      }
    }

  "the row rewriter" should "keep a column JSqlParser misreads (m-r3)" in
    Using.resource(database()) { conn =>
      List("SELECT id, minus FROM kv", "SELECT list(output ORDER BY id) AS l FROM kv").foreach {
        sql =>
          rls.rewrite(sql, StatementKind.Select, eff(Nil, List(rowPolicy)), rctx) match
            case RowPolicyRewriter.Rewritten(out) =>
              withClue(out)(rowsOf(conn, out) shouldBe rowsOf(conn, filtered(sql)))
            case other => fail(s"$sql: expected Rewritten, got $other")
      }
    }

  /** `sql` over the rows the row policy keeps, written by hand. */
  private def filtered(sql: String): String =
    sql.replace("FROM kv", "FROM (SELECT * FROM kv WHERE id = 1) AS kv")

  private def database(): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    List(
      s"ATTACH ':memory:' AS $Catalog",
      s"USE $Catalog.main",
      "CREATE TABLE kv (id INTEGER, minus VARCHAR, output VARCHAR)",
      "INSERT INTO kv VALUES (1, 'm1', 'o1'), (2, 'm2', 'o2')"
    ).foreach(sql => Using.resource(conn.createStatement())(_.execute(sql)))
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

  // ---- named parameters (m-r2) -----------------------------------------------------------------

  "a named parameter called like a quoted word" should "be left as written" in {
    MisreadNames.quoteWords("SELECT ssn FROM kv WHERE id = $key") shouldBe
      "SELECT ssn FROM kv WHERE id = $key"
    MisreadNames.quoteWords("SELECT ssn FROM kv WHERE id = $prior AND x = :minus") shouldBe
      "SELECT ssn FROM kv WHERE id = $prior AND x = :minus"
    cls
      .rewrite(
        "SELECT ssn FROM kv WHERE id = $key",
        StatementKind.Select,
        eff(List(mask("ssn"))),
        ctx
      )
      .unsafeRunSync() shouldBe Rewritten("SELECT '***' AS ssn FROM kv WHERE id = $key")
  }
