package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.cls.{ColumnCatalog, SchemaContext}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The row-policy rewriter and the access checks read a CTE name the way JSqlParser unquotes it,
  * which for these spellings is another name than DuckDB's. The rewriter wraps the table beside
  * such a CTE, but the access checks would take the table for the CTE. What keeps such a statement
  * from a row-policy holder is the guard's screen of quoted names, which runs ahead of the
  * rewriters: this pins it in CTE-name position.
  */
class ProtectedWriteGuardCteSpellingSpec extends AnyFlatSpec with Matchers:
  import GuardOutcome._

  private val user = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
  private val rowPolicy = RoleRowPolicy("rp-1", "r-1", "*", "tpch1", "secret", "region = 'eu'")
  private val ctx       =
    SchemaContext(Some("acme_tpch"), Some("tpch1"), attachedCatalogs = Set.empty)
  private val guard =
    new ProtectedWriteGuard(
      new ColumnCatalog.MapCatalog(Map.empty),
      clsEnabled = true,
      rlsEnabled = true
    )

  private val spellings = List(
    "\"secret\"\"\"",
    "\"\"\"secret\"",
    "`secret\"`",
    "`\"secret`",
    "\"[secret\"",
    "`[secret`",
    "\"secret]\"",
    "`secret]`",
    "\"secret`\"",
    "\"`secret\"",
    "`secret```",
    "```secret`"
  )

  for spelling <- spellings do
    s"a CTE spelled $spelling" should "be refused to a row-policy holder" in {
      val sql = s"WITH $spelling AS (SELECT 5 AS id, 'q' AS region) SELECT * FROM secret"
      guard.check(
        sql,
        StatementKind.Select,
        EffectiveSet(user, Nil, Nil, Nil, Nil, Nil, List(rowPolicy)),
        ctx
      ) shouldBe a[Deny]
    }
