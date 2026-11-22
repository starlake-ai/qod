package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.policy.{GuardOutcome, ProtectedWriteGuard}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import scala.collection.mutable.ListBuffer
import scala.util.{Try, Using}

/** Column policies on a column named like a SQL value keyword (owner ruling D23). JSqlParser reads
  * the bare words `current_date`, `current_time` and `current_timestamp` as a `TimeKeyExpression`,
  * and `current` and `current_timezone` too, never as a column. DuckDB 2.0 binds each of DuckDB's
  * SQL value names (those three and `current_user`, `session_user`, `user`, `current_role`,
  * `current_schema`, `current_catalog`, `localtime`, `localtimestamp`), quoted or not, to a column
  * of the select's own FROM that has it, and to the function otherwise, never to an enclosing
  * query's column; `current` and `current_timezone` are plain identifiers there. The gateway must
  * read every one of them as DuckDB does.
  */
class SqlValueNameColumnSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val Catalog = "acme_lake"
  private val user    = RbacUser("u-1", Some("acme"), "alice", "user")
  private val ctx     = SchemaContext(Some(Catalog), Some("main"), attachedCatalogs = Set.empty)

  private val TimeKeys = List("current_date", "current_time", "current_timestamp")
  private val Specials = List(
    "current_user",
    "session_user",
    "user",
    "current_role",
    "current_schema",
    "current_catalog",
    "localtime",
    "localtimestamp"
  )

  private val columns = Map(
    (Catalog, "main", "u") -> (List("id") ++ TimeKeys ++ List(
      "current",
      "current_timezone",
      "ssn"
    )),
    (Catalog, "main", "o")   -> List("id"),
    (Catalog, "main", "cur") -> List("id", "current", "current_timezone"),
    (Catalog, "main", "sp")  -> ("id" :: Specials),
    (Catalog, "main", "w")   -> List("v")
  )
  private val catalog = new ColumnCatalog.MapCatalog(columns)
  private val rw      = new ColumnPolicyRewriter(catalog)
  private val strict  = new ColumnPolicyRewriter(catalog, unresolvedMode = UnresolvedMode.Deny)

  private def mask(table: String, column: String, sql: String = "'***'") =
    RoleColumnPolicy(s"cp-$table-$column", "r-1", "*", "main", table, column, "mask", Some(sql))
  private def deny(table: String, column: String) =
    RoleColumnPolicy(s"cd-$table-$column", "r-1", "*", "main", table, column, "deny", None)

  private def eff(policies: List[RoleColumnPolicy]) =
    EffectiveSet(user, Nil, Nil, Nil, Nil, policies)

  private def rewrite(
      sql: String,
      policies: List[RoleColumnPolicy],
      rewriter: ColumnPolicyRewriter = rw
  ): Outcome =
    rewriter.rewrite(sql, StatementKind.Select, eff(policies), ctx).unsafeRunSync()

  private def masked(sql: String, policies: List[RoleColumnPolicy]): String =
    rewrite(sql, policies) match
      case Rewritten(out) => out
      case other          => fail(s"$sql: expected Rewritten, got $other")

  private def spellings(word: String) =
    List(word, word.toUpperCase, word.split('_').map(_.capitalize).mkString("_"))

  // ---- the three TimeKeyExpression words -----------------------------------------------------

  "a masked column named current_date, current_time or current_timestamp" should
    "be masked when read bare, in any case" in
    TimeKeys.foreach { word =>
      spellings(word).foreach { spelled =>
        withClue(spelled) {
          masked(s"SELECT $spelled FROM u", List(mask("u", word))) shouldBe
            s"""SELECT '***' AS "$spelled" FROM u"""
        }
      }
    }

  it should "be masked when read qualified, as before" in
    TimeKeys.foreach { word =>
      masked(s"SELECT u.$word FROM u", List(mask("u", word))) shouldBe
        s"SELECT '***' AS $word FROM u"
    }

  it should "be masked in WHERE, ORDER BY, GROUP BY, HAVING and an aggregate" in
    TimeKeys.foreach { word =>
      val p = List(mask("u", word))
      withClue(word) {
        masked(s"SELECT id FROM u WHERE $word = 'x'", p) shouldBe
          "SELECT id FROM u WHERE '***' = 'x'"
        masked(s"SELECT id FROM u ORDER BY $word", p) shouldBe "SELECT id FROM u ORDER BY '***'"
        masked(s"SELECT max($word) AS m FROM u", p) shouldBe "SELECT max('***') AS m FROM u"
        masked(s"SELECT count(*) FROM u GROUP BY $word", p) shouldBe
          "SELECT count(*) FROM u GROUP BY '***'"
        masked(s"SELECT count(*) FROM u HAVING max($word) = 'x'", p) shouldBe
          "SELECT count(*) FROM u HAVING max('***') = 'x'"
      }
    }

  /** Statements reading `{c}` of `u` in every clause the column rewriter models. */
  private val Templates = List(
    "SELECT {c} FROM u",
    "SELECT u.{c} FROM u",
    "SELECT {c} AS x FROM u",
    "SELECT id FROM u WHERE {c} = 'x'",
    "SELECT id FROM u WHERE {c} IS NULL OR {c} LIKE 'a%'",
    "SELECT id FROM u ORDER BY {c}",
    "SELECT max({c}) AS m FROM u",
    "SELECT count(*) FROM u GROUP BY {c}",
    "SELECT count(*) FROM u HAVING max({c}) = 'x'",
    "SELECT id FROM u QUALIFY row_number() OVER (ORDER BY {c}) = 1",
    "SELECT row_number() OVER (PARTITION BY {c}) AS r FROM u",
    "SELECT o.id FROM o JOIN u ON u.{c} = 'x'",
    "SELECT o.id FROM o JOIN u ON {c} = 'x'",
    "SELECT id FROM o WHERE id IN (SELECT id FROM u WHERE {c} = 'x')",
    "SELECT id FROM o WHERE EXISTS (SELECT 1 FROM u WHERE {c} = 'x')",
    "WITH c AS (SELECT {c} FROM u) SELECT * FROM c",
    "WITH c AS (SELECT id FROM u WHERE {c} = 'x') SELECT * FROM c",
    "SELECT (SELECT max({c}) FROM u) AS m FROM o",
    "SELECT x FROM (SELECT {c} AS x FROM u) s",
    "SELECT {c} FROM (SELECT {c} FROM u) s",
    "SELECT {c} FROM u, o",
    "SELECT CASE WHEN {c} = 'x' THEN 1 END AS k FROM u",
    "SELECT CAST({c} AS VARCHAR) AS v FROM u",
    "SELECT {c} || 'x' AS v FROM u",
    "SELECT id FROM u WHERE {c} IN ('a', 'b')",
    "SELECT id FROM u WHERE {c} BETWEEN 'a' AND 'b'"
  )

  /** `outcome` with `word` written as the ordinary column `ssn`. */
  private def asSsn(outcome: Outcome, word: String): Outcome =
    def plain(s: String) =
      List("\"" + word + "\"", word, word.toUpperCase).foldLeft(s)(_.replace(_, "ssn"))
    outcome match
      case Rewritten(sql) => Rewritten(plain(sql))
      case Denied(reason) => Denied(plain(reason))
      case other          => other

  "a column named like a time keyword" should
    "be treated as an ordinary column in every clause, masked or denied" in
    (TimeKeys ++ List("current", "current_timezone")).foreach { word =>
      Templates.foreach { tpl =>
        List(mask(_, _), (t: String, c: String) => deny(t, c)).foreach { policy =>
          val keyword  = tpl.replace("{c}", word)
          val ordinary = tpl.replace("{c}", "ssn")
          withClue(s"$keyword vs $ordinary") {
            val expected = rewrite(ordinary, List(policy("u", "ssn")))
            expected should not be Passthrough
            asSsn(rewrite(keyword, List(policy("u", word))), word) shouldBe expected
          }
        }
      }
    }

  it should "be masked when the FROM list holds another table too" in
    TimeKeys.foreach { word =>
      masked(s"SELECT $word FROM u, o", List(mask("u", word))) shouldBe
        s"""SELECT '***' AS "$word" FROM u, o"""
    }

  "a star over a column named like a time keyword" should "mask it as it masks any column" in
    (TimeKeys ++ List("current", "current_timezone")).foreach { word =>
      withClue(word) {
        val out = masked("SELECT * FROM u", List(mask("u", word)))
        out should include(s"'***' AS $word,")
        out should not include s" u.$word,"
        rewrite("SELECT * FROM u", List(deny("u", word))) shouldBe a[Denied]
        masked(s"SELECT * EXCLUDE (ssn) FROM u", List(mask("u", word))) should include("'***'")
      }
    }

  "a denied column named like a time keyword" should "refuse the statement" in
    TimeKeys.foreach { word =>
      List(
        s"SELECT $word FROM u",
        s"SELECT id FROM u WHERE $word = 'x'",
        s"SELECT id FROM u ORDER BY $word",
        s"SELECT max(${word.toUpperCase}) FROM u"
      ).foreach { sql =>
        withClue(sql) {
          rewrite(sql, List(deny("u", word))) match
            case Denied(reason) =>
              reason.toLowerCase should
                (include(s"u.$word is denied") or include(s"u.\"$word\" is denied"))
            case other => fail(s"expected Denied, got $other")
        }
      }
    }

  "a time keyword with no such column in its own FROM" should "stay the function, unchanged" in
    TimeKeys.foreach { word =>
      val p = List(mask("u", word))
      withClue(word) {
        rewrite(s"SELECT $word FROM o", p) shouldBe Passthrough
        rewrite(s"SELECT $word", p) shouldBe Passthrough
        // DuckDB binds the name to its own FROM only: here it is the function, never u's column.
        rewrite(s"SELECT (SELECT $word FROM o) AS t FROM u", p) shouldBe Passthrough
        rewrite(s"SELECT id FROM u WHERE EXISTS (SELECT 1 FROM o WHERE $word IS NULL)", p) shouldBe
          Passthrough
        // A wildcard policy on the name does not make the function a column either.
        rewrite(s"SELECT $word FROM o", List(mask("*", word))) shouldBe Passthrough
        rewrite(s"SELECT $word FROM o", List(deny("*", word))) shouldBe Passthrough
        rewrite(s"SELECT $word FROM o", p, strict) shouldBe Passthrough
      }
    }

  it should "keep a call with parentheses a call" in {
    rewrite("SELECT current_date() FROM u", List(mask("u", "current_date"))) shouldBe Passthrough
  }

  // ---- current and current_timezone: plain identifiers in DuckDB -------------------------------

  "a masked column named current or current_timezone" should "be masked wherever it binds" in
    List("current", "current_timezone").foreach { word =>
      val p = List(mask("cur", word))
      withClue(word) {
        masked(s"SELECT $word FROM cur", p) shouldBe s"""SELECT '***' AS "$word" FROM cur"""
        masked(s"SELECT id FROM cur WHERE ${word.toUpperCase} = 'x'", p) shouldBe
          "SELECT id FROM cur WHERE '***' = 'x'"
        // A plain identifier binds the enclosing query's column too, which the rewriter refuses
        // there as it refuses an ordinary column (an unqualified correlated reference).
        rewrite(s"SELECT (SELECT $word FROM o) AS t FROM cur", p) shouldBe a[Denied]
        rewrite(s"SELECT (SELECT ssn FROM o) AS t FROM u", List(mask("u", "ssn"))) shouldBe
          a[Denied]
        rewrite(s"SELECT $word FROM cur", List(deny("cur", word))) match
          case Denied(reason) => reason should include(s"cur.$word is denied")
          case other          => fail(s"expected Denied, got $other")
      }
    }

  "current followed by a bare word" should "read as the column current under that alias" in {
    // DuckDB reads `current date` as `current AS date`; JSqlParser as one time keyword.
    masked("SELECT current date FROM cur", List(mask("cur", "current"))) shouldBe
      "SELECT '***' date FROM cur"
    masked("SELECT CURRENT   timestamp FROM cur", List(mask("cur", "current"))) shouldBe
      "SELECT '***' timestamp FROM cur"
  }

  // ---- the other SQL value names, which JSqlParser already reads as columns -------------------

  "a masked column named like another SQL value function" should "be masked in its own FROM" in
    Specials.foreach { word =>
      withClue(word) {
        masked(s"SELECT $word FROM sp", List(mask("sp", word))) shouldBe
          s"SELECT '***' AS $word FROM sp"
      }
    }

  it should "leave the function alone in a subquery, as DuckDB binds no outer column there" in
    Specials.foreach { word =>
      withClue(word) {
        rewrite(s"SELECT (SELECT $word FROM o) AS t FROM sp", List(mask("sp", word))) shouldBe
          Passthrough
      }
    }

  // ---- the backstop --------------------------------------------------------------------------

  "the residual check" should "refuse a time keyword that binds a covered column" in
    TimeKeys.foreach { word =>
      val policies = List(mask("u", word))
      val schema   = Map("u" -> columns((Catalog, "main", "u")), "o" -> List("id"))
      List(s"SELECT $word FROM u", s"SELECT id FROM u WHERE $word = 'x'").foreach { sql =>
        val sel      = ai.starlake.sql.JSqlStatements.parseFirst(sql)
        val suspects = ResidualColumns.suspects(sel, policies, schema)
        withClue(sql)(ResidualColumns.unmasked(sel, suspects, new Applied()) shouldBe defined)
      }
      val free     = ai.starlake.sql.JSqlStatements.parseFirst(s"SELECT $word FROM o")
      val suspects = ResidualColumns.suspects(free, policies, schema)
      ResidualColumns.unmasked(free, suspects, new Applied()) shouldBe None
    }

  // ---- the protected-write guard -------------------------------------------------------------

  "the protected-write guard" should "refuse a write reading a masked time keyword column" in {
    val guard = new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true)
    TimeKeys.foreach { word =>
      withClue(word) {
        guard.check(
          s"INSERT INTO w SELECT $word FROM u",
          StatementKind.Dml,
          eff(List(mask("u", word))),
          ctx
        ) shouldBe a[GuardOutcome.Deny]
        guard.check(
          s"INSERT INTO w SELECT $word FROM o",
          StatementKind.Dml,
          eff(List(mask("u", word))),
          ctx
        ) shouldBe GuardOutcome.Allow
      }
    }
  }

  // ---- the other policy steps ----------------------------------------------------------------

  "the rewrite of a time keyword column" should "reach nothing the original statement does not" in {
    val config = ai.starlake.acl.model.Config.forDuckDB(Some(Catalog), Some("main"), Set.empty)
    (TimeKeys ++ List("current", "current_timezone")).foreach { word =>
      val sql = s"SELECT $word, id FROM u WHERE $word = 'x' ORDER BY $word"
      val out = masked(sql, List(mask("u", word)))
      withClue(out)(
        ai.starlake.quack.edge.policy.RewriteScope
          .reachesBeyond(sql, out, config, Catalog, clientDataScans = false) shouldBe None
      )
    }
  }

  "a table named like a time keyword read as a column" should
    "be refused under a column policy, as DuckDB reads its whole row" in {
      val tables = new ColumnCatalog.MapCatalog(
        columns + ((Catalog, "main", "current_date") -> List("id", "s"))
      )
      val rewriter = new ColumnPolicyRewriter(tables)
      List("SELECT current_date FROM current_date", "SELECT CURRENT_DATE FROM main.current_date")
        .foreach { sql =>
          withClue(sql) {
            rewrite(sql, List(mask("current_date", "s")), rewriter) match
              case Denied(reason) => reason should include("whole row")
              case other          => fail(s"expected Denied, got $other")
          }
        }
      // Under another alias DuckDB reads the function, while the check reads any table by its own
      // name too, aliased or not: refused, as `SELECT customer FROM customer c` is.
      rewrite(
        "SELECT current_date FROM current_date c",
        List(mask("current_date", "s")),
        rewriter
      ) shouldBe a[Denied]
    }

  "the row filter" should "filter a table read whole through a time keyword" in {
    val rls = new ai.starlake.quack.edge.rls.RowPolicyRewriter(enabled = true)
    val p   = ai.starlake.quack.ondemand.state
      .RoleRowPolicy("rp-1", "r-1", "*", "main", "current_date", "id = 1")
    val eff = EffectiveSet(user, Nil, Nil, Nil, Nil, Nil, List(p))
    val out = rls.rewrite(
      "SELECT current_date FROM current_date",
      StatementKind.Select,
      eff,
      ai.starlake.quack.edge.rls.SchemaContext(Some(Catalog), Some("main"))
    ) match
      case ai.starlake.quack.edge.rls.RowPolicyRewriter.Rewritten(sql) => sql
      case other => fail(s"expected Rewritten, got $other")
    Using.resource(database(maskedValues = false)) { conn =>
      Using.resource(conn.createStatement()) { st =>
        st.execute("CREATE TABLE current_date (id INTEGER, s VARCHAR)")
        st.execute("INSERT INTO current_date VALUES (1, 'kept'), (2, 'filtered')")
      }
      withClue(out)(rowsOf(conn, out) shouldBe Right(List(List("{'id': 1, 's': kept}"))))
    }
  }

  // ---- end to end on DuckDB ------------------------------------------------------------------

  private val Rows = List((1, "2020-01-01", "ct-secret", "cts-secret"), (2, "2021-06-30", "x", "y"))

  private def database(maskedValues: Boolean): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn                    = DriverManager.getConnection("jdbc:duckdb:")
    def exec(sql: String): Unit = Using.resource(conn.createStatement())(_.execute(sql)): Unit
    exec(s"ATTACH ':memory:' AS $Catalog")
    exec(s"USE $Catalog.main")
    exec(
      "CREATE TABLE u (id INTEGER, current_date VARCHAR, current_time VARCHAR, " +
        "current_timestamp VARCHAR, ssn VARCHAR)"
    )
    exec("CREATE TABLE o (id INTEGER)")
    Rows.foreach((id, d, t, ts) => exec(s"INSERT INTO u VALUES ($id, '$d', '$t', '$ts', 's')"))
    exec("INSERT INTO o VALUES (1)")
    if maskedValues then
      exec(s"""UPDATE u SET "current_date" = left("current_date", 2) || '***'""")
      exec(s"""UPDATE u SET "current_time" = left("current_time", 1) || '***'""")
      exec(s"""UPDATE u SET "current_timestamp" = left("current_timestamp", 1) || '***'""")
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

  "a rewritten statement on DuckDB" should "return the masked values a keyword column holds" in
    Using.resources(database(maskedValues = false), database(maskedValues = true)) { (raw, mk) =>
      val policies = List(
        mask("u", "current_date", "left(current_date, 2) || '***'"),
        mask("u", "current_time", "left(current_time, 1) || '***'"),
        mask("u", "current_timestamp", "left(current_timestamp, 1) || '***'")
      )
      val cases = List(
        "SELECT id, current_date, current_time, current_timestamp FROM u ORDER BY id",
        "SELECT id FROM u WHERE current_date = '2020-01-01' ORDER BY id",
        "SELECT id FROM u WHERE CURRENT_TIME = 'ct-secret' ORDER BY id",
        "SELECT id FROM u ORDER BY current_timestamp, id",
        "SELECT max(current_date) FROM u",
        "SELECT o.id FROM o JOIN u ON u.current_date LIKE '2020%' ORDER BY 1",
        "SELECT id FROM u WHERE current_date LIKE '2020%' OR current_time LIKE 'c%' ORDER BY 1",
        "SELECT (SELECT current_date FROM o) IS NOT NULL AS d FROM u ORDER BY id"
      )
      cases.foreach { sql =>
        val expected = rowsOf(mk, sql)
        val out      = rewrite(sql, policies) match
          case Rewritten(s) => s
          case Passthrough  => sql
          case other        => fail(s"$sql: $other")
        withClue(s"$sql\n -> $out\n") {
          expected.isRight shouldBe true
          rowsOf(raw, out) shouldBe expected
        }
      }
      // The raw value is what the original statement reads on its own.
      rowsOf(raw, "SELECT current_date FROM u WHERE id = 1") shouldBe Right(
        List(List("2020-01-01"))
      )
    }
