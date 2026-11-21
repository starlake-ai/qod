package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.rbac.{DeclaredPermission, PlatformPermissions, UserMemberships}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  LiquibaseRunner,
  PlatformRole,
  PostgresControlPlaneStore,
  UserStore
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.util.Try

/** Platform roles and staff users through the supervisor (issue #274). On Postgres, as `createUser`
  * writes the row through the JDBC `UserStore`.
  */
class PlatformRoleSupervisorSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodprs")

  private val Tenant1 = "acme"

  /** Once armed, holds the next read of the method named (`find` or `perms`) after it read its
    * value: it signals `loaded`, then waits for `release`, so a test can change the state while a
    * load is in flight.
    */
  private final class GatedStore(inner: PostgresControlPlaneStore) extends ControlPlaneStore:
    export inner.{findUser as _, platformPermissionsOfUser as _, *}
    @volatile var gate: Option[(String, CountDownLatch, CountDownLatch)] = None
    private def held[A](method: String)(a: A): A                         =
      gate.filter(_._1 == method).foreach { (_, loaded, release) =>
        gate = None
        loaded.countDown()
        release.await(10, TimeUnit.SECONDS)
      }
      a
    def platformPermissionsOfUser(userId: String): Set[String] =
      held("perms")(inner.platformPermissionsOfUser(userId))
    def findUser(tenant: Option[String], username: String) =
      held("find")(inner.findUser(tenant, username))

  /** Hides every platform role from the supervisor's own name check, so the database's unique index
    * answers a duplicate.
    */
  private final class BlindStore(inner: PostgresControlPlaneStore) extends ControlPlaneStore:
    export inner.{listPlatformRoles as _, *}
    def listPlatformRoles(): List[PlatformRole] = Nil

  private def withPostgres(
      test: (PoolSupervisor, PostgresControlPlaneStore, UserStore) => Unit
  ): Unit = withStores(identity)((s, _, st, us) => test(s, st, us))

  private def withStores[S <: ControlPlaneStore](wrap: PostgresControlPlaneStore => S)(
      test: (PoolSupervisor, S, PostgresControlPlaneStore, UserStore) => Unit
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
      val wrapped = wrap(st)
      val sup     = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, wrapped)
      sup.restore()
      sup.createTenant(Tenant(id = Tenant1)).unsafeRunSync()
      sup.setDeclaredPermissions(
        List(
          DeclaredPermission("hosted.tenant.read", "hosted", "read"),
          DeclaredPermission("hosted.quota.extra", "hosted", "extra")
        )
      )
      test(sup, wrapped, st, us)
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
    PlatformPermissions.nameRefusal("hosted", "hosted.a..b") shouldBe defined
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

  /** Runs `staffPrincipal(name)` on another thread, holds its load at the store, runs `change`
    * while it is held, then lets it finish and returns what it loaded.
    */
  private def racedLoad(
      s: PoolSupervisor,
      gated: GatedStore,
      name: String,
      at: String = "perms"
  )(change: => Unit): Option[ai.starlake.quack.ondemand.rbac.StaffPrincipal] =
    val loaded  = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    gated.gate = Some((at, loaded, release))
    @volatile var out: Option[ai.starlake.quack.ondemand.rbac.StaffPrincipal] = None
    val t = new Thread(() => out = s.staffPrincipal(name))
    t.start()
    loaded.await(10, TimeUnit.SECONDS) shouldBe true
    change
    release.countDown()
    t.join(10000)
    out

  "staffPrincipal" should "not serve a load that raced a role change" in withStores(
    new GatedStore(_)
  ) { (s, gated, _, us) =>
    val role = s.createPlatformRole("support", None, Set("hosted.tenant.read")).toOption.get
    staff(s, us, "sam", List("support")).isRight shouldBe true
    val raced = racedLoad(s, gated, "sam") {
      s.updatePlatformRole(
        role.id,
        None,
        None,
        Some(Set("hosted.tenant.read", "hosted.quota.extra"))
      ).isRight shouldBe true
    }
    raced.map(_.permissions) shouldBe Some(Set("hosted.tenant.read"))
    s.staffPrincipal("sam").map(_.permissions) shouldBe Some(
      Set("hosted.tenant.read", "hosted.quota.extra")
    )
  }

  it should "not serve a load that raced a disable" in withStores(new GatedStore(_)) {
    (s, gated, _, us) =>
      s.createPlatformRole("support", None, Set("hosted.tenant.read"))
      val u = staff(s, us, "sam", List("support")).toOption.get
      racedLoad(s, gated, "sam") {
        s.updateUserPassword(u.id, None, None, us, enabled = Some(false))
          .unsafeRunSync()
          .isRight shouldBe true
      } shouldBe defined
      s.staffPrincipal("sam") shouldBe None
  }

  it should "not serve a miss that raced a staff create" in withStores(new GatedStore(_)) {
    (s, gated, _, us) =>
      racedLoad(s, gated, "late", at = "find") {
        staff(s, us, "late", Nil).isRight shouldBe true
      } shouldBe None
      s.staffPrincipal("late") shouldBe defined
  }

  "the database's unique index" should "answer a duplicate the check missed as NameTaken" in
    withStores(new BlindStore(_)) { (s, _, _, _) =>
      s.createPlatformRole("support", None, Set.empty).isRight shouldBe true
      s.createPlatformRole("SUPPORT", None, Set.empty).left.toOption.get shouldBe a[
        SupervisorError.NameTaken
      ]
      val b = s.createPlatformRole("billing", None, Set.empty).toOption.get
      s.updatePlatformRole(b.id, Some("Support"), None, None).left.toOption.get shouldBe a[
        SupervisorError.NameTaken
      ]
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

  "a move to staff" should "not bring back edges left on a row that was not staff" in
    withPostgres { (s, store, us) =>
      val r    = s.createPlatformRole("support", None, Set("hosted.tenant.read")).toOption.get
      val root = s
        .createUser(None, "root", "pw-123456", "admin", us, noMemberships)
        .unsafeRunSync()
        .toOption
        .get
      // An edge a racing setUserPlatformRoles could leave on a row that is no longer staff.
      store.setUserPlatformRoles(root.id, Set(r.id))
      s.updateUserPassword(root.id, None, Some("staff"), us).unsafeRunSync().isRight shouldBe true
      s.platformRoleNamesOf(root.id) shouldBe Nil
      s.staffPrincipal("root").map(_.permissions) shouldBe Some(Set.empty)
      val op = s
        .createUser(None, "op", "pw-123456", "admin", us, noMemberships)
        .unsafeRunSync()
        .toOption
        .get
      store.setUserPlatformRoles(op.id, Set(r.id))
      s.createUser(None, "op", "pw-123456", "staff", us, noMemberships)
        .unsafeRunSync()
        .isRight shouldBe true
      s.platformRoleNamesOf(op.id) shouldBe Nil
      s.staffPrincipal("op").map(_.permissions) shouldBe Some(Set.empty)
    }

  "an upsert over a staff row to another kind" should "drop its platform roles" in withPostgres {
    (s, _, us) =>
      s.createPlatformRole("support", None, Set("hosted.tenant.read"))
      val u = staff(s, us, "sam", List("support")).toOption.get
      s.staffPrincipal("sam") shouldBe defined
      s.createUser(None, "sam", "pw-123456", "admin", us, noMemberships)
        .unsafeRunSync()
        .isRight shouldBe true
      s.platformRoleNamesOf(u.id) shouldBe Nil
      s.staffPrincipal("sam") shouldBe None
  }

  "an upsert over a staff row naming no roles" should "keep its roles" in withPostgres {
    (s, _, us) =>
      s.createPlatformRole("support", None, Set("hosted.tenant.read"))
      val u = staff(s, us, "sam", List("support")).toOption.get
      staff(s, us, "sam", Nil).isRight shouldBe true
      s.platformRoleNamesOf(u.id) shouldBe List("support")
  }
