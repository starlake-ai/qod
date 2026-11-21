package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PlatformRoleStoreSpec extends AnyFlatSpec with Matchers:

  private def postgres(): ControlPlaneStore =
    TestPostgres.ensureReachable()
    val dbName = s"qodpr_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    val url = TestPostgres.dbUrl(dbName)
    new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
    new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)

  private def cases(label: String, mk: () => ControlPlaneStore): Unit =
    s"$label platform roles" should "round-trip a role and its permissions" in {
      val s = mk()
      val r = s.insertPlatformRole(
        "support",
        Some("helps"),
        Set("hosted.tenant.read", "hosted.quota.extra")
      )
      s.getPlatformRole(r.id) shouldBe Some(r)
      s.findPlatformRoleByName("support").map(_.permissions) shouldBe Some(
        Set("hosted.tenant.read", "hosted.quota.extra")
      )
      s.updatePlatformRole(r.id, "support2", None, Set("hosted.tenant.read"))
        .map(_.permissions) shouldBe Some(Set("hosted.tenant.read"))
      s.listPlatformRoles().map(_.name) shouldBe List("support2")
      s.deletePlatformRole(r.id) shouldBe true
      s.listPlatformRoles() shouldBe Nil
    }

    it should "union the permissions of a staff user's roles and cascade on delete" in {
      val s   = mk()
      val uid =
        s.upsertUserWithHash(tenant = None, username = "sam", passwordHash = "x", kind = "staff")
      val a = s.insertPlatformRole("a", None, Set("m.one"))
      val b = s.insertPlatformRole("b", None, Set("m.two", "m.one"))
      s.setUserPlatformRoles(uid, Set(a.id, b.id))
      s.listUserPlatformRoleIds(uid).toSet shouldBe Set(a.id, b.id)
      s.platformPermissionsOfUser(uid) shouldBe Set("m.one", "m.two")
      s.deletePlatformRole(a.id)
      s.platformPermissionsOfUser(uid) shouldBe Set("m.one", "m.two")
      s.setUserPlatformRoles(uid, Set.empty)
      s.platformPermissionsOfUser(uid) shouldBe Set.empty
    }

  cases("Postgres", () => postgres())
  cases("InMemory", () => new InMemoryControlPlaneStore())

  "the kind check" should "refuse a staff row with a tenant" in {
    val s = postgres()
    // any existing tenant row works; create one through the store's upsertTenant
    s.upsertTenant(
      ai.starlake.quack.model.Tenant(id = "t1", displayName = "t1", authProvider = "db")
    )
    an[Exception] should be thrownBy
      s.upsertUserWithHash(tenant = Some("t1"), username = "x", passwordHash = "x", kind = "staff")
  }
