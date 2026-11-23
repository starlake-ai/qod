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

/** DuckDB's method-call syntax read as DuckDB reads it (owner ruling D28, review round 4): each
  * method call is written as the plain call DuckDB makes of it before any policy step parses the
  * statement, a call that cannot be written so with certainty is refused, and a call left in method
  * form after that is refused by the backstop.
  */
class MethodCallSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val Catalog = "acme_lake"
  private val user    = RbacUser("u-1", Some("acme"), "alice", "user")
  private val ctx     = SchemaContext(Some(Catalog), Some("main"), attachedCatalogs = Set.empty)

  private val columns = Map(
    (Catalog, "main", "kv") -> List("id", "ssn", "n", "l"),
    (Catalog, "main", "sp") -> List("id", "user", "current_user"),
    (Catalog, "main", "u")  -> List("id", "current_date"),
    (Catalog, "main", "o")  -> List("id"),
    (Catalog, "main", "w")  -> List("a", "b"),
    (Catalog, "main", "q")  -> List("id", "main", "pg_catalog")
  )
  private val catalog = new ColumnCatalog.MapCatalog(columns)
  private val rw      = new ColumnPolicyRewriter(catalog)
  private val strict  = new ColumnPolicyRewriter(catalog, unresolvedMode = UnresolvedMode.Deny)
  private val guard   = new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true)

  private def mask(table: String, column: String, sql: String = "'***'") =
    RoleColumnPolicy(s"cp-$table-$column", "r-1", "*", "main", table, column, "mask", Some(sql))
  private def deny(table: String, column: String) =
    RoleColumnPolicy(s"cd-$table-$column", "r-1", "*", "main", table, column, "deny", None)
  private def eff(policies: List[RoleColumnPolicy]) =
    EffectiveSet(user, Nil, Nil, Nil, Nil, policies)

  private def rewrite(sql: String, p: List[RoleColumnPolicy], r: ColumnPolicyRewriter = rw) =
    r.rewrite(sql, StatementKind.Select, eff(p), ctx).unsafeRunSync()

  private def refused(outcome: Outcome): Boolean =
    outcome match
      case Denied(_) | DeniedUnresolvedTable | PassthroughParseFailed => true
      case _                                                          => false

  private def desugared(sql: String): String =
    MethodCalls.desugar(sql) match
      case MethodCalls.Desugared(text, None) => text
      case other                             => fail(s"$sql: $other")

  // ---- the desugaring, form by form (each checked on the DuckDB 2.0.0 CLI) --------------------

  /** Forms DuckDB writes as a plain call, each checked on the DuckDB 2.0.0 CLI. */
  private val nameReceiverForms = List(
    "SELECT s.upper() FROM u"                     -> "SELECT upper(s) FROM u",
    "SELECT u.s.upper() FROM u"                   -> "SELECT upper(u.s) FROM u",
    "SELECT \"s\".upper() FROM u"                 -> "SELECT upper(\"s\") FROM u",
    "SELECT s . upper ( ) FROM u"                 -> "SELECT upper(s) FROM u",
    "SELECT s.upper().lower() FROM u"             -> "SELECT lower(upper(s)) FROM u",
    "SELECT s.upper().lower().length() FROM u"    -> "SELECT length(lower(upper(s))) FROM u",
    "SELECT s.replace('A', 'Z').upper() FROM u"   -> "SELECT upper(replace(s, 'A', 'Z')) FROM u",
    "SELECT s.string_agg(',' ORDER BY id) FROM u" -> "SELECT string_agg(s, ',' ORDER BY id) FROM u",
    "SELECT s.list(ORDER BY id) FROM u"           -> "SELECT list(s ORDER BY id) FROM u",
    "SELECT -n.abs() FROM u"                      -> "SELECT -abs(n) FROM u",
    "SELECT l.list_transform(lambda x: x.upper()) FROM u" ->
      "SELECT list_transform(l, lambda x: upper(x)) FROM u",
    "SELECT max(s.upper()) FROM u"     -> "SELECT max(upper(s)) FROM u",
    "SELECT pg_catalog.upper() FROM u" -> "SELECT upper(pg_catalog) FROM u"
  )

  private val otherReceiverForms = List(
    "SELECT 'abc'.upper()"                     -> "SELECT upper('abc')",
    "SELECT (s || 'x').upper() FROM u"         -> "SELECT upper((s || 'x')) FROM u",
    "SELECT l[1].upper() FROM u"               -> "SELECT upper(l[1]) FROM u",
    "SELECT [3, 1, 2].list_sort()"             -> "SELECT list_sort([3, 1, 2])",
    "SELECT {'a': 1}.struct_extract('a')"      -> "SELECT struct_extract({'a': 1}, 'a')",
    "SELECT j->'a'.upper() FROM u"             -> "SELECT j->upper('a') FROM u",
    "SELECT upper(s).lower() FROM u"           -> "SELECT lower(upper(s)) FROM u",
    "SELECT CAST(n AS VARCHAR).upper() FROM u" -> "SELECT upper(CAST(n AS VARCHAR)) FROM u",
    "SELECT s.upper().lower() FROM u"          -> "SELECT lower(s.upper()) FROM u",
    "SELECT myschema.f(id).upper() FROM u"     -> "SELECT upper(myschema.f(id)) FROM u"
  )

  "a method call on no name chain" should "be written as the plain call DuckDB makes of it" in
    otherReceiverForms.foreach((sql, expected) => withClue(sql)(desugared(sql) shouldBe expected))

  "a method call on a name chain" should
    "be left as written in a statement, for the rewriter to judge on the bindings in scope" in
    List(
      "SELECT s.upper() FROM u",
      "SELECT u.s.upper() FROM u",
      "SELECT ssn.max() FILTER (WHERE true) FROM kv",
      "SELECT myschema.f(id) FROM u",
      "SELECT lake.main.f(id) FROM u",
      "SELECT memory.main.upper(id) FROM u",
      "SELECT myschema.agg(id) FILTER (WHERE true) FROM u"
    ).foreach(sql => withClue(sql)(desugared(sql) shouldBe sql))

  it should "be written as the plain call in a stored mask or row predicate" in
    (nameReceiverForms ++ otherReceiverForms.filterNot((sql, _) =>
      sql.contains("s.upper().lower()") || sql.contains("myschema")
    ))
      .foreach { (sql, expected) =>
        withClue(sql)(MethodCalls.desugarStored(sql) shouldBe MethodCalls.Desugared(expected, None))
      }

  "a function DuckDB resolves itself" should "be left as written" in
    List(
      "SELECT main.upper(s) FROM u",
      "SELECT system.main.upper(s) FROM u",
      "SELECT \"main\".upper(s) FROM u",
      "SELECT main.max(s) FILTER (WHERE true) FROM u",
      "INSERT INTO s.t (a, b) VALUES (1, 2)",
      "CREATE TABLE s.t (a INTEGER)",
      "CREATE TABLE IF NOT EXISTS s.t (a INTEGER)",
      "SELECT * FROM s.tf(1)",
      "SELECT t.* FROM t",
      "SELECT 'a.b(c)' AS s, \"x.y\"(1) FROM t"
    ).foreach { sql =>
      withClue(sql) {
        desugared(sql) shouldBe sql
        if sql.startsWith("SELECT main") || sql.startsWith("SELECT system") ||
          sql.startsWith("SELECT \"main\"")
        then MethodCalls.desugarStored(sql) shouldBe MethodCalls.Desugared(sql, None)
      }
    }

  "a method call that cannot be written as a plain call with certainty" should "be refused" in
    List(
      "SELECT (ssn || 'x').max() FILTER (WHERE true) FROM kv",
      "SELECT ssn.upper().max() OVER () FROM kv",
      "SELECT upper(ssn).mode() WITHIN GROUP (ORDER BY id) FROM kv",
      "SELECT upper(ssn).count(DISTINCT id) FROM kv",
      "SELECT DATE '2020-01-02'.year()",
      "SELECT n::VARCHAR.upper() FROM kv",
      "SELECT CASE WHEN true THEN ssn END.upper() FROM kv",
      "SELECT a.b.c.upper() FROM kv"
    ).foreach { sql =>
      withClue(sql)(MethodCalls.desugar(sql).refusal shouldBe defined)
    }

  // ---- qualifier-named receivers (review round 5, R3-C1) ---------------------------------------

  private val qualifierNamed = List(
    "SELECT pg_catalog.max() FILTER (WHERE true) AS m FROM kv AS pg_catalog",
    "SELECT information_schema.list() FILTER (WHERE true) AS m FROM kv AS information_schema",
    "SELECT temp.main.max() FILTER (WHERE true) AS m FROM q AS temp",
    "SELECT memory.main.max() FILTER (WHERE true) AS m FROM q AS memory",
    "SELECT lake.main.max() FILTER (WHERE true) AS m FROM q AS lake",
    "SELECT pg_catalog.max() FILTER (WHERE true) AS m FROM q",
    "SELECT count(*) FROM kv AS pg_catalog HAVING pg_catalog.max() FILTER (WHERE true) IS NOT NULL",
    "SELECT pg_catalog.max() OVER () AS m FROM kv AS pg_catalog",
    "SELECT ssn.mode() WITHIN GROUP (ORDER BY id) AS m FROM kv",
    "SELECT ssn.max() OVER () AS m FROM kv"
  )

  "a method aggregate on a receiver named like a qualifier" should "be refused" in {
    for
      sql    <- qualifierNamed
      policy <- List(deny("kv", "ssn"), deny("q", "main"), deny("q", "pg_catalog"))
      if !(sql.contains("FROM q") ^ policy.tableName == "q")
      r <- List(rw, strict)
    do
      withClue(s"$sql (${policy.columnName})")(refused(rewrite(sql, List(policy), r)) shouldBe true)
  }

  it should "be refused in a write" in
    List(
      "INSERT INTO w SELECT 1, temp.main.list() FILTER (WHERE true) FROM q AS temp" -> deny(
        "q",
        "main"
      ),
      "INSERT INTO w SELECT 1, pg_catalog.max() FILTER (WHERE true) FROM kv AS pg_catalog" ->
        deny("kv", "ssn")
    ).foreach { (sql, p) =>
      withClue(sql)(
        guard.check(sql, StatementKind.Dml, eff(List(p)), ctx) shouldBe a[GuardOutcome.Deny]
      )
    }

  "a call qualified by a schema nothing in scope is named" should "ship as written (R3-m1)" in {
    val p = List(mask("kv", "ssn"))
    List(
      "SELECT myschema.f(id) AS v FROM kv",
      "SELECT lake.main.f(id) AS v FROM kv",
      "SELECT myschema.agg(id) FILTER (WHERE true) AS v FROM kv"
    ).foreach { sql =>
      withClue(sql) {
        ai.starlake.quack.edge.FlightSqlRouter.policyText(sql, Some(eff(p))) shouldBe sql
        rewrite(sql, p) shouldBe Passthrough
      }
    }
    // Named like a binding in scope, the same FILTER call may be a method aggregate: refused.
    refused(
      rewrite("SELECT kv.agg(id) FILTER (WHERE true) AS v FROM kv", List(deny("kv", "ssn")))
    ) shouldBe true
  }

  // ---- the reviewed statements, in both modes (R2-C1, R2-C2) ---------------------------------

  private val chained = List(
    ("sp", "user", "SELECT (SELECT user.upper().max()) AS r FROM sp"),
    ("sp", "user", "SELECT (SELECT user.upper().lower().max()) AS r FROM sp"),
    ("sp", "current_user", "SELECT (SELECT current_user.upper().max() FROM o) AS r FROM sp"),
    ("u", "current_date", "SELECT (SELECT current_date.upper().max()) AS r FROM u")
  )

  "a chained method-call aggregate in a subquery" should "be refused" in {
    for
      (table, column, sql) <- chained
      policy               <- List(mask(table, column), deny(table, column))
      r                    <- List(rw, strict)
    do withClue(s"$sql (${policy.action})")(refused(rewrite(sql, List(policy), r)) shouldBe true)
  }

  private val filtered = List(
    "SELECT ssn.max() FILTER (WHERE true) AS m FROM kv",
    "SELECT ssn.list() FILTER (WHERE id > 0) AS m FROM kv",
    "SELECT id FROM kv GROUP BY id HAVING ssn.max() FILTER (WHERE true) = 'x'",
    "SELECT id FROM kv GROUP BY id HAVING ssn.max() = 'x'"
  )

  "a method-call aggregate with FILTER" should "be refused, in the select list and as HAVING" in {
    for
      sql    <- filtered
      policy <- List(mask("kv", "ssn"), deny("kv", "ssn"))
      r      <- List(rw, strict)
    do
      val outcome = rewrite(sql, List(policy), r)
      withClue(s"$sql (${policy.action}) -> $outcome") {
        if sql.contains("FILTER") || policy.action == "deny" then refused(outcome) shouldBe true
        else outcome should not be Passthrough
      }
    for policy <- List(mask("sp", "user"), deny("sp", "user")) do
      refused(
        rewrite("SELECT (SELECT user.max() FILTER (WHERE true)) AS r FROM sp", List(policy))
      ) shouldBe true
  }

  "the protected-write guard" should "refuse these forms in a write" in
    List(
      "INSERT INTO w SELECT 1, ssn.max() FILTER (WHERE true) FROM kv" -> mask("kv", "ssn"),
      "INSERT INTO w SELECT 1, ssn.upper().lower() FROM kv"           -> deny("kv", "ssn"),
      "INSERT INTO w SELECT 1, (SELECT user.upper().max()) FROM sp"   -> deny("sp", "user"),
      "CREATE TABLE z AS SELECT ssn.upper() AS v FROM kv"             -> deny("kv", "ssn")
    ).foreach { (sql, p) =>
      val kind = if sql.startsWith("CREATE") then StatementKind.Ddl else StatementKind.Dml
      withClue(sql)(guard.check(sql, kind, eff(List(p)), ctx) shouldBe a[GuardOutcome.Deny])
    }

  "the backstop" should "refuse a call left in method form, never a function's qualifier" in {
    def parsed(sql: String) = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql)
    ResidualColumns.methodCallLeft(parsed("SELECT ssn.max() FILTER (WHERE true) FROM kv")) shouldBe
      defined
    // A scalar call in method form is left to the method normalizer, which judges its receiver.
    ResidualColumns.methodCallLeft(parsed("SELECT ssn.upper() FROM kv")) shouldBe None
    ResidualColumns.methodCallLeft(
      parsed("SELECT pg_catalog.max() FILTER (WHERE true) FROM kv AS pg_catalog")
    ) shouldBe defined
    ResidualColumns.methodCallLeft(
      parsed("SELECT main.max(ssn) FILTER (WHERE true) FROM kv")
    ) shouldBe
      None
    ResidualColumns.methodCallLeft(parsed("SELECT main.upper(ssn) FROM kv")) shouldBe None
    ResidualColumns.methodCallLeft(parsed("SELECT system.main.upper(ssn) FROM kv")) shouldBe None
  }

  // ---- the argument check, call by call (m-r4) -------------------------------------------------

  "the argument check" should "compare each call with its own text" in {
    def check(sql: String) =
      ResidualColumns.droppedArgument(
        sql,
        List(net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql))
      )
    check("SELECT list(output ORDER BY id), list(separator, id) FROM w") shouldBe defined
    check("SELECT list(a, b),\n\tlist(\n  output ORDER BY id) FROM w") shouldBe defined
    check("SELECT list(a ORDER BY b, a),\n\tmax(\n b) FROM w") shouldBe None
  }

  // ---- named parameters and colons (m-r5) ------------------------------------------------------

  "the word quoting" should "quote after a separating colon and never after a parameter's" in {
    MisreadNames.quoteWords("SELECT {'a': key} FROM t") shouldBe "SELECT {'a': \"key\"} FROM t"
    MisreadNames.quoteWords("SELECT {a: minus} FROM t") shouldBe "SELECT {a: \"minus\"} FROM t"
    MisreadNames.quoteWords("SELECT list_transform(l, lambda x: key || x) FROM t") shouldBe
      "SELECT list_transform(l, lambda x: \"key\" || x) FROM t"
    MisreadNames.quoteWords("SELECT s[1:key] FROM t") shouldBe "SELECT s[1:\"key\"] FROM t"
    MisreadNames.quoteWords("SELECT s FROM t WHERE id = :key") shouldBe
      "SELECT s FROM t WHERE id = :key"
    MisreadNames.quoteWords("SELECT x::key FROM t") shouldBe "SELECT x::key FROM t"
    MisreadNames.quoteWords("SELECT $key") shouldBe "SELECT $key"
  }

  it should "leave DDL's own uses of the words alone" in
    List(
      "CREATE TABLE t (id INTEGER PRIMARY KEY, b INTEGER)",
      "CREATE TABLE t (id INTEGER, FOREIGN KEY (id) REFERENCES o (id))",
      "CREATE TABLE IF NOT EXISTS t (id INTEGER)",
      "DROP TABLE IF EXISTS t"
    ).foreach(sql => withClue(sql)(MisreadNames.quoteWords(sql) shouldBe sql))

  // ---- DuckDB evidence ---------------------------------------------------------------------------

  private def database(maskedValues: Boolean): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    List(
      s"ATTACH ':memory:' AS $Catalog",
      s"USE $Catalog.main",
      "CREATE TABLE kv (id INTEGER, ssn VARCHAR, n INTEGER, l VARCHAR[])",
      "INSERT INTO kv VALUES (1, 'Ssn-Secret', -5, ['ab', 'Cd']), (2, 'Other', 3, ['x'])"
    ).foreach(sql => Using.resource(conn.createStatement())(_.execute(sql)))
    if maskedValues then
      Using.resource(conn.createStatement())(_.execute("UPDATE kv SET ssn = left(ssn, 1) || '***'"))
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
    }.toEither.left.map(_ => "error")

  private val receivers = List("ssn", "kv.ssn", "(ssn || 'x')", "'abc'", "l[1]", "upper(ssn)")
  private val methods   = List(
    "upper()",
    "lower()",
    "replace('S', 'Z')",
    "concat('x', 'y')",
    "left(2)",
    "length()",
    "string_agg(',' ORDER BY id)",
    "max()",
    "list()"
  )

  /** Chains of 1 to 3 methods over each receiver. */
  private val shapes =
    for
      r     <- receivers
      depth <- 1 to 3
      chain <- methods.combinations(depth).flatMap(_.permutations).take(40)
    yield s"$r.${chain.mkString(".")}"

  "the desugared form" should "answer exactly what DuckDB answers for the method form" in
    Using.resource(database(maskedValues = false)) { conn =>
      var compared = 0
      for
        shape        <- shapes
        (mode, pass) <- List(
          "statement" -> ((t: String) => MethodCalls.desugar(t)),
          "stored"    -> ((t: String) => MethodCalls.desugarStored(t))
        )
      do
        val sql = s"SELECT $shape AS v FROM kv"
        pass(sql) match
          case MethodCalls.Desugared(text, None) =>
            withClue(s"$mode: $sql\n -> $text")(rowsOf(conn, text) shouldBe rowsOf(conn, sql))
            compared += 1
          case MethodCalls.Desugared(_, Some(r)) => fail(s"$mode: $sql refused: $r")
      info(s"$compared method-call shapes compared on DuckDB")
      compared should be > 1000
    }

  "a rewritten method call on DuckDB" should "return the masked value" in
    Using.resources(database(maskedValues = false), database(maskedValues = true)) { (raw, mk) =>
      val policies = List(mask("kv", "ssn", "left(ssn, 1) || '***'"))
      List(
        "SELECT ssn.upper().lower() AS v FROM kv ORDER BY id",
        "SELECT kv.ssn.left(3) AS v FROM kv ORDER BY id",
        "SELECT ssn.max() AS v FROM kv",
        "SELECT id FROM kv WHERE ssn.upper() = 'SSN-SECRET' ORDER BY id"
      ).foreach { sql =>
        val out = rewrite(sql, policies) match
          case Rewritten(s) => s
          case other        => fail(s"$sql: $other")
        withClue(s"$sql\n -> $out")(rowsOf(raw, out) shouldBe rowsOf(mk, sql))
      }
    }
