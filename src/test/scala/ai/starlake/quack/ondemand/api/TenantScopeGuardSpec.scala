package ai.starlake.quack.ondemand.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantScopeGuardSpec extends AnyFlatSpec, Matchers:

  import TenantScopeGuard.extractTenant

  /** A plain path's segments, as the guard and the router read them: the leading `/` dropped, and
    * `split` dropping trailing empty segments, so a trailing slash adds none.
    */
  private def seg(path: String): Vector[String] = path.dropWhile(_ == '/').split("/").toVector

  "extractTenant" should "pull a tenant from /api/pool/:tenant/:tdb/:pool/status" in {
    extractTenant(seg("/api/pool/t-abc/td1/p1/status"), queryTenant = None) shouldBe Some("t-abc")
  }

  it should "pull from /api/catalog/tenant/:tenant/database/..." in {
    extractTenant(
      seg("/api/catalog/tenant/t-abc/database/td1/schemas"),
      queryTenant = None
    ) shouldBe Some("t-abc")
  }

  it should "pull from /api/branch/tenant/:tenant/..." in {
    extractTenant(
      seg("/api/branch/tenant/t-abc/database/td1/branches"),
      queryTenant = None
    ) shouldBe Some("t-abc")
  }

  it should "pull from /api/tenants/:tenant/tenant-dbs/..." in {
    extractTenant(
      seg("/api/tenants/t-abc/tenant-dbs/td1/federated-sources"),
      queryTenant = None
    ) shouldBe Some("t-abc")
  }

  it should "pull from /api/scim/v2/:tenant with or without a resource" in {
    extractTenant(seg("/api/scim/v2/t-abc"), queryTenant = None) shouldBe Some("t-abc")
    extractTenant(seg("/api/scim/v2/t-abc/Users"), queryTenant = None) shouldBe Some("t-abc")
  }

  it should "pull from query string when no path tenant" in {
    extractTenant(seg("/api/database/list"), queryTenant = Some("t-q")) shouldBe Some("t-q")
  }

  it should "return None when no tenant is encoded" in {
    extractTenant(seg("/api/pool/list"), queryTenant = None) shouldBe None
  }

  it should "ignore /api/auth/login (public)" in {
    extractTenant(seg("/api/auth/login"), queryTenant = None) shouldBe None
  }

  it should "leave the query tenant to the endpoint on the exempt routes" in {
    for p <- List("/api/audit/list", "/api/history/trends", "/api/history/statements", "/api/usage")
    do withClue(p)(extractTenant(seg(p), queryTenant = Some("victim")) shouldBe None)
  }

  it should "require a segment after the tenant where the arm always did" in {
    // `/api/catalog/tenant/<t>` and `/api/tenants/<t>/tenant-dbs` name no route of their own: the
    // query tenant decides there, as it always did.
    extractTenant(seg("/api/catalog/tenant/mine"), Some("victim")) shouldBe Some("victim")
    extractTenant(seg("/api/branch/tenant/mine"), Some("victim")) shouldBe Some("victim")
    extractTenant(seg("/api/tenants/mine/tenant-dbs"), Some("victim")) shouldBe Some("victim")
    extractTenant(seg("/api/pool/mine/td/p"), Some("victim")) shouldBe Some("victim")
    extractTenant(seg("/api/scim/v2"), Some("victim")) shouldBe Some("victim")
  }

  it should "read a trailing slash as the router does, adding no segment" in {
    // The router (and the guard's `segments`) drops a trailing empty segment, so
    // `/api/catalog/tenant/mine/` is the request `/api/catalog/tenant/mine`.
    seg("/api/catalog/tenant/mine/") shouldBe Vector("api", "catalog", "tenant", "mine")
    extractTenant(seg("/api/catalog/tenant/mine/"), Some("victim")) shouldBe Some("victim")
    extractTenant(seg("/api/catalog/tenant/mine/x/"), Some("victim")) shouldBe Some("mine")
    extractTenant(seg("/api/scim/v2/mine/"), None) shouldBe Some("mine")
  }

  it should "take no empty segment as a tenant" in {
    extractTenant(Vector("api", "catalog", "tenant", "", "x"), Some("victim")) shouldBe
      Some("victim")
    extractTenant(Vector("api", "pool", "", "td", "p", "status"), Some("victim")) shouldBe
      Some("victim")
  }

  // ---- encoded slashes: a segment holding `/` is one segment, as the router reads it ----

  it should "not read `catalog/tenant` held in one segment as the catalog arm" in {
    extractTenant(
      Vector("api", "catalog/tenant", "mine", "x"),
      queryTenant = Some("victim")
    ) shouldBe Some("victim")
  }

  it should "not read `audit/list` held in one segment as an exempt route" in {
    extractTenant(Vector("api", "audit/list"), queryTenant = Some("victim")) shouldBe
      Some("victim")
  }

  it should "give the tenant segment as it is, slash included" in {
    extractTenant(Vector("api", "catalog", "tenant", "mine/x", "t"), queryTenant = None) shouldBe
      Some("mine/x")
  }

  it should "not read `scim/v2` held in one segment as the SCIM arm" in {
    extractTenant(Vector("api", "scim/v2", "mine", "Users"), queryTenant = Some("victim")) shouldBe
      Some("victim")
  }
