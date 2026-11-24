package ai.starlake.quack.edge.rls

import ai.starlake.quack.edge.rls.StaleRowPolicyReport.Database
import ai.starlake.quack.ondemand.state.{RbacRole, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StaleRowPolicyReportSpec extends AnyFlatSpec with Matchers:

  private val role  = RbacRole("r-1", "acme", "eu_readers")
  private val other = RbacRole("r-2", "globex", "readers")
  private val lake  = Database("acme", "acme_lake", "acme_lake")
  private val sales = Database("acme", "acme_sales", "sales")
  private val glx   = Database("globex", "globex_lake", "globex_lake")

  private val columns: Map[(String, String, String), List[String]] = Map(
    ("acme_lake", "main", "orders")   -> List("id", "Region", "amount"),
    ("acme_sales", "main", "orders")  -> List("id", "country"),
    ("globex_lake", "main", "orders") -> List("id")
  )
  private def columnsOf(d: Database, schema: String, table: String): List[String] =
    columns.getOrElse((d.name, schema, table), Nil)

  private def policy(
      id: String,
      predicate: String,
      catalog: String = "*",
      schema: String = "main",
      table: String = "orders",
      roleId: String = "r-1"
  ) = RoleRowPolicy(id, roleId, catalog, schema, table, predicate)

  private def report(policies: RoleRowPolicy*): List[String] =
    StaleRowPolicyReport.lines(
      List(role, other),
      policies.toList,
      List(lake, sales, glx),
      columnsOf
    )

  "the stale row policy report" should "say nothing of a policy whose columns the table has" in {
    report(policy("rp-1", "region = 'eu' AND ${user} IS NOT NULL", catalog = "acme_lake")) shouldBe
      Nil
  }

  it should "name the policy, role, table and missing columns, never the predicate" in {
    val lines = report(policy("rp-1", "region = 'eu' AND owner = 'x'", catalog = "acme_lake"))
    lines shouldBe List(
      "WARN: tenant 'acme' role 'eu_readers': row policy rp-1 on acme_lake.main.orders names a " +
        "column the table does not have: owner. Every statement reading the table under this " +
        "role is denied (\"row policy failed to apply\"): update the policy, or delete it."
    )
    lines.head should not include "'eu'"
  }

  it should "check a wildcard catalog against each database of the role's tenant that holds the table" in {
    val lines = report(policy("rp-1", "region = 'eu'"))
    // acme_sales.main.orders has no region; acme_lake's has; globex is another tenant.
    lines.size shouldBe 1
    lines.head should include("row policy rp-1 on sales.main.orders")
    lines.head should include("region.")
  }

  it should "report a predicate that cannot be applied at all" in {
    val lines = report(policy("rp-1", "region IN (SELECT code FROM codes)", catalog = "acme_lake"))
    lines.size shouldBe 1
    lines.head should include("cannot be applied: its predicate holds a subquery")
  }

  it should "skip a wildcard schema or table, a table no database holds, and an unknown role" in {
    report(
      policy("rp-1", "nope = 1", schema = "*"),
      policy("rp-2", "nope = 1", table = "*"),
      policy("rp-3", "nope = 1", table = "missing"),
      policy("rp-4", "nope = 1", roleId = "r-gone")
    ) shouldBe Nil
  }

  it should "report unsupported stored predicates even on wildcard or absent tables" in {
    val lines = StaleRowPolicyReport.invalidLines(
      List(role),
      List(
        policy("rp-wild", "getvariable('secret') = 1", schema = "*", table = "*"),
        policy("rp-missing", "owner = ${unknown}", table = "missing"),
        policy("rp-good", "owner = ${user}", table = "missing")
      )
    )
    lines.size shouldBe 2
    lines.mkString should include("rp-wild")
    lines.mkString should include("rp-missing")
    lines.mkString should not include "secret"
    lines.mkString should not include "unknown"
    lines.mkString should not include "rp-good"
  }
