package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.cls.{ColumnCatalog, SchemaContext}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RoleColumnPolicy, RoleRowPolicy}
import ai.starlake.quack.route.StatementClassifier
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Connection
import scala.collection.mutable.ListBuffer
import scala.util.Using

/** Owner ruling D27: a write aimed at the table a principal's row policy covers reached every row,
  * the hidden ones included, since neither rewriter reads a write; and a write to a table with a
  * column policy read the masked column raw in its WHERE, SET values, RETURNING or ON CONFLICT.
  *
  * Each form is judged three ways: by the guard alone, through the router's whole policy pipeline
  * (what reaches the node), and on an in-process DuckDB, which shows what the statement does to the
  * rows the policy hides (the evidence that a refused form leaked before the fix).
  */
class ProtectedWriteGuardDirectWriteSpec extends AnyFlatSpec with Matchers:

  import PolicyPipelineFixture.*

  private val catalog = new ColumnCatalog.MapCatalog(
    Map(
      ("memory", "main", "secret") -> List("id", "owner", "a", "b", "email"),
      ("memory", "main", "other")  -> List("id", "v", "k", "email")
    )
  )
  private val guard = new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true)
  private val ctx   = SchemaContext(Some("memory"), Some("main"), Set.empty)

  private val rowEff =
    effWith(rowPolicies = List(RoleRowPolicy("rp", "r", "*", "main", "secret", "owner = 'alice'")))
  private val maskEff = effWith(columnPolicies =
    List(RoleColumnPolicy("cp", "r", "*", "main", "secret", "email", "mask", Some("'***'")))
  )
  private val denyEff = effWith(columnPolicies =
    List(RoleColumnPolicy("cd", "r", "*", "main", "secret", "email", "deny", None))
  )

  private val RowWrite   = "writes to a row-policy table are refused"
  private val ColWrite   = "cannot read a masked or denied column"
  private val ReadInto   = "cannot be read into a write statement"
  private val DdlRefusal =
    "is protected by a column/row policy, and altering, dropping or replacing it is refused"

  // ---- forms ----

  /** A form a row-policy holder is refused, the refusal it reads, and whether DuckDB, given the
    * statement unfiltered, touches or returns a row the policy hides.
    */
  private final case class Refused(sql: String, reason: String, leaks: Boolean)

  private val rowRefused = List(
    Refused("UPDATE secret SET a = 0", RowWrite, leaks = true),
    Refused("UPDATE secret SET a = 0 WHERE owner = 'bob'", RowWrite, leaks = true),
    Refused(
      "UPDATE secret SET a = o.v FROM other o WHERE o.id = secret.id",
      RowWrite,
      leaks = true
    ),
    Refused("UPDATE secret SET a = b", RowWrite, leaks = true),
    Refused("UPDATE secret SET a = 0 RETURNING *", RowWrite, leaks = true),
    Refused("UPDATE secret SET a = 0 WHERE owner = 'bob' RETURNING owner, email", RowWrite, true),
    Refused(
      "UPDATE secret SET a = o.v FROM other o WHERE o.id = secret.id RETURNING secret.email",
      RowWrite,
      leaks = true
    ),
    Refused("UPDATE secret SET a = b RETURNING *", RowWrite, leaks = true),
    Refused("UPDATE secret s SET a = 0 WHERE s.owner = 'bob'", RowWrite, leaks = true),
    Refused("UPDATE main.secret SET a = 0 WHERE owner = 'bob'", RowWrite, leaks = true),
    Refused("UPDATE memory.main.secret SET a = 0 WHERE owner = 'bob'", RowWrite, leaks = true),
    Refused("UPDATE \"secret\" SET a = 0 WHERE owner = 'bob'", RowWrite, leaks = true),
    Refused("UPDATE `secret` SET a = 0 WHERE owner = 'bob'", RowWrite, leaks = true),
    Refused("UPDATE SECRET SET a = 0 WHERE owner = 'bob'", RowWrite, leaks = true),
    Refused("DELETE FROM secret", RowWrite, leaks = true),
    Refused("DELETE FROM secret WHERE owner = 'bob'", RowWrite, leaks = true),
    Refused(
      "DELETE FROM secret USING other o WHERE o.id = secret.id AND o.v = 200",
      RowWrite,
      true
    ),
    Refused("DELETE FROM secret WHERE id = 2 RETURNING *", RowWrite, leaks = true),
    Refused(
      "WITH x AS (SELECT 2 AS id) DELETE FROM secret WHERE id IN (SELECT id FROM x)",
      RowWrite,
      leaks = true
    ),
    Refused(
      "MERGE INTO secret t USING other o ON t.id = o.id WHEN MATCHED THEN UPDATE SET a = o.v",
      RowWrite,
      leaks = true
    ),
    Refused(
      "MERGE INTO secret t USING other o ON t.id = o.id WHEN MATCHED THEN DELETE",
      RowWrite,
      leaks = true
    ),
    Refused(
      "MERGE INTO secret t USING other o ON t.id = o.id WHEN MATCHED THEN UPDATE SET a = o.v " +
        "RETURNING *",
      RowWrite,
      leaks = true
    ),
    Refused(
      "INSERT INTO secret VALUES (2, 'alice', 0, 0, 'z') ON CONFLICT (id) DO UPDATE SET a = 99",
      RowWrite,
      leaks = true
    ),
    Refused("INSERT OR REPLACE INTO secret VALUES (2, 'alice', 0, 0, 'z')", RowWrite, leaks = true),
    Refused("INSERT INTO secret VALUES (3, 'alice', 30, 31, 'c@x') RETURNING *", RowWrite, false),
    Refused("TRUNCATE secret", RowWrite, leaks = true),
    // Reading the row-policy table as a source stays refused with its own reason.
    Refused(
      "MERGE INTO other t USING secret s ON t.id = s.id WHEN MATCHED THEN UPDATE SET v = s.a",
      ReadInto,
      leaks = true
    ),
    // DuckDB refuses a subquery in RETURNING and in DO UPDATE SET; the guard refuses it first.
    Refused(
      "UPDATE other SET v = 1 WHERE id = 1 RETURNING (SELECT max(email) FROM secret)",
      ReadInto,
      leaks = false
    ),
    Refused(
      "DELETE FROM other WHERE id = 1 RETURNING (SELECT max(email) FROM secret)",
      ReadInto,
      leaks = false
    ),
    Refused(
      "INSERT INTO other VALUES (9, 9, 'k', 'e') RETURNING (SELECT max(email) FROM secret)",
      ReadInto,
      leaks = false
    ),
    Refused(
      "INSERT INTO other VALUES (1, 0, 'x', 'e') ON CONFLICT (id) DO UPDATE " +
        "SET k = (SELECT max(email) FROM secret)",
      ReadInto,
      leaks = false
    ),
    Refused(
      "MERGE INTO other t USING (SELECT 1 AS id) s ON t.id = s.id WHEN MATCHED THEN " +
        "UPDATE SET v = 0 RETURNING (SELECT max(email) FROM secret)",
      ReadInto,
      leaks = false
    )
  )

  /** Plain inserts of new rows stay allowed to a row-policy holder, as before. */
  private val rowAllowed = List(
    "INSERT INTO secret VALUES (3, 'alice', 30, 31, 'c@x')",
    "INSERT INTO secret (id, owner) VALUES (3, 'alice')",
    "INSERT INTO secret SELECT id + 10, 'alice', v, v, k FROM other",
    "INSERT INTO secret VALUES (3, 'alice', 0, 0, 'z') ON CONFLICT (id) DO NOTHING",
    "UPDATE other SET v = 1 WHERE id = 1",
    "DELETE FROM other WHERE id = 1 RETURNING *"
  )

  private val colRefused = List(
    Refused("UPDATE secret SET a = 0 WHERE email = 'bob@x'", ColWrite, leaks = true),
    Refused("UPDATE secret SET a = 0 WHERE \"email\" = 'bob@x'", ColWrite, leaks = true),
    Refused("UPDATE secret SET a = 0 WHERE `EMAIL` = 'bob@x'", ColWrite, leaks = true),
    Refused("UPDATE secret SET a = 0 WHERE secret.email = 'bob@x'", ColWrite, leaks = true),
    Refused("UPDATE secret SET a = 0 WHERE main.secret.email = 'bob@x'", ColWrite, leaks = true),
    Refused("UPDATE secret s SET a = 0 WHERE s.email = 'bob@x'", ColWrite, leaks = true),
    Refused("UPDATE secret SET a = length(email)", ColWrite, leaks = true),
    Refused("UPDATE secret SET a = 0 WHERE id = 2 RETURNING email", ColWrite, leaks = true),
    Refused("UPDATE secret SET a = 0 WHERE id = 2 RETURNING *", ColWrite, leaks = true),
    Refused("UPDATE secret SET a = 0 WHERE id = 2 RETURNING COLUMNS('e.*')", ColWrite, true),
    Refused(
      "UPDATE secret SET a = o.v FROM other o WHERE o.k = secret.email",
      ColWrite,
      leaks = true
    ),
    Refused(
      "UPDATE secret SET a = 0 WHERE EXISTS (SELECT 1 WHERE email = 'bob@x')",
      ColWrite,
      leaks = true
    ),
    Refused("UPDATE secret SET a = 0 WHERE secret::VARCHAR LIKE '%bob@%'", ColWrite, true),
    Refused("DELETE FROM secret WHERE email LIKE 'b%'", ColWrite, leaks = true),
    Refused("DELETE FROM secret WHERE id = 2 RETURNING email", ColWrite, leaks = true),
    Refused("DELETE FROM secret USING other o WHERE o.k = secret.email", ColWrite, leaks = true),
    Refused(
      "MERGE INTO secret t USING other o ON t.email = o.k WHEN MATCHED THEN UPDATE SET a = o.v",
      ColWrite,
      leaks = true
    ),
    Refused(
      "MERGE INTO secret t USING other o ON t.id = o.id WHEN MATCHED THEN UPDATE SET a = 1 " +
        "RETURNING t.email",
      ColWrite,
      leaks = true
    ),
    Refused(
      "INSERT INTO secret VALUES (2, 'bob', 0, 0, 'z') ON CONFLICT (id) " +
        "DO UPDATE SET a = length(email)",
      ColWrite,
      leaks = true
    ),
    Refused(
      "INSERT INTO secret VALUES (2, 'bob', 0, 0, 'z') ON CONFLICT (id) " +
        "DO UPDATE SET a = 1 WHERE email LIKE 'b%'",
      ColWrite,
      leaks = true
    ),
    // Returns only the value the caller supplied, but a RETURNING naming the column is refused.
    Refused(
      "INSERT INTO secret VALUES (3, 'carol', 30, 31, 'c@x') RETURNING email",
      ColWrite,
      false
    ),
    Refused(
      "UPDATE other SET v = 1 WHERE id = 1 RETURNING (SELECT max(email) FROM secret)",
      ReadInto,
      leaks = false
    )
  ) ++ positionalRefused ++ qualifiedRefused

  /** Review round 2, C1: DuckDB numbers `#n` over the target's columns (and, outside RETURNING,
    * over the target and the write's sources together), so `#5` here is `email`.
    */
  private def positionalRefused = List(
    Refused("UPDATE secret SET a = a RETURNING #5", ColWrite, leaks = true),
    Refused("UPDATE secret SET a = a WHERE #5 = 'bob@x' RETURNING id", ColWrite, leaks = true),
    Refused("UPDATE secret SET owner = #5", ColWrite, leaks = true),
    Refused("DELETE FROM secret WHERE #5 LIKE 'bob%'", ColWrite, leaks = true),
    Refused(
      "INSERT INTO secret VALUES (2, 'bob', 0, 0, 'z') ON CONFLICT (id) DO UPDATE SET a = 1 " +
        "RETURNING #5",
      ColWrite,
      leaks = true
    ),
    Refused(
      "MERGE INTO secret t USING other o ON t.id = o.id WHEN MATCHED THEN UPDATE SET a = 2 " +
        "RETURNING #5",
      ColWrite,
      leaks = true
    ),
    Refused(
      "UPDATE secret SET a = a FROM other o WHERE o.id = secret.id AND #5 = 'bob@x' " +
        "RETURNING secret.id",
      ColWrite,
      leaks = true
    ),
    Refused(
      "DELETE FROM secret USING other o WHERE o.id = secret.id AND #5 LIKE 'b%'",
      ColWrite,
      leaks = true
    ),
    Refused(
      "MERGE INTO secret t USING other o ON t.id = o.id AND #9 = 'bob@x' " +
        "WHEN MATCHED THEN UPDATE SET a = 3",
      ColWrite,
      leaks = true
    ),
    // `#5` there is the proposed row's value; refused all the same, as it names the column.
    Refused(
      "INSERT INTO secret VALUES (2, 'bob', 0, 0, 'z') ON CONFLICT (id) DO UPDATE SET a = length(#5)",
      ColWrite,
      leaks = false
    ),
    Refused(
      "UPDATE secret SET a = 0 WHERE EXISTS (SELECT 1 WHERE #5 = 'bob@x')",
      ColWrite,
      leaks = false
    )
  )

  /** Review round 2, C2: DuckDB reads `main.secret.email` as schema, table and column, whatever
    * alias `main` stands for elsewhere in the statement.
    */
  private def qualifiedRefused = List(
    Refused(
      "UPDATE secret SET a = 0 FROM other AS main WHERE main.secret.email = 'bob@x' " +
        "AND main.id = secret.id",
      ColWrite,
      leaks = true
    ),
    Refused(
      "DELETE FROM secret USING other AS main WHERE main.secret.email = 'bob@x'",
      ColWrite,
      leaks = true
    ),
    Refused(
      "MERGE INTO secret USING (SELECT 1 AS id) AS main ON main.secret.email = 'bob@x' " +
        "WHEN MATCHED THEN UPDATE SET a = 0",
      ColWrite,
      leaks = true
    ),
    Refused(
      "UPDATE secret SET a = 0 FROM other AS memory WHERE memory.main.secret.email = 'bob@x'",
      ColWrite,
      leaks = true
    ),
    Refused(
      "UPDATE secret SET a = a WHERE EXISTS (SELECT 1 FROM other AS main) " +
        "RETURNING main.secret.email",
      ColWrite,
      leaks = true
    ),
    Refused(
      "UPDATE secret SET a = a WHERE EXISTS (SELECT 1 FROM other AS main) RETURNING main.secret",
      ColWrite,
      leaks = true
    ),
    Refused(
      "INSERT INTO secret SELECT 2, 'bob', 0, 0, 'z' FROM other AS main WHERE main.id = 1 " +
        "ON CONFLICT (id) DO UPDATE SET a = length(main.secret.email)",
      ColWrite,
      leaks = true
    )
  )

  /** Writes to the column-policy table that read no protected column of it stay allowed. */
  private val colAllowed = List(
    "UPDATE secret SET email = 'x' WHERE id = 1",
    "UPDATE secret SET a = 0 WHERE id = 2 RETURNING id, a",
    "UPDATE secret SET email = o.k FROM other o WHERE o.id = secret.id",
    "UPDATE secret SET a = o.v FROM other o WHERE o.email = 'x@o' AND o.id = secret.id",
    "DELETE FROM secret WHERE id = 2",
    "DELETE FROM secret",
    "TRUNCATE secret",
    "MERGE INTO secret t USING other o ON t.id = o.id WHEN MATCHED THEN UPDATE SET email = o.email",
    "INSERT INTO secret VALUES (3, 'carol', 30, 31, 'c@x')",
    "INSERT INTO secret VALUES (2, 'bob', 0, 0, 'z') ON CONFLICT (id) " +
      "DO UPDATE SET email = excluded.email",
    "INSERT OR REPLACE INTO secret VALUES (2, 'bob', 0, 0, 'z')",
    // A column of another table of the statement, named like the protected one, is that table's.
    "UPDATE secret SET a = 1 FROM other WHERE other.email = 'x@o' AND other.id = secret.id",
    "MERGE INTO secret t USING (SELECT id, email FROM other) s ON t.id = s.id " +
      "WHEN MATCHED THEN UPDATE SET email = s.email",
    "WITH s AS (SELECT id, email FROM other) UPDATE secret SET email = s.email FROM s " +
      "WHERE s.id = secret.id",
    "DELETE FROM secret USING (SELECT id, email FROM other) o WHERE o.id = secret.id " +
      "AND o.email = 'y@o'",
    // A positional reference that names an unprotected column, or a column of another table.
    "UPDATE secret SET a = #3 WHERE id = 1",
    "UPDATE secret SET a = 0 WHERE id = 2 RETURNING #1",
    "DELETE FROM secret WHERE #1 = 2",
    "UPDATE secret SET a = 0 WHERE id IN (SELECT #1 FROM other)",
    // Qualified names that cannot be the protected column.
    "UPDATE secret SET a = 0 WHERE main.secret.id = 2",
    "UPDATE secret SET a = o.v FROM other AS o WHERE o.email = 'y@o' AND o.id = secret.id"
  )

  /** Owner ruling D29: DDL on a table the principal's own policies cover. Each form, run as is, and
    * a statement that shows what it did to the protected data; `leaks` says whether that statement
    * shows raw protected values or hidden rows gone.
    */
  private final case class Ddl(sql: String, after: String, leaks: Boolean)

  private val ddlRefused = List(
    Ddl("ALTER TABLE secret RENAME TO s2", "SELECT email FROM main.s2", leaks = true),
    Ddl("ALTER TABLE secret RENAME COLUMN email TO e2", "SELECT e2 FROM main.secret", true),
    Ddl(
      "ALTER TABLE secret ALTER COLUMN owner TYPE VARCHAR USING email",
      "SELECT owner FROM main.secret",
      leaks = true
    ),
    Ddl(
      "ALTER TABLE secret ALTER COLUMN email SET DATA TYPE VARCHAR USING upper(email)",
      "SELECT lower(email) FROM main.secret",
      leaks = true
    ),
    Ddl(
      "ALTER TABLE secret DROP COLUMN b",
      "SELECT count(*) FROM duckdb_columns() WHERE table_name = 'secret' AND column_name = 'b'",
      leaks = true
    ),
    Ddl(
      "ALTER TABLE secret ADD COLUMN z VARCHAR DEFAULT 'q'",
      "SELECT count(*) FROM main.secret WHERE owner = 'bob'",
      leaks = false
    ),
    Ddl("ALTER TABLE secret ALTER COLUMN a SET DEFAULT 0", "SELECT 1", leaks = false),
    Ddl("ALTER TABLE main.secret RENAME TO s2", "SELECT email FROM main.s2", leaks = true),
    Ddl(
      "DROP TABLE secret",
      "SELECT count(*) FROM duckdb_tables() WHERE table_name = 'secret'",
      leaks = true
    ),
    Ddl(
      "DROP TABLE IF EXISTS memory.main.secret",
      "SELECT count(*) FROM duckdb_tables() WHERE table_name = 'secret'",
      leaks = true
    ),
    Ddl(
      "CREATE OR REPLACE TABLE secret AS SELECT * FROM other",
      "SELECT count(*) FROM main.secret WHERE email = 'bob@x'",
      leaks = true
    ),
    Ddl(
      "CREATE OR REPLACE TABLE secret (id INTEGER)",
      "SELECT count(*) FROM main.secret",
      leaks = true
    )
  )

  /** DDL refused before this round already, as forms the guard cannot analyse. */
  private val ddlUnanalysed = List(
    "COMMENT ON TABLE secret IS 'x'",
    "COMMENT ON COLUMN secret.email IS 'x'",
    "CREATE INDEX i ON secret (email)"
  )

  private val ddlAllowed = List(
    "CREATE TABLE secret2 AS SELECT * FROM other",
    "ALTER TABLE other ADD COLUMN z INTEGER",
    "DROP TABLE other",
    "CREATE TABLE IF NOT EXISTS secret (id INTEGER)"
  )

  // ---- DuckDB ----

  private def seed(conn: Connection, maskedEmail: Boolean, rowFiltered: Boolean): Unit =
    exec(
      conn,
      "CREATE TABLE main.secret (id INTEGER PRIMARY KEY, owner VARCHAR, a INTEGER, b INTEGER, " +
        "email VARCHAR)"
    )
    exec(conn, "INSERT INTO main.secret VALUES (1, 'alice', 10, 11, 'alice@x')")
    if !rowFiltered then exec(conn, "INSERT INTO main.secret VALUES (2, 'bob', 20, 21, 'bob@x')")
    if maskedEmail then exec(conn, "UPDATE main.secret SET email = '***'")
    exec(
      conn,
      "CREATE TABLE main.other (id INTEGER PRIMARY KEY, v INTEGER, k VARCHAR, email VARCHAR)"
    )
    exec(
      conn,
      "INSERT INTO main.other VALUES (1, 100, 'alice@x', 'x@o'), (2, 200, 'bob@x', 'y@o')"
    )

  /** What the statement answers and what it leaves, on a fresh DuckDB; an error reads as such. */
  private def outcome(
      sql: String,
      maskedEmail: Boolean = false,
      rowFiltered: Boolean = false
  ): (String, List[List[String]], List[List[String]]) =
    withDuckDb("memory") { conn =>
      seed(conn, maskedEmail, rowFiltered)
      val answer =
        try
          Using.resource(conn.createStatement()) { st =>
            if st.execute(sql) then
              Using.resource(st.getResultSet) { rs =>
                val n   = rs.getMetaData.getColumnCount
                val buf = ListBuffer.empty[String]
                while rs.next() do buf += (1 to n).map(i => rs.getString(i)).mkString(",")
                buf.mkString(" | ")
              }
            else s"count=${st.getUpdateCount}"
          }
        catch case t: java.sql.SQLException => s"error: ${t.getMessage}"
      (
        answer,
        query(conn, "SELECT id, owner, a, b, email FROM main.secret ORDER BY id"),
        query(conn, "SELECT id, v, k, email FROM main.other ORDER BY id")
      )
    }

  private val bobSeed = List("2", "bob", "20", "21", "bob@x")

  /** A row-policy leak: the statement changes or removes the hidden row, or answers or writes
    * anything else differently from the same statement over the rows the policy shows.
    */
  private def rowLeaks(sql: String): Boolean =
    val real                              = outcome(sql)
    val shown                             = outcome(sql, rowFiltered = true)
    def visible(rows: List[List[String]]) = rows.filterNot(_.headOption.contains("2"))
    !real._1.startsWith("error") &&
    (!real._2.contains(bobSeed) || real._1 != shown._1 || real._3 != shown._3 ||
      visible(real._2) != visible(shown._2))

  /** A column-policy leak: the statement answers or writes differently once the protected column is
    * masked, or its answer carries a raw value of it.
    */
  private def colLeaks(sql: String): Boolean =
    val real                                   = outcome(sql)
    val masked                                 = outcome(sql, maskedEmail = true)
    def withoutEmail(rows: List[List[String]]) = rows.map(_.take(4))
    !real._1.startsWith("error") &&
    (real._1.contains("alice@x") || real._1.contains("bob@x") || real._1 != masked._1 ||
      withoutEmail(real._2) != withoutEmail(masked._2) || real._3 != masked._3)

  // ---- checks ----

  private def guarded(sql: String, e: EffectiveSet): GuardOutcome =
    guard.check(sql, StatementClassifier.classify(sql), e, ctx)

  private def piped(sql: String, e: EffectiveSet): Either[RouterFailure, String] =
    harness(Memory, catalog, guarded = true).run(sql, e)

  "a row-policy holder's write on the policy table" should "be refused by the guard" in
    rowRefused.foreach { f =>
      withClue(f.sql) {
        guarded(f.sql, rowEff) match
          case GuardOutcome.Deny(reason) =>
            reason should include(f.reason)
            reason should include("secret")
          case other => fail(s"expected a refusal, got $other")
      }
    }

  it should "be refused by the router before it reaches a node" in
    rowRefused.foreach { f =>
      withClue(f.sql) {
        piped(f.sql, rowEff) match
          case Left(RouterFailure.AccessDenied(reason)) => reason should include(f.reason)
          case other                                    => fail(s"expected a refusal, got $other")
      }
    }

  it should "reach the hidden rows on DuckDB, which is what the refusal stops" in
    rowRefused.foreach { f =>
      withClue(s"${f.sql} -> ${outcome(f.sql)}") {
        rowLeaks(f.sql) shouldBe f.leaks
      }
    }

  "a row-policy holder's plain INSERT of new rows" should "still be allowed and sent as written" in
    rowAllowed.foreach { sql =>
      withClue(sql) {
        guarded(sql, rowEff) shouldBe GuardOutcome.Allow
        piped(sql, rowEff) match
          case Right(sent) =>
            sent should endWith(sql)
            rowLeaks(sql) shouldBe false
          case Left(f) => fail(s"refused: $f")
      }
    }

  "a column-policy holder's write reading the protected column" should "be refused by the guard" in {
    for e <- List(maskEff, denyEff); f <- colRefused do
      withClue(s"${e.columnPolicies.head.action}: ${f.sql}") {
        guarded(f.sql, e) match
          case GuardOutcome.Deny(reason) =>
            reason should include(f.reason)
            reason should include("secret")
          case other => fail(s"expected a refusal, got $other")
      }
  }

  it should "be refused by the router before it reaches a node" in {
    for e <- List(maskEff, denyEff); f <- colRefused do
      withClue(s"${e.columnPolicies.head.action}: ${f.sql}") {
        piped(f.sql, e) match
          case Left(RouterFailure.AccessDenied(reason)) => reason should include(f.reason)
          case other                                    => fail(s"expected a refusal, got $other")
      }
  }

  it should "read the raw value on DuckDB, which is what the refusal stops" in
    colRefused.foreach { f =>
      withClue(s"${f.sql} -> ${outcome(f.sql)} / ${outcome(f.sql, maskedEmail = true)}") {
        colLeaks(f.sql) shouldBe f.leaks
      }
    }

  "a column-policy holder's write reading no protected column" should "still be allowed" in {
    for e <- List(maskEff, denyEff); sql <- colAllowed do
      withClue(s"${e.columnPolicies.head.action}: $sql") {
        guarded(sql, e) shouldBe GuardOutcome.Allow
        piped(sql, e) match
          case Right(sent) =>
            sent should endWith(sql)
            colLeaks(sql) shouldBe false
          case Left(f) => fail(s"refused: $f")
      }
  }

  "a column-policy holder's ON CONFLICT on the protected column" should "be refused" in {
    for e <- List(maskEff, denyEff) do
      guarded(
        "INSERT INTO secret VALUES (3, 'carol', 0, 0, 'c@x') ON CONFLICT (email) DO NOTHING",
        e
      ) match
        case GuardOutcome.Deny(reason) => reason should include(ColWrite)
        case other                     => fail(s"expected a refusal, got $other")
  }

  "DDL on a policy table" should "be refused to a holder of a row or column policy on it" in {
    for e <- List(rowEff, maskEff, denyEff); d <- ddlRefused do
      withClue(s"${e.rowPolicies.size}/${e.columnPolicies.size}: ${d.sql}") {
        guarded(d.sql, e) match
          case GuardOutcome.Deny(reason) => reason should include(DdlRefusal)
          case other                     => fail(s"expected a refusal, got $other")
        piped(d.sql, e) match
          case Left(RouterFailure.AccessDenied(reason)) =>
            reason should include(DdlRefusal)
            reason should include("secret")
          case other => fail(s"expected a refusal, got $other")
      }
    for e <- List(rowEff, maskEff, denyEff); sql <- ddlUnanalysed do
      withClue(sql)(guarded(sql, e) shouldBe a[GuardOutcome.Deny])
  }

  it should "reach the protected data on DuckDB, which is what the refusal stops" in
    ddlRefused.foreach { d =>
      val shown = withDuckDb("memory") { conn =>
        seed(conn, maskedEmail = false, rowFiltered = false)
        exec(conn, d.sql)
        query(conn, d.after).flatten
      }
      withClue(s"${d.sql} -> $shown") {
        (shown.exists(v => v != null && v.toLowerCase.contains("bob@x")) || shown == List(
          "0"
        )) shouldBe
          d.leaks
      }
    }

  it should "leave other tables' DDL, and a create that replaces nothing, alone" in {
    for e <- List(rowEff, maskEff, denyEff); sql <- ddlAllowed do
      withClue(sql)(guarded(sql, e) shouldBe GuardOutcome.Allow)
    (ddlRefused.map(_.sql) ++ ddlUnanalysed).foreach { sql =>
      withClue(sql)(guarded(sql, effWith()) shouldBe GuardOutcome.Allow)
    }
  }

  "a write on a policy table" should "be left to the node for a principal with no policy" in
    (rowRefused.map(_.sql) ++ colRefused.map(_.sql)).foreach { sql =>
      withClue(sql)(guarded(sql, effWith()) shouldBe GuardOutcome.Allow)
    }

  it should "be left alone when row or column policies are not enforced" in {
    val off = new ProtectedWriteGuard(catalog, clsEnabled = false, rlsEnabled = false)
    off.check(
      "UPDATE secret SET a = 0",
      ai.starlake.quack.model.StatementKind.Dml,
      rowEff,
      ctx
    ) shouldBe GuardOutcome.Allow
    off.check(
      "UPDATE secret SET a = 0 WHERE email = 'bob@x'",
      ai.starlake.quack.model.StatementKind.Dml,
      maskEff,
      ctx
    ) shouldBe GuardOutcome.Allow
  }
