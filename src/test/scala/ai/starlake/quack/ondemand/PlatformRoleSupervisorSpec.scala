package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.rbac.{DeclaredPermission, PlatformPermissions, UserMemberships}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Platform roles and staff users through the supervisor (issue #274). On Postgres, as `createUser`
  * writes the row through the JDBC `UserStore`.
  */
class PlatformRoleSupervisorSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodprs")

  private val Tenant1 = "acme"

  private def withPostgres(
      test: (PoolSupervisor, PostgresControlPlaneStore, UserStore) => Unit
  ): Unit =
    TestPostgres.ensureReachable()
    val dbName                                   = s"qodprs_test_${System.nanoTime()}"
    var store: Option[PostgresControlPlaneStore] = None
    var users: Option[UserStore]                 = None
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val st = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      store = Some(st)
      val us = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      users = Some(us)
      val sup = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, st)
      sup.restore()
      sup.createTenant(Tenant(id = Tenant1)).unsafeRunSync()
      sup.setDeclaredPermissions(
        List(
          DeclaredPermission("hosted.tenant.read", "hosted", "read"),
          DeclaredPermission("hosted.quota.extra", "hosted", "extra")
        )
      )
      test(sup, st, us)
    finally
      users.foreach(u => Try(u.close()))
      store.foreach(s => Try(s.close()))
      Try(TestPostgres.psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  private val noMemberships = UserMemberships.Requested(None, None)

  private def staff(
      s: PoolSupervisor,
      us: UserStore,
      name: String,
      roles: List[String]
  ) =
    s.createUser(None, name, "pw-123456", "staff", us, noMemberships, platformRoles = roles)
      .unsafeRunSync()

  "PlatformPermissions.nameRefusal" should "take module-prefixed names and refuse the rest" in {
    PlatformPermissions.nameRefusal("hosted", "hosted.tenant.read") shouldBe None
    PlatformPermissions.nameRefusal("hosted", "hosted.quota_extra.v2") shouldBe None
    PlatformPermissions.nameRefusal("hosted", "other.tenant.read") shouldBe defined
    PlatformPermissions.nameRefusal("platform", "platform.anything") shouldBe defined
    PlatformPermissions.nameRefusal("hosted", "hosted.") shouldBe defined
    PlatformPermissions.nameRefusal("hosted", "hosted.Tenant") shouldBe defined
    PlatformPermissions.nameRefusal("hosted", "hosted.tenant.") shouldBe defined
    PlatformPermissions.nameRefusal("hosted", "hosted..x") shouldBe defined
  }

  "createPlatformRole" should "refuse an undeclared permission" in withPostgres { (s, _, _) =>
    s.createPlatformRole("support", None, Set("hosted.nope")) match
      case Left(SupervisorError.InvalidMembership(code, _)) => code shouldBe "unknown_permission"
      case other                                            => fail(other.toString)
    s.listPlatformRoles() shouldBe Nil
  }

  it should "refuse the reserved name in any case" in withPostgres { (s, _, _) =>
    s.createPlatformRole("SuperUser", None, Set.empty).left.toOption.get shouldBe a[
      SupervisorError.ReservedName
    ]
    s.createPlatformRole(" superuser ", None, Set.empty).left.toOption.get shouldBe a[
      SupervisorError.ReservedName
    ]
  }

  it should "refuse a blank name" in withPostgres { (s, _, _) =>
    s.createPlatformRole("  ", None, Set.empty).left.toOption.get shouldBe a[
      SupervisorError.InvalidArgument
    ]
  }

  it should "refuse a duplicate name as name_taken" in withPostgres { (s, _, _) =>
    s.createPlatformRole("support", None, Set("hosted.tenant.read")).isRight shouldBe true
    s.createPlatformRole("support", None, Set.empty).left.toOption.get shouldBe a[
      SupervisorError.NameTaken
    ]
  }

  it should "treat a name differing only in case as taken, and store the name as written" in
    withPostgres { (s, _, _) =>
      val r = s.createPlatformRole(" Support ", None, Set.empty).toOption.get
      r.name shouldBe "Support"
      s.createPlatformRole("support", None, Set.empty).left.toOption.get shouldBe a[
        SupervisorError.NameTaken
      ]
      s.createPlatformRole("SUPPORT", None, Set.empty).left.toOption.get shouldBe a[
        SupervisorError.NameTaken
      ]
      s.listPlatformRoles().map(_.name) shouldBe List("Support")
    }

  "updatePlatformRole" should "refuse a rename onto another role's name in any case" in
    withPostgres { (s, _, _) =>
      s.createPlatformRole("support", None, Set.empty)
      val b = s.createPlatformRole("billing", None, Set.empty).toOption.get
      s.updatePlatformRole(b.id, Some("SUPPORT"), None, None).left.toOption.get shouldBe a[
        SupervisorError.NameTaken
      ]
      // Its own name in another case is no clash.
      s.updatePlatformRole(b.id, Some("Billing"), None, None).map(_.name) shouldBe Right("Billing")
    }

  it should "keep undeclared permissions it already holds but refuse new ones" in withPostgres {
    (s, store, _) =>
      val r = store.insertPlatformRole("legacy", None, Set("gone.module.perm"))
      s.updatePlatformRole(r.id, None, Some(Some("d")), None).isRight shouldBe true
      s.updatePlatformRole(
        r.id,
        None,
        None,
        Some(Set("gone.module.perm", "hosted.tenant.read"))
      ).map(_.permissions) shouldBe Right(Set("gone.module.perm", "hosted.tenant.read"))
      s.updatePlatformRole(r.id, None, None, Some(Set("hosted.nope"))) match
        case Left(SupervisorError.InvalidMembership(code, _)) => code shouldBe "unknown_permission"
        case other                                            => fail(other.toString)
  }

  it should "answer NotFound for an unknown id" in withPostgres { (s, _, _) =>
    s.updatePlatformRole("nope", Some("x"), None, None).left.toOption.get shouldBe a[
      SupervisorError.NotFound
    ]
    s.deletePlatformRole("nope").left.toOption.get shouldBe a[SupervisorError.NotFound]
  }

  "staffPrincipal" should "follow role edits at once" in withPostgres { (s, _, us) =>
    val role = s.createPlatformRole("support", None, Set("hosted.tenant.read")).toOption.get
    val u    = staff(s, us, "sam", List("support")).toOption.get
    s.staffPrincipal("sam").map(_.permissions) shouldBe Some(Set("hosted.tenant.read"))
    s.updatePlatformRole(role.id, None, None, Some(Set("hosted.tenant.read", "hosted.quota.extra")))
    s.staffPrincipal("sam").map(_.permissions) shouldBe Some(
      Set("hosted.tenant.read", "hosted.quota.extra")
    )
    s.deletePlatformRole(role.id) shouldBe Right(())
    s.staffPrincipal("sam").map(_.permissions) shouldBe Some(Set.empty)
    s.updateUserPassword(u.id, None, None, us, enabled = Some(false)).unsafeRunSync()
    s.staffPrincipal("sam") shouldBe None
  }

  it should "find a staff user created after a miss" in withPostgres { (s, _, us) =>
    s.staffPrincipal("late") shouldBe None
    staff(s, us, "late", Nil).isRight shouldBe true
    s.staffPrincipal("late").map(_.permissions) shouldBe Some(Set.empty)
  }

  it should "be None for a superuser and for an unknown name" in withPostgres { (s, _, us) =>
    s.createUser(None, "root", "pw-123456", "admin", us, noMemberships).unsafeRunSync()
    s.staffPrincipal("root") shouldBe None
    s.staffPrincipal("ghost") shouldBe None
  }

  it should "drop a deleted staff user" in withPostgres { (s, _, us) =>
    val u = staff(s, us, "sam", Nil).toOption.get
    s.staffPrincipal("sam") shouldBe defined
    s.deleteUser(u.id).unsafeRunSync() shouldBe Right(())
    s.staffPrincipal("sam") shouldBe None
  }

  "createUser" should "refuse platformRoles on a non-staff kind and staff with a tenant" in
    withPostgres { (s, store, us) =>
      s.createPlatformRole("support", None, Set.empty)
      s.createUser(
        None,
        "x",
        "pw-123456",
        "user",
        us,
        noMemberships,
        platformRoles = List("support")
      ).unsafeRunSync() match
        case Left(SupervisorError.InvalidMembership(code, _)) => code shouldBe "invalid_kind"
        case other                                            => fail(other.toString)
      s.createUser(Some(Tenant1), "y", "pw-123456", "staff", us, noMemberships)
        .unsafeRunSync() match
        case Left(SupervisorError.InvalidMembership(code, _)) => code shouldBe "invalid_kind"
        case other                                            => fail(other.toString)
      store.findUser(None, "x") shouldBe None
      store.listUsers(None).map(_.username) should not contain "y"
    }

  it should "refuse an unknown platform role name" in withPostgres { (s, store, us) =>
    staff(s, us, "z", List("ghost")) match
      case Left(SupervisorError.InvalidMembership(code, _)) => code shouldBe "unknown_role"
      case other                                            => fail(other.toString)
    store.findUser(None, "z") shouldBe None
  }

  it should "match a platform role name ignoring case" in withPostgres { (s, _, us) =>
    s.createPlatformRole("Support", None, Set("hosted.tenant.read"))
    val u = staff(s, us, "sam", List("SUPPORT", "support")).toOption.get
    s.platformRoleNamesOf(u.id) shouldBe List("Support")
    s.staffPrincipal("sam").map(_.permissions) shouldBe Some(Set("hosted.tenant.read"))
    s.createPlatformRole("billing", None, Set("hosted.quota.extra"))
    s.setUserPlatformRoles(u.id, List("BILLING")) shouldBe Right(())
    s.platformRoleNamesOf(u.id) shouldBe List("billing")
    s.staffPrincipal("sam").map(_.permissions) shouldBe Some(Set("hosted.quota.extra"))
  }

  "setUserPlatformRoles" should "refuse a user that is not staff and an unknown user" in
    withPostgres { (s, _, us) =>
      s.createPlatformRole("support", None, Set.empty)
      val root = s
        .createUser(None, "root", "pw-123456", "admin", us, noMemberships)
        .unsafeRunSync()
        .toOption
        .get
      s.setUserPlatformRoles(root.id, List("support")) match
        case Left(SupervisorError.InvalidMembership(code, _)) => code shouldBe "invalid_kind"
        case other                                            => fail(other.toString)
      s.setUserPlatformRoles("nope", List("support")).left.toOption.get shouldBe a[
        SupervisorError.NotFound
      ]
    }

  "updateUserPassword" should "replace a staff user's platform roles" in withPostgres {
    (s, _, us) =>
      s.createPlatformRole("support", None, Set.empty)
      s.createPlatformRole("billing", None, Set.empty)
      val u = staff(s, us, "sam", List("support")).toOption.get
      s.updateUserPassword(u.id, None, None, us, platformRoles = Some(List("billing")))
        .unsafeRunSync()
        .isRight shouldBe true
      s.platformRoleNamesOf(u.id) shouldBe List("billing")
      s.updateUserPassword(u.id, None, None, us, platformRoles = Some(List("ghost")))
        .unsafeRunSync() match
        case Left(SupervisorError.InvalidMembership(code, _)) => code shouldBe "unknown_role"
        case other                                            => fail(other.toString)
      s.platformRoleNamesOf(u.id) shouldBe List("billing")
  }

  it should "refuse staff on a tenant row and platformRoles on a non-staff row" in withPostgres {
    (s, store, us) =>
      s.createPlatformRole("support", None, Set.empty)
      val t = s
        .createUser(Some(Tenant1), "bob", "pw-123456", "user", us, noMemberships)
        .unsafeRunSync()
        .toOption
        .get
      s.updateUserPassword(t.id, None, Some("staff"), us).unsafeRunSync() match
        case Left(SupervisorError.InvalidMembership(code, _)) => code shouldBe "invalid_kind"
        case other                                            => fail(other.toString)
      store.getUserById(t.id).map(_.kind) shouldBe Some("user")
      val root = s
        .createUser(None, "root", "pw-123456", "admin", us, noMemberships)
        .unsafeRunSync()
        .toOption
        .get
      s.updateUserPassword(root.id, None, None, us, platformRoles = Some(List("support")))
        .unsafeRunSync() match
        case Left(SupervisorError.InvalidMembership(code, _)) => code shouldBe "invalid_kind"
        case other                                            => fail(other.toString)
  }

  it should "take a kind change to staff with platform roles in one write" in withPostgres {
    (s, _, us) =>
      s.createPlatformRole("support", None, Set("hosted.tenant.read"))
      val root = s
        .createUser(None, "root", "pw-123456", "admin", us, noMemberships)
        .unsafeRunSync()
        .toOption
        .get
      s.updateUserPassword(root.id, None, Some("staff"), us, platformRoles = Some(List("support")))
        .unsafeRunSync()
        .isRight shouldBe true
      s.staffPrincipal("root").map(_.permissions) shouldBe Some(Set("hosted.tenant.read"))
  }

  "a kind change away from staff" should "drop the row's platform roles" in withPostgres {
    (s, _, us) =>
      s.createPlatformRole("support", None, Set("hosted.tenant.read"))
      val u = staff(s, us, "sam", List("support")).toOption.get
      s.staffPrincipal("sam") shouldBe defined
      s.updateUserPassword(u.id, None, Some("user"), us).unsafeRunSync().isRight shouldBe true
      s.platformRoleNamesOf(u.id) shouldBe Nil
      s.staffPrincipal("sam") shouldBe None
  }
