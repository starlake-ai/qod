package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.cls.{ColumnCatalog, SchemaContext}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Row-only reads are bound by the native engine, which resolves CTE names itself. */
class ProtectedWriteGuardCteSpellingSpec extends AnyFlatSpec with Matchers:
  import GuardOutcome._

  private val user = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
  private val rowPolicy = RoleRowPolicy("rp-1", "r-1", "*", "tpch1", "secret", "region = 'eu'")
  private val ctx       =
    SchemaContext(Some("acme_tpch"), Some("tpch1"), attachedCatalogs = Set.empty)
  private val guard =
    new ProtectedWriteGuard(
      new ColumnCatalog.MapCatalog(),
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
    s"a CTE spelled $spelling" should "reach native binding for a row-policy holder" in {
      val sql = s"WITH $spelling AS (SELECT 5 AS id, 'q' AS region) SELECT * FROM secret"
      guard.check(
        sql,
        StatementKind.Select,
        EffectiveSet(user, Nil, Nil, Nil, Nil, Nil, List(rowPolicy)),
        ctx
      ) shouldBe Allow
    }
