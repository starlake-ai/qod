package ai.starlake.quack.edge.policy

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TimeTravelCarrierSpec extends AnyFlatSpec with Matchers:
  "historical table placement" should "read fully scoped coordinates without changing SQL" in {
    TimeTravelCarrier.pinnedTables(
      "SELECT * FROM lake.main.a x AT (VERSION => 1) JOIN b AT (VERSION => 2) ON true"
    ) shouldBe
      Some(List((Some("lake"), Some("main"), "a"), (None, None, "b")))
  }

  it should "handle line breaks, tabs and surrogate pairs" in {
    TimeTravelCarrier.pinnedTables(
      "SELECT '😀\t' AS e\r\nFROM\ta\tx\rAT (VERSION => 1)\n, b AT (VERSION => 2)"
    ) shouldBe
      Some(List((None, None, "a"), (None, None, "b")))
  }

  it should "ignore clause text inside literals" in {
    TimeTravelCarrier.pinnedTables("SELECT 'AT (VERSION => 1)' FROM t") shouldBe Some(Nil)
  }

  it should "refuse clauses without one distinct table reference" in
    List(
      "SELECT * FROM (SELECT 1) d AT (VERSION => 1)",
      "SELECT * FROM a /* c */ AT (VERSION => 1)",
      "SELECT * FROM a AT (VERSION => 1) AT (VERSION => 2)"
    ).foreach { sql =>
      withClue(sql)(TimeTravelCarrier.pinnedTables(sql) shouldBe None)
    }

  // The carrier copies a clause back verbatim, unseen by either policy rewriter. That holds only
  // while DuckDB refuses to read data inside an AT clause; in-process DuckDB (core) binds the clause
  // before it finds out the catalog has no time travel, so the refusal is observable here.
  "DuckDB" should "refuse subqueries and column names inside an AT clause" in {
    Class.forName("org.duckdb.DuckDBDriver")
    scala.util.Using.resource(java.sql.DriverManager.getConnection("jdbc:duckdb:")) { conn =>
      def error(sql: String): String =
        scala.util.Using.resource(conn.createStatement()) { st =>
          intercept[java.sql.SQLException](st.executeQuery(sql)).getMessage
        }
      scala.util.Using.resource(conn.createStatement())(_.execute("CREATE TABLE t (a INTEGER)"))
      error("SELECT * FROM t AT (VERSION => (SELECT max(a) FROM t))") should
        include("AT clause cannot contain subqueries")
      error("SELECT * FROM t AT (TIMESTAMP => (FROM t))") should
        include("AT clause cannot contain subqueries")
      error("SELECT * FROM t AT (VERSION => a)") should include("AT clause cannot contain column")
    }
  }
