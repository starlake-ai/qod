package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.policy.PolicyPipelineFixture
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RoleColumnPolicy, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DuckDB folds identifier case for ASCII letters only (`StringUtil::Lower`): `"Clés"` and `"clÉs"`
  * are two names, two tables can hold them in one schema, and a CTE named `"clÉs"` does not shadow
  * the table `"Clés"`. The gateway's policy steps once compared names with Java's
  * `toLowerCase(Locale.ROOT)`, which folds every cased letter, so they read `FROM "Clés"` as a
  * reference to the CTE `"clÉs"` and passed the statement as written: a masked or denied column of
  * the real table went out raw (security review C1).
  *
  * Driven through the real policy pipeline and an in-process DuckDB holding the table: whatever the
  * gateway sends must not return a raw value of a masked or denied column, nor a row the row policy
  * hides. A refusal is accepted everywhere.
  */
class NonAsciiCaseFoldSpec extends AnyFlatSpec with Matchers:

  import PolicyPipelineFixture.*

  private val kc  = DuckLake
  private val Ref = s""""${kc.catalog}"."main"."Clés""""

  private val catalog = new ColumnCatalog.MapCatalog(
    Map((kc.catalog, "main", "Clés") -> List("id", "secret", "note"))
  )

  private val maskSecret =
    RoleColumnPolicy("cp-1", "r-1", "*", "main", "Clés", "secret", "mask", Some("'***'"))
  private val denyNote =
    RoleColumnPolicy("cp-2", "r-1", "*", "main", "Clés", "note", "deny", None)
  private val onlyFirst = RoleRowPolicy("rp-1", "r-1", "*", "main", "Clés", "id = 1")

  private val columnHolder: EffectiveSet = effWith(columnPolicies = List(maskSecret, denyNote))
  private val rowHolder: EffectiveSet    = effWith(rowPolicies = List(onlyFirst))
  private val bothPolicies: EffectiveSet =
    effWith(columnPolicies = List(maskSecret, denyNote), rowPolicies = List(onlyFirst))

  /** Every value of the table: none may come back to a policy holder but `1` and the CTE's. */
  private val RawValues = Set("raw-secret-1", "raw-note-1", "raw-secret-2", "raw-note-2")

  private def withTable(f: (java.sql.Connection, Harness) => Unit): Unit =
    withDuckDb(kc.catalog) { conn =>
      exec(conn, s"CREATE TABLE $Ref (id INTEGER, secret VARCHAR, note VARCHAR)")
      exec(
        conn,
        s"INSERT INTO $Ref VALUES (1, 'raw-secret-1', 'raw-note-1'), (2, 'raw-secret-2', 'raw-note-2')"
      )
      f(conn, harness(kc, catalog, guarded = true))
    }

  /** What a holder of `eff` gets for `sql`: None when the gateway refused it, else the rows DuckDB
    * returns for the statement the gateway sent.
    */
  private def served(conn: java.sql.Connection, h: Harness, sql: String, eff: EffectiveSet) =
    h.run(sql, eff).toOption.map(text => text -> rowsOf(conn, text))

  // The CTE is spelled with the other case of the table's non-ASCII letter: DuckDB reads the table.
  private val Cte = """"clÉs""""

  private val columnCases = List(
    // aliased
    s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) SELECT c.secret FROM \"Clés\" c",
    // no alias, bare column
    s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) SELECT secret FROM \"Clés\"",
    // qualified by the table's own name
    s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) " +
      "SELECT \"Clés\".secret FROM \"Clés\"",
    // unquoted on both sides
    "WITH clÉs AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) SELECT c.secret FROM Clés c",
    // a star
    s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) SELECT * FROM \"Clés\"",
    // inside a derived table
    s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) " +
      "SELECT s.x FROM (SELECT secret AS x FROM \"Clés\") s",
    // inside an expression subquery
    s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) " +
      "SELECT (SELECT max(secret) FROM \"Clés\") AS x",
    // the WITH inside a derived table
    s"SELECT * FROM (WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) " +
      "SELECT c.secret FROM \"Clés\" c) s",
    // a set operation reading the CTE in one arm and the table in the other
    s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) " +
      s"SELECT secret FROM $Cte UNION ALL SELECT secret FROM \"Clés\"",
    s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) " +
      s"SELECT c.secret FROM \"Clés\" c UNION ALL SELECT secret FROM $Cte",
    // the deny column, aliased and bare
    s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) SELECT c.note FROM \"Clés\" c",
    s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) SELECT note FROM \"Clés\"",
    // the CTE spelled upper case in its non-ASCII letter, the table lower
    "WITH \"CLÉS\" AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) " +
      "SELECT c.secret, c.note FROM \"Clés\" c"
  )

  "a CTE named like a table but for a non-ASCII letter's case" should
    "never hide the table's masked or denied columns from a column-policy holder" in
    withTable { (conn, h) =>
      columnCases.foreach { sql =>
        served(conn, h, sql, columnHolder).foreach { (text, rows) =>
          withClue(s"$sql\n -> $text\n -> $rows\n") {
            rows.flatten.toSet.intersect(RawValues) shouldBe empty
          }
        }
      }
    }

  it should "never hide the table's rows from a row-policy holder" in
    withTable { (conn, h) =>
      columnCases.foreach { sql =>
        served(conn, h, sql, rowHolder).foreach { (text, rows) =>
          withClue(s"$sql\n -> $text\n -> $rows\n") {
            rows.flatten.toSet should not contain "raw-secret-2"
            rows.flatten.toSet should not contain "raw-note-2"
          }
        }
      }
    }

  it should "never hide the table from a holder of both kinds of policy" in
    withTable { (conn, h) =>
      columnCases.foreach { sql =>
        served(conn, h, sql, bothPolicies).foreach { (text, rows) =>
          withClue(s"$sql\n -> $text\n -> $rows\n") {
            rows.flatten.toSet.intersect(RawValues) shouldBe empty
          }
        }
      }
    }

  it should "still mask the table's column, not refuse it, when the statement only names a CTE" in
    withTable { (conn, h) =>
      // A plain masked read keeps working and shows the mask.
      val sql = s"WITH $Cte AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) " +
        "SELECT c.id, c.secret FROM \"Clés\" c ORDER BY c.id"
      served(conn, h, sql, columnHolder) match
        case Some((text, rows)) =>
          withClue(text)(rows shouldBe List(List("1", "***"), List("2", "***")))
        case None => fail(s"$sql was refused")
      // Every form reading only the masked column is served, masked, rather than refused; a read
      // of the deny column (or a star over the table, which holds it) is refused.
      val (denied, masked) =
        columnCases.partition(s => s.contains("note FROM") || s.endsWith("SELECT * FROM \"Clés\""))
      masked.foreach { s =>
        served(conn, h, s, columnHolder) match
          case Some((text, rows)) =>
            withClue(s"$s\n -> $text\n")(rows.flatten.filterNot(_ == "***") shouldBe empty)
          case None => fail(s"$s was refused")
      }
      denied.foreach(s => withClue(s)(h.run(s, columnHolder).isLeft shouldBe true))
    }

  "a CTE whose name folds to the table's under DuckDB's ASCII fold" should
    "still shadow the table, as DuckDB binds it" in
    withTable { (conn, h) =>
      // `"CLéS"` folds to `clés`, the table's own name: DuckDB reads the CTE, so the column step
      // has nothing to mask and serves the CTE's row.
      val sql = "WITH \"CLéS\" AS (SELECT 0 AS id, 'cte' AS secret, 'cte' AS note) " +
        "SELECT c.secret, c.note FROM \"Clés\" c"
      served(conn, h, sql, columnHolder) match
        case Some((text, rows)) => withClue(text)(rows shouldBe List(List("cte", "cte")))
        case None               => fail(s"$sql was refused")
      // The row step wraps a reference by its name and knows no CTE (review M1, unchanged here):
      // the wrap reads the CTE under the row filter, so its row is filtered out, never a raw one.
      List(rowHolder, bothPolicies).foreach { eff =>
        served(conn, h, sql, eff).foreach { (text, rows) =>
          withClue(text)(rows.flatten.toSet.intersect(RawValues) shouldBe empty)
        }
      }
    }

  // ---- the write path: the protected-write guard reads the same CTE scoping -------------------

  private val guard = new ai.starlake.quack.edge.policy.ProtectedWriteGuard(
    catalog,
    clsEnabled = true,
    rlsEnabled = true
  )
  private val guardCtx = SchemaContext(Some(kc.catalog), Some("main"), attachedCatalogs = Set.empty)

  private def guarded(sql: String, eff: EffectiveSet, kind: ai.starlake.quack.model.StatementKind) =
    guard.check(sql, kind, eff, guardCtx)

  "a write reading the table through a CTE spelled with the other case" should
    "be refused to a holder of column or row policies" in {
      import ai.starlake.quack.model.StatementKind
      val writes = List(
        StatementKind.Dml -> (s"INSERT INTO main.scratch WITH $Cte AS (SELECT 0 AS secret) " +
          "SELECT secret FROM \"Clés\""),
        StatementKind.Ddl -> (s"CREATE TABLE main.leak AS WITH $Cte AS (SELECT 0 AS secret) " +
          "SELECT c.secret FROM \"Clés\" c"),
        StatementKind.Dml -> (s"WITH $Cte AS (SELECT 0 AS secret) INSERT INTO main.scratch " +
          "SELECT secret FROM \"Clés\"")
      )
      for
        (kind, sql) <- writes
        eff         <- List(columnHolder, rowHolder, bothPolicies)
      do
        withClue(sql)(
          guarded(sql, eff, kind) shouldBe a[ai.starlake.quack.edge.policy.GuardOutcome.Deny]
        )
    }
