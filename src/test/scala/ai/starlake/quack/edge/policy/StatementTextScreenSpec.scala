package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.cls.{ColumnCatalog, SchemaContext}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The node runs the rewritten statement, so a function returning its text shows the policies
  * applied to it. The guard refuses those functions to a principal with column or row policies.
  */
class StatementTextScreenSpec extends AnyFlatSpec with Matchers:
  import GuardOutcome._

  private val user  = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
  private val ctx   = SchemaContext(Some("acme_tpch"), Some("tpch1"), attachedCatalogs = Set.empty)
  private val guard =
    new ProtectedWriteGuard(
      new ColumnCatalog.MapCatalog(Map.empty),
      clsEnabled = true,
      rlsEnabled = true
    )
  private val rowPolicy = RoleRowPolicy("rp-1", "r-1", "*", "tpch1", "secret", "region = 'eu'")
  private val mask      =
    RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "secret", "ssn", "mask", Some("'***'"))

  private def check(sql: String, rows: List[RoleRowPolicy], cols: List[RoleColumnPolicy]) =
    guard.check(sql, StatementKind.Select, EffectiveSet(user, Nil, Nil, Nil, Nil, cols, rows), ctx)

  private val statements = List(
    "SELECT current_query()",
    "SELECT CURRENT_QUERY()",
    "SELECT \"current_query\"()",
    "SELECT pg_catalog.current_query()",
    "SELECT * FROM secret WHERE current_query() LIKE '%x%'",
    "SELECT json_serialize_sql(current_query())",
    "SELECT * FROM duckdb_prepared_statements()",
    "SELECT * FROM duckdb_prepared_statements",
    "SELECT * FROM pg_prepared_statements",
    "SELECT * FROM pg_catalog.pg_prepared_statements",
    "SELECT * FROM query('SELECT current_query()')"
  )

  for sql <- statements do
    s"$sql" should "be refused to a row-policy holder" in {
      check(sql, List(rowPolicy), Nil) shouldBe a[Deny]
    }

    it should "be refused to a column-policy holder" in {
      check(sql, Nil, List(mask)) shouldBe a[Deny]
    }

  "a statement with none of them" should "pass the screen" in {
    StatementTextScreen.find("SELECT current_query_id(), 'current_query' AS q FROM secret") shouldBe
      None
  }

  "a principal with no policy" should "not be screened" in {
    check("SELECT current_query()", Nil, Nil) shouldBe Allow
  }
