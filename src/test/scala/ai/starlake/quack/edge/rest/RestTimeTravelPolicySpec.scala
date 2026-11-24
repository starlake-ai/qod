package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.cls.ColumnCatalog
import ai.starlake.quack.ondemand.state.{RoleColumnPolicy, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The gateway preserves snapshots for native row filtering; only column masking changes SQL. */
class RestTimeTravelPolicySpec extends AnyFlatSpec with Matchers:
  import ai.starlake.quack.edge.policy.PolicyPipelineFixture.*
  private val kc   = DuckLake
  private val Ref  = s"\"${kc.catalog}\".\"main\".\"customer\""
  private val At   = "AT (VERSION => 3)"
  private val mask =
    RoleColumnPolicy("cp-1", "r-1", "*", "main", "customer", "c_email", "mask", Some("'***'"))
  private val row     = RoleRowPolicy("rp-1", "r-1", "*", "main", "customer", "c_region = 'X'")
  private val catalog = new ColumnCatalog.MapCatalog(
    stable = Set((kc.catalog, "main", "customer"))
  )
  private val pipeline   = harness(kc, catalog, guarded = true)
  private val statements = List(
    s"SELECT * FROM $Ref LIMIT 0",
    s"SELECT c_id, c_email FROM $Ref WHERE c_id >= 2 ORDER BY c_id DESC LIMIT 101",
    s"SELECT * FROM $Ref $At LIMIT 0",
    s"SELECT c_id, c_email FROM $Ref $At WHERE c_id >= 2 ORDER BY c_id DESC LIMIT 101"
  )

  "REST SQL with row policies" should "reach DuckDB without a gateway row-filter wrapper" in
    statements.foreach { sql =>
      val sent =
        pipeline.run(sql, effWith(rowPolicies = List(row))).fold(f => fail(f.toString), identity)
      sent should include(sql)
      sent should not include "__qod_rls_"
      sent should not include row.predicateSql
    }

  it should "retain each snapshot clause once without embedding policies" in
    statements.foreach { sql =>
      val sent = pipeline
        .run(sql, effWith(columnPolicies = List(mask), rowPolicies = List(row)))
        .fold(f => fail(f.toString), identity)
      sent should include(sql)
      sent should not include "'***'"
      sent should not include row.predicateSql
      if sql.contains(At) then {
        sent should include(s"$Ref $At")
        sent.split(java.util.regex.Pattern.quote(At), -1).length - 1 shouldBe 1
      }
    }

  it should "still refuse ambiguous snapshot placement required by column masking" in {
    val sql = s"SELECT * FROM (SELECT * FROM $Ref) d $At"
    pipeline.run(sql, effWith(columnPolicies = List(mask))) shouldBe a[Left[?, ?]]
  }

  it should "still refuse column-masked historical names whose bindings changed" in {
    val unstable = harness(
      kc,
      new ColumnCatalog.MapCatalog(),
      guarded = true
    )
    unstable.run(s"SELECT * FROM $Ref $At", effWith(columnPolicies = List(mask))) match
      case Left(RouterFailure.AccessDenied(reason)) =>
        reason should include("cannot be read with time travel")
      case other => fail(s"expected a refusal, got $other")
  }
