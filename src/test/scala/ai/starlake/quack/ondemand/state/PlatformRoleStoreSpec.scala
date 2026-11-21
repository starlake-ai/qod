package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

class PlatformRoleStoreSpec extends AnyFlatSpec with Matchers:

  private def withPostgres(f: ControlPlaneStore => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodpr_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    var store: Option[PostgresControlPlaneStore] = None
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val st = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      store = Some(st)
      f(st)
    finally
      store.foreach(st => Try(st.close()))
      Try(TestPostgres.psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  private def withMemory(f: ControlPlaneStore => Unit): Unit = f(new InMemoryControlPlaneStore())

  private def staff(s: ControlPlaneStore, name: String): String =
    s.upsertUserWithHash(
      tenant = None,
      username = name,
      passwordHash = "x",
      kind = UserKind.Staff
    )

  private def cases(label: String, run: (ControlPlaneStore => Unit) => Unit): Unit =
    s"$label platform roles" should "round-trip a role and its permissions" in run { s =>
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

    it should "union the permissions of a staff user's roles and cascade on role delete" in run {
      s =>
        val uid = staff(s, "sam")
        val a   = s.insertPlatformRole("a", None, Set("m.one"))
        val b   = s.insertPlatformRole("b", None, Set("m.two", "m.one"))
        s.setUserPlatformRoles(uid, Set(a.id, b.id))
        s.listUserPlatformRoleIds(uid).toSet shouldBe Set(a.id, b.id)
        s.platformPermissionsOfUser(uid) shouldBe Set("m.one", "m.two")
        s.deletePlatformRole(a.id)
        s.listUserPlatformRoleIds(uid) shouldBe List(b.id)
        s.platformPermissionsOfUser(uid) shouldBe Set("m.one", "m.two")
        s.setUserPlatformRoles(uid, Set.empty)
        s.platformPermissionsOfUser(uid) shouldBe Set.empty
    }

    it should "leave no edge behind when the staff user is deleted" in run { s =>
      val uid = staff(s, "gone")
      val a   = s.insertPlatformRole("a", None, Set("m.one"))
      s.setUserPlatformRoles(uid, Set(a.id))
      s.deleteUser(uid)
      s.listUserPlatformRoleIds(uid) shouldBe Nil
      s.platformPermissionsOfUser(uid) shouldBe Set.empty
      s.getPlatformRole(a.id) shouldBe Some(a)
    }

    it should "refuse renaming a role to a name another role holds" in run { s =>
      s.insertPlatformRole("a", None, Set.empty)
      val b = s.insertPlatformRole("b", None, Set.empty)
      an[Exception] should be thrownBy s.updatePlatformRole(b.id, "a", None, Set.empty)
      s.getPlatformRole(b.id).map(_.name) shouldBe Some("b")
    }

    it should "refuse a name another role holds in another case, as a unique violation" in run {
      s =>
        s.insertPlatformRole("Support", None, Set.empty)
        val onInsert =
          the[java.sql.SQLException] thrownBy s.insertPlatformRole("support", None, Set.empty)
        onInsert.getSQLState shouldBe "23505"
        val b        = s.insertPlatformRole("billing", None, Set.empty)
        val onUpdate =
          the[java.sql.SQLException] thrownBy s.updatePlatformRole(b.id, "SUPPORT", None, Set.empty)
        onUpdate.getSQLState shouldBe "23505"
        // A role's own name in another case is no clash.
        s.updatePlatformRole(b.id, "Billing", None, Set.empty).map(_.name) shouldBe Some("Billing")
        s.listPlatformRoles().map(_.name).toSet shouldBe Set("Support", "Billing")
    }

    it should "refuse an unknown user or role id when setting a user's roles" in run { s =>
      val uid = staff(s, "sam")
      val a   = s.insertPlatformRole("a", None, Set.empty)
      an[Exception] should be thrownBy s.setUserPlatformRoles("no-such-user", Set(a.id))
      an[Exception] should be thrownBy s.setUserPlatformRoles(uid, Set("no-such-role"))
      s.listUserPlatformRoleIds(uid) shouldBe Nil
    }

    "the kind check (" + label + ")" should "refuse a staff row with a tenant, and admit a user row" in
      run { s =>
        s.upsertTenant(
          ai.starlake.quack.model.Tenant(id = "t1", displayName = "t1", authProvider = "db")
        )
        an[Exception] should be thrownBy
          s.upsertUserWithHash(
            tenant = Some("t1"),
            username = "x",
            passwordHash = "x",
            kind = UserKind.Staff
          )
        val uid = s.upsertUserWithHash(
          tenant = Some("t1"),
          username = "x",
          passwordHash = "x",
          kind = UserKind.User
        )
        s.getUserById(uid).map(_.tenant) shouldBe Some(Some("t1"))
      }

  cases("Postgres", withPostgres)
  cases("InMemory", withMemory)

  "the in-memory identity upsert" should "refuse a staff row with a tenant" in {
    val s = new InMemoryControlPlaneStore()
    an[Exception] should be thrownBy
      s.upsertUserIdentity(RbacUser("u1", Some("t1"), "x", UserKind.Staff))
    s.upsertUserIdentity(RbacUser("u2", None, "y", UserKind.Staff))
    s.getUserById("u2").map(_.kind) shouldBe Some(UserKind.Staff)
  }
