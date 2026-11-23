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

/** Column references JSqlParser reads as another node (owner ruling D23, review round 2): a word
  * followed by another token (`key k`, `prior p`, `nextval n`, `connect_by_root r`, `exists e`, the
  * select modifiers `sql_cache c` and kin, `minus` after a comma), a date part as a call's argument
  * (`count(year)`), `interval` followed by another token, and `@x`, DuckDB's absolute value. DuckDB
  * 2.0 reads the column in each; the column's policy must apply, or the statement be refused.
  */
class MisreadColumnSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val Catalog = "acme_lake"
  private val user    = RbacUser("u-1", Some("acme"), "alice", "user")
  private val ctx     = SchemaContext(Some(Catalog), Some("main"), attachedCatalogs = Set.empty)

  /** Words JSqlParser reads as an operator or a modifier when another token follows. */
  private val Words = List(
    "key",
    "prior",
    "nextval",
    "connect_by_root",
    "exists",
    "sql_cache",
    "sql_no_cache",
    "sql_calc_found_rows",
    "high_priority",
    "straight_join",
    "sql_small_result",
    "sql_big_result",
    "sql_buffer_result",
    "distinctrow",
    "minus"
  )
  private val DateParts = List("year", "month", "day", "hour", "minute", "second")

  private val columns = Map(
    (Catalog, "main", "kv") -> (List("id") ++ Words ++ DateParts ++ List("interval", "ssn")),
    (Catalog, "main", "o")  -> List("id"),
    (Catalog, "main", "w")  -> List("v")
  )
  private val catalog = new ColumnCatalog.MapCatalog(columns)
  private val rw      = new ColumnPolicyRewriter(catalog)
  private val strict  = new ColumnPolicyRewriter(catalog, unresolvedMode = UnresolvedMode.Deny)

  private def mask(column: String, sql: String = "'***'") =
    RoleColumnPolicy(s"cp-$column", "r-1", "*", "main", "kv", column, "mask", Some(sql))
  private def deny(column: String) =
    RoleColumnPolicy(s"cd-$column", "r-1", "*", "main", "kv", column, "deny", None)
  private def eff(policies: List[RoleColumnPolicy]) =
    EffectiveSet(user, Nil, Nil, Nil, Nil, policies)

  private def rewrite(
      sql: String,
      policies: List[RoleColumnPolicy],
      rewriter: ColumnPolicyRewriter = rw
  ): Outcome =
    rewriter.rewrite(sql, StatementKind.Select, eff(policies), ctx).unsafeRunSync()

  private def refused(outcome: Outcome): Boolean =
    outcome match
      case Denied(_) | DeniedUnresolvedTable | PassthroughParseFailed => true
      case _                                                          => false

  /** Masked (no raw reference of `word` left but its output alias) or refused. */
  private def held(outcome: Outcome, word: String): Boolean =
    outcome match
      case Rewritten(out) =>
        out.contains("'***'") &&
        !out
          .replaceAll(s"""(?i)\\bAS\\s+"?$word"?""", "")
          .replaceAll(s"""(?i)"$word"\\s+(AS\\s+)?\\w+""", "")
          .matches(s"""(?is).*\\bkv\\.$word\\b.*""")
      case other => refused(other)

  // ---- words followed by another token -------------------------------------------------------

  private def forms(word: String) = List(
    s"SELECT $word k FROM kv",
    s"SELECT $word k, id FROM kv",
    s"SELECT ${word.toUpperCase} k FROM kv",
    s"SELECT $word + 1 AS k FROM kv",
    s"SELECT $word[1] AS k FROM kv",
    s"SELECT id FROM kv ORDER BY $word DESC",
    s"SELECT d.k FROM (SELECT $word k FROM kv) d",
    s"SELECT (SELECT $word k FROM kv LIMIT 1) AS r FROM o"
  )

  "a column named like a keyword followed by another token" should
    "be masked, or the statement refused" in {
      for
        word     <- Words.filterNot(_ == "minus")
        sql      <- forms(word)
        rewriter <- List(rw, strict)
      do
        withClue(s"$sql (mask)")(rewrite(sql, List(mask(word)), rewriter) should not be Passthrough)
        withClue(s"$sql (deny)")(refused(rewrite(sql, List(deny(word)), rewriter)) shouldBe true)
    }

  it should "be masked in the select list, aliased without AS" in
    Words.filterNot(_ == "minus").foreach { word =>
      withClue(word)(
        rewrite(s"SELECT $word k FROM kv", List(mask(word))) shouldBe
          Rewritten(s"SELECT '***' k FROM kv")
      )
    }

  "a column named minus after a comma" should "be masked or refused" in {
    rewrite("SELECT id, minus FROM kv", List(mask("minus"))) shouldBe
      Rewritten("""SELECT id, '***' AS "minus" FROM kv""")
    refused(rewrite("SELECT id, minus FROM kv", List(deny("minus")))) shouldBe true
  }

  "those words" should "keep their meaning where DuckDB reads a keyword or a call" in {
    val p = List(mask("ssn"))
    rewrite("SELECT id FROM kv WHERE EXISTS (SELECT 1 FROM o)", p) shouldBe Passthrough
    rewrite("SELECT id FROM kv WHERE NOT EXISTS (SELECT 1 FROM o)", p) shouldBe Passthrough
    rewrite("SELECT key, prior FROM kv", p) shouldBe Passthrough
    rewrite("SELECT id FROM kv WHERE key = 'a' ORDER BY key", p) shouldBe Passthrough
  }

  // ---- date parts as a call's argument -------------------------------------------------------

  "a column named like a date part" should "be masked or refused as a call's argument" in {
    for
      word <- DateParts
      sql  <- List(
        s"SELECT max($word) AS m FROM kv",
        s"SELECT count(${word.toUpperCase}) AS m FROM kv",
        s"SELECT coalesce(id, $word) AS m FROM kv",
        s"SELECT id FROM kv WHERE length($word) > 1"
      )
      rewriter <- List(rw, strict)
    do
      withClue(s"$sql (mask)")(held(rewrite(sql, List(mask(word)), rewriter), word) shouldBe true)
      withClue(s"$sql (deny)")(refused(rewrite(sql, List(deny(word)), rewriter)) shouldBe true)
  }

  it should "be masked exactly" in {
    rewrite("SELECT max(year) AS m FROM kv", List(mask("year"))) shouldBe
      Rewritten("SELECT max('***') AS m FROM kv")
  }

  it should "leave an interval's unit alone" in {
    // The resolver cannot read an interval literal (a refusal that predates this check); what
    // matters is that no date part of it is read as a column.
    rewrite("SELECT id, INTERVAL 1 DAY AS d FROM kv", List(mask("ssn"))) match
      case Denied(reason) => reason should not include "cannot see"
      case _              => ()
    rewrite(
      "SELECT id FROM kv WHERE EXTRACT(year FROM DATE '2020-01-01') = 1",
      List(mask("year"))
    ) shouldBe
      Passthrough
  }

  // ---- interval followed by another token ----------------------------------------------------

  "a column named interval followed by another token" should "be refused" in
    List("SELECT interval i FROM kv", "SELECT interval + 1 FROM kv", "SELECT interval[1] FROM kv")
      .foreach { sql =>
        withClue(sql) {
          refused(rewrite(sql, List(mask("interval")))) shouldBe true
          refused(rewrite(sql, List(deny("interval")))) shouldBe true
        }
      }

  // ---- @, DuckDB's absolute value ------------------------------------------------------------

  "@ over a column" should "be refused under a column policy" in {
    for
      sql <- List(
        "SELECT @ssn FROM kv",
        "SELECT @kv.ssn FROM kv",
        "SELECT (@ssn) + 0 AS v FROM kv",
        "SELECT 1 FROM kv WHERE @ssn = 42",
        "SELECT id FROM kv ORDER BY @ssn",
        "SELECT f(@ssn) FROM kv"
      )
      policy   <- List(mask("ssn"), deny("ssn"))
      rewriter <- List(rw, strict)
    do
      withClue(s"$sql (${policy.action})")(
        refused(rewrite(sql, List(policy), rewriter)) shouldBe true
      )
  }

  "a star with an alias" should "not read a masked column raw" in {
    held(rewrite("SELECT * k FROM kv", List(mask("ssn"))), "ssn") shouldBe true
    refused(rewrite("SELECT * k FROM kv", List(deny("ssn")))) shouldBe true
  }

  // ---- the parity with an ordinary column ----------------------------------------------------

  private val Templates = List(
    "SELECT {c} FROM kv",
    "SELECT {c} AS x FROM kv",
    "SELECT id FROM kv WHERE {c} = 'x'",
    "SELECT id FROM kv ORDER BY {c}",
    "SELECT max({c}) AS m FROM kv",
    "SELECT count(*) FROM kv GROUP BY {c}",
    "SELECT id FROM o WHERE id IN (SELECT id FROM kv WHERE {c} = 'x')",
    "WITH c AS (SELECT {c} FROM kv) SELECT * FROM c",
    "SELECT x FROM (SELECT {c} AS x FROM kv) s",
    "SELECT CASE WHEN {c} = 'x' THEN 1 END AS k FROM kv"
  )

  "a column named like a keyword" should
    "be treated as an ordinary column, or refused where the resolver cannot print it" in {
      val stricter = ListBuffer.empty[String]
      for
        word   <- Words ++ DateParts
        tpl    <- Templates
        policy <- List((c: String) => mask(c), (c: String) => deny(c))
      do
        val ordinary         = rewrite(tpl.replace("{c}", "ssn"), List(policy("ssn")))
        val keyword          = rewrite(tpl.replace("{c}", word), List(policy(word)))
        def plain(s: String) =
          List("\"" + word + "\"", "\"" + word.toUpperCase + "\"", word)
            .foldLeft(s)(_.replace(_, "ssn"))
        val normalized = keyword match
          case Rewritten(out) => Rewritten(plain(out))
          case Denied(reason) => Denied(plain(reason))
          case other          => other
        // Fail closed: the resolver prints a CTE's output names unquoted, which JSqlParser cannot
        // read back for a few of these words; the router refuses such a statement.
        if refused(keyword) && ordinary != Passthrough then
          if !refused(ordinary) then stricter += tpl.replace("{c}", word)
        else withClue(s"${tpl.replace("{c}", word)}")(normalized shouldBe ordinary)
      info(s"refused where an ordinary column is rewritten: ${stricter.mkString("; ")}")
    }

  // ---- the protected-write guard -------------------------------------------------------------

  "the protected-write guard" should "refuse a write reading one of these columns" in {
    val guard = new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true)
    List(
      "INSERT INTO w SELECT key k FROM kv"     -> mask("key"),
      "INSERT INTO w SELECT max(year) FROM kv" -> mask("year"),
      "INSERT INTO w SELECT @ssn FROM kv"      -> mask("ssn")
    ).foreach { (sql, p) =>
      withClue(sql)(
        guard.check(sql, StatementKind.Dml, eff(List(p)), ctx) shouldBe a[GuardOutcome.Deny]
      )
    }
  }

  // ---- end to end on DuckDB ------------------------------------------------------------------

  private def database(maskedValues: Boolean): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn                    = DriverManager.getConnection("jdbc:duckdb:")
    def exec(sql: String): Unit = Using.resource(conn.createStatement())(_.execute(sql)): Unit
    exec(s"ATTACH ':memory:' AS $Catalog")
    exec(s"USE $Catalog.main")
    exec("CREATE TABLE kv (id INTEGER, key VARCHAR, year VARCHAR, prior VARCHAR)")
    exec("INSERT INTO kv VALUES (1, 'key-secret', '2024', 'prior-secret')")
    if maskedValues then
      exec("""UPDATE kv SET "key" = left("key", 1) || '***', "year" = left("year", 1) || '***'""")
      exec("""UPDATE kv SET "prior" = left("prior", 1) || '***'""")
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

  "a rewritten statement on DuckDB" should "return the masked values these columns hold" in
    Using.resources(database(maskedValues = false), database(maskedValues = true)) { (raw, mk) =>
      val policies = List(
        mask("key", "left(key, 1) || '***'"),
        mask("year", "left(year, 1) || '***'"),
        mask("prior", "left(prior, 1) || '***'")
      )
      List(
        "SELECT key k, id FROM kv",
        "SELECT prior p FROM kv",
        "SELECT max(year) AS m FROM kv",
        "SELECT id FROM kv WHERE key = 'key-secret'"
      ).foreach { sql =>
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
    }
