package ai.starlake.quack.edge.rls

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RoleRowPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import scala.util.{Try, Using}

/** A new row predicate may not use a form DuckDB 2.0.0 can answer with an error quoting a value of
  * the row it evaluates ([[PredicateForms]]). DuckDB evaluates a predicate on every row it reads,
  * those the policy removes included, so such an error could hand a hidden row's value to the
  * reader. A predicate stays a predicate language: comparisons, IN lists, the identity tokens and
  * every other function stay allowed. A stored predicate keeps applying and is reported at boot.
  */
class RaisingPredicateSpec extends AnyFlatSpec with Matchers:

  /** Predicates a create refuses, each with the words its refusal names the part by. */
  private val Refused = List(
    "CAST(name AS INTEGER) > 0"                      -> "CAST or :: to a type other than VARCHAR",
    "name::INTEGER > 0"                              -> "CAST or :: to a type other than VARCHAR",
    "CAST(name AS DATE) > DATE '2000-01-01'"         -> "CAST or :: to a type other than VARCHAR",
    "CAST(n AS TINYINT) > 0"                         -> "CAST or :: to a type other than VARCHAR",
    "region = 'eu' AND name::INTEGER > 0"            -> "CAST or :: to a type other than VARCHAR",
    "strptime(name, '%Y') > TIMESTAMP '2000-01-01'"  -> "strptime()",
    "json_extract_string(name, '$.a') = 'x'"         -> "json_extract_string()",
    "json_extract(name, '$.a') = 'x'"                -> "json_extract()",
    "json_type(name) = 'x'"                          -> "json_type()",
    "json(name) IS NOT NULL"                         -> "json()",
    "from_json(name, '{\"a\":\"VARCHAR\"}') IS NULL" -> "from_json()",
    "name ->> '$.a' = 'x'"                           -> "-> / ->>",
    "from_base64(name) IS NOT NULL"                  -> "from_base64()",
    "unhex(name) IS NOT NULL"                        -> "unhex()",
    "from_hex(name) IS NOT NULL"                     -> "from_hex()",
    "unbin(name) IS NOT NULL"                        -> "unbin()",
    "from_binary(name) IS NOT NULL"                  -> "from_binary()",
    "make_date(n, 1, 1) > DATE '2000-01-01'"         -> "make_date()",
    "make_time(n, 0, 0) IS NULL"                     -> "make_time()",
    "make_timestamp(n, 1, 1, 0, 0, 0) IS NOT NULL"   -> "make_timestamp()",
    "to_days(big) > INTERVAL 1 DAY"                  -> "to_days()",
    "to_hours(big) > INTERVAL 1 DAY"                 -> "to_hours()",
    "INTERVAL (big) DAY > INTERVAL 1 DAY"            -> "INTERVAL (<expression>)",
    "error(name) IS NULL"                            -> "error()",
    "n + n = 1"                                      -> "arithmetic (+, -, *, <<, >>)",
    "n * n > 0"                                      -> "arithmetic (+, -, *, <<, >>)",
    "n - m > 0"                                      -> "arithmetic (+, -, *, <<, >>)",
    "big << 3 > 0"                                   -> "arithmetic (+, -, *, <<, >>)",
    "add(n, 1) > 0"                                  -> "add()",
    "subtract(m, 1) = 0"                             -> "subtract()",
    "multiply(n, 2) = 0"                             -> "multiply()",
    "divide(m, -1) = 0"                              -> "divide()",
    "mod(m, -1) = 0"                                 -> "mod()",
    "abs(m) > 0"                                     -> "abs()",
    "chr(n) = 'x'"                                   -> "chr()",
    "repeat(name, n) = 'x'"                          -> "repeat()",
    "m % -1 = 0"                                  -> "% or // by anything but a positive integer",
    "date_part(name, TIMESTAMP '2020-01-01') = 1" -> "date_part() with an argument read from",
    "date_trunc(name, TIMESTAMP '2020-01-01') IS NULL" -> "date_trunc() with an argument read from",
    "timezone(name, TIMESTAMPTZ '2020-01-01') IS NULL" -> "timezone() with an argument read from",
    "regexp_matches('x', name)"       -> "regexp_matches() with an argument read from",
    "regexp_extract('x', name) = 'x'" -> "regexp_extract() with an argument read from",
    "'x' ~ name"                      -> "~ with a pattern read from the row",
    "'x' SIMILAR TO name"             -> "SIMILAR TO with a pattern read from the row",
    "owner = ${user} AND n + n = 1"   -> "arithmetic (+, -, *, <<, >>)"
  )

  /** Refused under TRY(...) too: TRY does not catch every such error (see the mask evidence), so a
    * raising form is judged wherever it sits.
    */
  private val RefusedUnderTry = List(
    "TRY(CAST(name AS INTEGER)) > 0" -> "CAST or :: to a type other than VARCHAR",
    "TRY(n + n) = 1"                 -> "arithmetic (+, -, *, <<, >>)"
  )

  /** Predicates a create admits: comparisons, IN lists, identity tokens, the non-raising forms. */
  private val Allowed = List(
    "region = 'eu'",
    "owner = ${user}",
    "dept IN (${groups})",
    "tenant_id = ${tenantId} AND owner = ${user}",
    "n > 5",
    "n BETWEEN 1 AND 10",
    "n % 2 = 0",
    "-m = 1",
    "n / 2 > 1",
    "region IS NOT NULL",
    "lower(region) = 'eu'",
    "upper(name) LIKE 'B%'",
    "coalesce(region, 'x') = 'eu'",
    "starts_with(name, 'B')",
    "length(name) > 3",
    "regexp_matches(name, '^B')",
    "name ~ '^B'",
    "date_part('year', dt) = 2020",
    "TRY_CAST(name AS INTEGER) > 0",
    "TRY_CAST(name AS DATE) IS NULL",
    "CAST(name AS VARCHAR) = 'x'",
    "name::VARCHAR = 'x'",
    "try_strptime(name, '%Y') IS NULL",
    "json_valid(name)",
    "CAST('5' AS INTEGER) = n"
  )

  /** Comparisons DuckDB answers by casting text to the constant's type: allowed by ruling, and they
    * can quote the value. Kept here so the residual stays visible.
    */
  private val ResidualComparisons = List("name = 42", "name IN (1, 2)", "name IS TRUE")

  "validate" should "refuse every raising form, naming it and the non-raising forms" in
    (Refused ++ RefusedUnderTry).foreach { (predicate, part) =>
      withClue(predicate) {
        RowPredicateValidator.validate(predicate) match
          case RowPredicateValidator.Invalid(reason) =>
            reason should include(part)
            reason should include("can raise an error quoting a value of the row")
            reason should include("TRY_CAST")
            reason should not include predicate
          case other => fail(s"expected Invalid, got $other")
      }
    }

  it should "admit comparisons, IN lists, identity tokens and the non-raising forms" in
    (Allowed ++ ResidualComparisons).foreach { predicate =>
      withClue(predicate) {
        RowPredicateValidator.validate(predicate) shouldBe RowPredicateValidator.Valid(predicate)
      }
    }

  /** One row holding the values the forms above fail on. */
  private def database(): Connection =
    Class.forName("org.duckdb.DuckDBDriver")
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    Using.resource(conn.createStatement())(
      _.execute(
        "CREATE TABLE t AS SELECT 'SECRET(]' AS name, 2147483647 AS n, (-2147483648)::INTEGER " +
          "AS m, 9223372036854775807::BIGINT AS big, DATE '2020-01-01' AS dt, 'eu' AS region, " +
          "'alice' AS owner, 'd' AS dept, 'acme' AS tenant_id"
      )
    )
    conn

  private val Markers = List(
    "SECRET",
    "2147483647",
    "2147483648",
    "9223372036854775807",
    "9.223372036854776e+18",
    "for binary digit: S",
    "for hex digit: S",
    // repeat()'s size, the row's length times its count
    "17179869176"
  )

  /** The error `predicate` answers with on the row, when it quotes one of the row's values. */
  private def quotes(conn: Connection, predicate: String): Option[String] =
    val sql = s"SELECT count(*) FROM t WHERE ${predicate
        .replace("${user}", "'alice'")
        .replace("${groups}", "'d'")
        .replace("${tenantId}", "'acme'")}"
    Try(
      Using.resource(conn.createStatement())(st => Using.resource(st.executeQuery(sql))(_.next()))
    ).failed.toOption
      .map(e => Option(e.getMessage).getOrElse("").replaceAll("(?s)LINE 1:.*", ""))
      .filter(m => Markers.exists(m.contains))

  "every refused form" should "raise an error quoting a value of the row on DuckDB" in
    Using.resource(database()) { conn =>
      Refused.map(_._1).foreach { predicate =>
        withClue(predicate)(quotes(conn, predicate) should not be empty)
      }
    }

  "the admitted forms" should "quote no value, the residual comparisons aside" in
    Using.resource(database()) { conn =>
      Allowed.foreach(p => withClue(p)(quotes(conn, p) shouldBe None))
      ResidualComparisons.foreach(p => withClue(p)(quotes(conn, p) should not be empty))
    }

  private def supervisor() =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
    val t     = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val role  = sup.createRole(t.id, "analyst").unsafeRunSync().toOption.get
    (store, sup, role)

  "createRowPolicy and updateRowPolicy" should "refuse every raising form, writing nothing" in {
    val (store, sup, role) = supervisor()
    Refused.foreach { (predicate, part) =>
      withClue(predicate) {
        sup.createRowPolicy(role.id, "*", "main", "t", predicate).unsafeRunSync() match
          case Left(err) => err.toString should include(part)
          case Right(p)  => fail(s"created $p")
      }
    }
    store.listRowPolicies(role.id) shouldBe Nil
    val created =
      sup.createRowPolicy(role.id, "*", "main", "t", "region = 'eu'").unsafeRunSync().toOption.get
    Refused.foreach { (predicate, part) =>
      withClue(predicate) {
        sup.updateRowPolicy(created.id, predicate).unsafeRunSync() match
          case Left(err) => err.toString should include(part)
          case Right(_)  => fail(s"updated to $predicate")
      }
    }
    store.listRowPolicies(role.id).map(_.predicateSql) shouldBe List("region = 'eu'")
  }

  "the boot report" should "name each stored raising predicate once with its part, never its text" in {
    val (store, _, role) = supervisor()
    val stored           = Refused.zipWithIndex.map { case ((predicate, _), i) =>
      store.insertRowPolicy(RoleRowPolicy(s"rp-$i", role.id, "*", "main", s"t$i", predicate))
    }
    store.insertRowPolicy(RoleRowPolicy("rp-safe", role.id, "*", "main", "ok", "region = 'eu'"))
    store.insertRowPolicy(RoleRowPolicy("rp-cmp", role.id, "*", "main", "ok", "name = 42"))
    val lines = StoredPredicateReport.linesOf(store)
    lines should have size stored.size.toLong
    stored.zip(Refused).foreach { (p, refused) =>
      val mine = lines.filter(_.contains(s"row policy ${p.id} on"))
      withClue(p.predicateSql)(mine should have size 1)
      mine.head should startWith("WARN: tenant 'acme' role 'analyst': ")
      mine.head should include(s"*.main.${p.tableName}")
      mine.head should include(refused._2)
      mine.head should include("It keeps applying as stored")
      mine.head should not include p.predicateSql
    }
    lines.exists(l => l.contains("rp-safe") || l.contains("rp-cmp")) shouldBe false
  }
