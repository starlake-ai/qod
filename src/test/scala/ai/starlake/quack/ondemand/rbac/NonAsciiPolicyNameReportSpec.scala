package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  RbacRole,
  RoleColumnPolicy,
  RolePermission,
  RoleRowPolicy
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

/** Grants and policies whose names hold a non-ASCII letter with another case now match as DuckDB
  * binds names (owner ruling D21): each such row is reported at boot, naming tenant, role and
  * object, never a mask expression or a row predicate.
  */
class NonAsciiPolicyNameReportSpec extends AnyFlatSpec with Matchers:

  private val analyst = RbacRole("r-1", "acme", "analyst")
  private val other   = RbacRole("r-2", "globex", "auditor")

  "holdsCasedNonAscii" should "flag only non-ASCII letters that have another case" in {
    List("Clés", "CLÉS", "Kpi", "ſecret", "ınfo", "ΣΥΦ").foreach(n =>
      withClue(n)(NonAsciiPolicyNameReport.holdsCasedNonAscii(n) shouldBe true)
    )
    List("orders", "ORDERS", "*", "日本", "tab_1", "", null).foreach(n =>
      withClue(String.valueOf(n))(NonAsciiPolicyNameReport.holdsCasedNonAscii(n) shouldBe false)
    )
  }

  "lines" should "name the tenant, the role and the object of each such row, and nothing else" in {
    val lines = NonAsciiPolicyNameReport.lines(
      List(analyst, other),
      List(
        RolePermission("p-1", "r-1", "acme_lake", "main", "Clés", "RO"),
        RolePermission("p-2", "r-1", "acme_lake", "main", "orders", "RO")
      ),
      List(
        RoleColumnPolicy("c-1", "r-2", "*", "main", "users", "émail", "mask", Some("'TOPSECRET'")),
        RoleColumnPolicy("c-2", "r-2", "*", "main", "users", "email", "deny", None)
      ),
      List(RoleRowPolicy("rp-1", "r-1", "*", "Ventes", "Données", "region = 'HIDDEN'"))
    )
    lines.size shouldBe 3
    lines.foreach(_ should startWith("WARN: tenant '"))
    lines.exists(l =>
      l.contains("tenant 'acme' role 'analyst'") && l.contains("grant on acme_lake.main.Clés")
    ) shouldBe true
    lines.exists(l =>
      l.contains("tenant 'globex' role 'auditor'") && l.contains(
        "column policy on *.main.users.émail"
      )
    ) shouldBe true
    lines.exists(l => l.contains("row policy on *.Ventes.Données")) shouldBe true
    lines.mkString("\n") should not include "TOPSECRET"
    lines.mkString("\n") should not include "HIDDEN"
    lines.mkString("\n") should not include "orders"
  }

  "linesOf" should "read every tenant's roles from the store" in {
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant("acme"))
    store.upsertRole(analyst)
    store.insertRolePermission(RolePermission("p-1", "r-1", "acme_lake", "main", "Clés", "RO"))
    store.insertRowPolicy(RoleRowPolicy("rp-1", "r-1", "*", "main", "Clés", "id = 1"))
    store.insertColumnPolicy(
      RoleColumnPolicy("c-1", "r-1", "*", "main", "Clés", "secret", "mask", Some("'x'"))
    )
    NonAsciiPolicyNameReport.linesOf(store).size shouldBe 3
  }

  it should "run at every boot" in {
    val main = new String(
      Files.readAllBytes(Paths.get("src/main/scala/ai/starlake/quack/Main.scala")),
      java.nio.charset.StandardCharsets.UTF_8
    )
    main should include("NonAsciiPolicyNameReport.report(store)")
  }
