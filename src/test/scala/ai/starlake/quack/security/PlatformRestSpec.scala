// src/test/scala/ai/starlake/quack/security/PlatformRestSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  PlatformHandlers,
  PlatformRoleCreateRequest,
  UserCreateRequest,
  UserHandlers,
  UserUpdateRequest
}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.module.ResolvedConsolePage
import ai.starlake.quack.ondemand.rbac.{DeclaredPermission, UserMemberships}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._

import scala.util.Try
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The platform REST surface (issue #274): platform roles, staff users and the console's module
  * pages. Role and staff routes are superuser-only; `GET /api/console/pages` answers every
  * signed-in caller the pages whose permission it holds.
  */
class PlatformRestSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  TestPostgres.dropStrayTestDatabases("qodplr")

  private val Ping = "test-module.ping"

  private val PingPage =
    ResolvedConsolePage("test-module", "ping", "Ping", Ping, "/test-module/ping.js")

  private def withHarness(
      consolePages: List[ResolvedConsolePage] = Nil
  )(f: (ManagerServerHarness.Harness, SecurityFixtures.Fixture) => Unit): Unit =
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, consolePages = consolePages)
    try
      h.supervisor.setDeclaredPermissions(List(DeclaredPermission(Ping, "test-module", "ping")))
      f(h, fix)
    finally h.shutdown()

  private def json(body: String): Json =
    parse(body).fold(e => fail(s"not JSON ($e): $body"), identity)

  private def roleNames(h: ManagerServerHarness.Harness, root: String): List[String] =
    val r = get(h.httpClient, s"${h.baseUrl}/api/platform/role/list", apiKey = Some(root))
    withClue(s"role/list: ${r.body()}")(r.statusCode() shouldBe 200)
    json(r.body()).hcursor
      .downField("roles")
      .values
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("name").toOption)
      .toList

  private def createRole(
      h: ManagerServerHarness.Harness,
      root: String,
      name: String,
      permissions: List[String]
  ) =
    val perms = permissions.map(p => s""""$p"""").mkString(",")
    post(
      h.httpClient,
      s"${h.baseUrl}/api/platform/role/create",
      s"""{"name":"$name","permissions":[$perms]}""",
      apiKey = Some(root)
    )

  // The handler-level cases' wiring: one Postgres control plane holding both the user rows and
  // their platform roles, as in Main.
  private val Root: Option[String]               = Some("superuser-credential")
  private val asSuperuser: SessionScope.Resolver = _ => SessionScope.Superuser

  private def decodeAs[A: io.circe.Decoder](body: String): A =
    io.circe.parser.decode[A](body).fold(e => fail(s"does not decode ($e): $body"), identity)

  private def createRoleDirect(platform: PlatformHandlers, name: String, perms: List[String]) =
    val out = platform
      .createRole(PlatformRoleCreateRequest(name, None, perms), Root)(asSuperuser)
      .unsafeRunSync()
    withClue(s"create role $name: $out")(out.isRight shouldBe true)

  private def withPostgresHandlers(test: (UserHandlers, PlatformHandlers) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName                                   = s"qodplr_test_${System.nanoTime()}"
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
      sup.setDeclaredPermissions(List(DeclaredPermission(Ping, "test-module", "ping")))
      sup
        .createUser(
          None,
          "boss",
          "boss-password-1",
          "admin",
          us,
          UserMemberships.Requested(None, None)
        )
        .unsafeRunSync()
        .fold(e => fail(s"seed superuser: $e"), identity)
      val userHandlers = new UserHandlers(sup, us)
      test(userHandlers, new PlatformHandlers(sup, userHandlers))
    finally
      users.foreach(u => Try(u.close()))
      store.foreach(s => Try(s.close()))
      Try(TestPostgres.psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  private def platformRolesOf(body: String): List[String] =
    json(body).hcursor
      .get[List[String]]("platformRoles")
      .getOrElse(fail(s"no platformRoles: $body"))

  "platform role CRUD" should "be superuser-only" in withHarness() { (h, _) =>
    val alice = h.mintToken(
      SecurityFixtures.AliceUsername,
      SecurityFixtures.AlicePassword,
      Some(SecurityFixtures.TenantId)
    )
    val staff  = h.staffSession("sam", Set(Ping))
    val writes = List(
      "/api/platform/role/create" -> s"""{"name":"support","permissions":["$Ping"]}""",
      "/api/platform/role/update" -> """{"id":"nope","permissions":[]}""",
      "/api/platform/role/delete" -> """{"id":"nope"}"""
    )
    val reads = List(
      "/api/platform/permission/list",
      "/api/platform/role/list",
      "/api/platform/staff/list"
    )
    // A tenant admin passes the guard and meets the handler's superuser check.
    for (path, body) <- writes do
      val r = post(h.httpClient, s"${h.baseUrl}$path", body, apiKey = Some(alice))
      withClue(s"tenant admin POST $path: ${r.body()}") {
        r.statusCode() shouldBe 403
        errorCode(r.body()) should contain("superuser_required")
      }
    for path <- reads do
      val r = get(h.httpClient, s"${h.baseUrl}$path", apiKey = Some(alice))
      withClue(s"tenant admin GET $path: ${r.body()}") {
        r.statusCode() shouldBe 403
        errorCode(r.body()) should contain("superuser_required")
      }
    // Staff never reach /api/platform/*: the guard refuses them before any handler.
    for (path, body) <- writes do
      val r = post(h.httpClient, s"${h.baseUrl}$path", body, apiKey = Some(staff))
      withClue(s"staff POST $path: ${r.body()}") {
        r.statusCode() shouldBe 403
        errorCode(r.body()) should contain("admin_required")
      }
    for path <- reads do
      val r = get(h.httpClient, s"${h.baseUrl}$path", apiKey = Some(staff))
      withClue(s"staff GET $path: ${r.body()}") {
        r.statusCode() shouldBe 403
        errorCode(r.body()) should contain("admin_required")
      }
    // Nothing was written by the refused create.
    roleNames(h, h.superuserSession()) should not contain "support"
  }

  it should "create, list, update and delete a role" in withHarness() { (h, _) =>
    val root    = h.superuserSession()
    val created = createRole(h, root, "support", List(Ping))
    withClue(s"create: ${created.body()}")(created.statusCode() shouldBe 200)
    val c  = json(created.body()).hcursor
    val id = c.get[String]("id").getOrElse(fail("no id"))
    c.get[String]("name") shouldBe Right("support")
    c.get[List[String]]("permissions") shouldBe Right(List(Ping))

    roleNames(h, root) should contain("support")

    val updated = post(
      h.httpClient,
      s"${h.baseUrl}/api/platform/role/update",
      s"""{"id":"$id","permissions":[],"description":"first line"}""",
      apiKey = Some(root)
    )
    withClue(s"update: ${updated.body()}")(updated.statusCode() shouldBe 200)
    val u = json(updated.body()).hcursor
    u.get[List[String]]("permissions") shouldBe Right(Nil)
    u.get[String]("description") shouldBe Right("first line")

    // An empty description clears it; an absent one keeps it.
    val kept = post(
      h.httpClient,
      s"${h.baseUrl}/api/platform/role/update",
      s"""{"id":"$id","name":"helpdesk"}""",
      apiKey = Some(root)
    )
    withClue(s"rename: ${kept.body()}")(kept.statusCode() shouldBe 200)
    json(kept.body()).hcursor.get[String]("description") shouldBe Right("first line")
    val cleared = post(
      h.httpClient,
      s"${h.baseUrl}/api/platform/role/update",
      s"""{"id":"$id","description":""}""",
      apiKey = Some(root)
    )
    withClue(s"clear: ${cleared.body()}")(cleared.statusCode() shouldBe 200)
    json(cleared.body()).hcursor.get[Option[String]]("description") shouldBe Right(None)

    val deleted = post(
      h.httpClient,
      s"${h.baseUrl}/api/platform/role/delete",
      s"""{"id":"$id"}""",
      apiKey = Some(root)
    )
    withClue(s"delete: ${deleted.body()}")(deleted.statusCode() shouldBe 200)
    roleNames(h, root) shouldBe empty

    val again = post(
      h.httpClient,
      s"${h.baseUrl}/api/platform/role/delete",
      s"""{"id":"$id"}""",
      apiKey = Some(root)
    )
    withClue(s"delete again: ${again.body()}") {
      again.statusCode() shouldBe 404
      errorCode(again.body()) should contain("not_found")
    }
  }

  it should "answer unknown_permission, reserved_name and name_taken" in withHarness() { (h, _) =>
    val root    = h.superuserSession()
    val unknown = createRole(h, root, "support", List("nobody.declares.this"))
    withClue(s"unknown: ${unknown.body()}") {
      unknown.statusCode() shouldBe 400
      errorCode(unknown.body()) should contain("unknown_permission")
    }
    val reserved = createRole(h, root, "superuser", Nil)
    withClue(s"reserved: ${reserved.body()}") {
      reserved.statusCode() shouldBe 400
      errorCode(reserved.body()) should contain("reserved_name")
    }
    createRole(h, root, "support", List(Ping)).statusCode() shouldBe 200
    val taken = createRole(h, root, "Support", Nil)
    withClue(s"taken: ${taken.body()}") {
      taken.statusCode() shouldBe 409
      errorCode(taken.body()) should contain("name_taken")
    }
  }

  "GET /api/platform/permission/list" should "list declared and stored-but-undeclared permissions" in
    withHarness() { (h, _) =>
      // A role stored by a module no longer loaded, written straight through the store.
      h.store.insertPlatformRole("legacy", None, Set("gone.module.x"))
      val root = h.superuserSession()
      val r = get(h.httpClient, s"${h.baseUrl}/api/platform/permission/list", apiKey = Some(root))
      withClue(s"permission/list: ${r.body()}")(r.statusCode() shouldBe 200)
      val perms = json(r.body()).hcursor.downField("permissions").values.getOrElse(Nil).toList
      perms.map(_.hcursor) should have size 2
      val declared = perms.find(_.hcursor.get[String]("name").contains(Ping)).get.hcursor
      declared.get[String]("module") shouldBe Right("test-module")
      declared.get[String]("description") shouldBe Right("ping")
      declared.get[Boolean]("declared") shouldBe Right(true)
      val gone = perms.find(_.hcursor.get[String]("name").contains("gone.module.x")).get.hcursor
      gone.get[Boolean]("declared") shouldBe Right(false)
      gone.get[Option[String]]("module") shouldBe Right(None)
    }

  // The two cases below write a user through the REST handlers, which put the row in the JDBC
  // UserStore and its platform roles in the control-plane store. The HTTP harness keeps those in
  // two stores (DuckDB and in-memory), so a staff row it writes has no platform-role edge to hold:
  // these run the same handlers on one Postgres, as Main wires them, and read the JSON through the
  // wire codecs.
  "user/create" should "create a staff user with platform roles and show them" in
    withPostgresHandlers { (users, platform) =>
      createRoleDirect(platform, "support", List(Ping))
      val req = decodeAs[UserCreateRequest](
        """{"username":"sam","password":"sam-password-1","kind":"staff","platformRoles":["support"]}"""
      )
      val created = users.createUser(req, Root)(asSuperuser).unsafeRunSync()
      withClue(s"create staff: $created") {
        val r = created.getOrElse(fail(s"refused: $created"))
        r.kind shouldBe "staff"
        r.platformRoles shouldBe List("support")
        platformRolesOf(r.asJson.noSpaces) shouldBe List("support")
      }
      val staff = platform.listStaff(Root)(asSuperuser).unsafeRunSync()
      withClue(s"staff/list: $staff") {
        val rows = staff.getOrElse(fail(s"refused: $staff")).users
        rows.map(_.username) shouldBe List("sam")
        rows.head.platformRoles shouldBe List("support")
      }
      // Only a staff row carries platform roles in a listing.
      val list = users.listUsers(None, Root)(asSuperuser).unsafeRunSync()
      val rows = list.getOrElse(fail(s"refused: $list")).users
      rows.find(_.username == "sam").map(_.platformRoles) shouldBe Some(List("support"))
      rows.find(_.username == "boss").map(_.platformRoles) shouldBe Some(Nil)
    }

  it should "refuse platformRoles on a tenant user with invalid_kind" in withHarness() { (h, _) =>
    val root = h.superuserSession()
    createRole(h, root, "support", List(Ping)).statusCode() shouldBe 200
    val r = post(
      h.httpClient,
      s"${h.baseUrl}/api/user/create",
      s"""{"tenant":"${SecurityFixtures.TenantId}","username":"tu","password":"tu-password-1","kind":"user","platformRoles":["support"]}""",
      apiKey = Some(root)
    )
    withClue(s"tenant user with platformRoles: ${r.body()}") {
      r.statusCode() shouldBe 400
      errorCode(r.body()) should contain("invalid_kind")
    }
    h.store.findUser(Some(SecurityFixtures.TenantId), "tu") shouldBe None
  }

  "user/update" should "keep roles when omitted, clear them with []" in
    withPostgresHandlers { (users, platform) =>
      createRoleDirect(platform, "support", List(Ping))
      val created = users
        .createUser(
          decodeAs[UserCreateRequest](
            """{"username":"sam","password":"sam-password-1","kind":"staff","platformRoles":["support"]}"""
          ),
          Root
        )(asSuperuser)
        .unsafeRunSync()
      val id                   = created.getOrElse(fail(s"refused: $created")).id
      def update(body: String) =
        users.updateUser(decodeAs[UserUpdateRequest](body), Root)(asSuperuser).unsafeRunSync()

      val kept = update(s"""{"id":"$id","email":"sam@example.com"}""")
      withClue(s"update without platformRoles: $kept") {
        kept.map(_.platformRoles) shouldBe Right(List("support"))
      }
      val cleared = update(s"""{"id":"$id","platformRoles":[]}""")
      withClue(s"update with []: $cleared")(cleared.map(_.platformRoles) shouldBe Right(Nil))
      val unknown = update(s"""{"id":"$id","platformRoles":["no-such-role"]}""")
      withClue(s"update with an unknown role: $unknown") {
        unknown.left.map(_._1.code) shouldBe Left(400)
        unknown.left.map(_._2.error) shouldBe Left("unknown_role")
      }
      val restored = update(s"""{"id":"$id","platformRoles":["SUPPORT"]}""")
      withClue(s"update with a role named in another case: $restored") {
        restored.map(_.platformRoles) shouldBe Right(List("support"))
      }
    }

  it should "refuse demoting the last enabled superuser to staff" in withHarness() { (h, fix) =>
    fix.store.upsertUserWithHash(
      tenant = None,
      username = "root2",
      passwordHash = at.favre.lib.crypto.bcrypt.BCrypt
        .withDefaults()
        .hashToString(10, "root2pw".toCharArray),
      kind = "admin"
    )
    val root2Id = fix.store.findUser(None, "root2").get.id
    val root    = h.superuserSession()
    // Minted before root2 is demoted: the still-live session is what the floor must hold.
    val root2                           = h.mintToken("root2", "root2pw")
    def toStaff(id: String, as: String) =
      post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        s"""{"id":"$id","kind":"staff"}""",
        apiKey = Some(as)
      )
    val first = toStaff(root2Id, root)
    withClue(s"demote root2: ${first.body()}")(first.statusCode() shouldBe 200)
    val last = toStaff(fix.rootUserId, root2)
    withClue(s"demote the last superuser: ${last.body()}") {
      last.statusCode() shouldBe 400
      errorCode(last.body()) should contain("last_superuser")
    }
  }

  "GET /api/console/pages" should "list only the pages a caller may see" in
    withHarness(consolePages = List(PingPage)) { (h, _) =>
      def pages(token: String): List[Json] =
        val r = get(h.httpClient, s"${h.baseUrl}/api/console/pages", apiKey = Some(token))
        withClue(s"console/pages: ${r.body()}")(r.statusCode() shouldBe 200)
        json(r.body()).hcursor.downField("pages").values.getOrElse(Nil).toList

      val asRoot = pages(h.superuserSession())
      asRoot should have size 1
      val p = asRoot.head.hcursor
      p.get[String]("module") shouldBe Right("test-module")
      p.get[String]("id") shouldBe Right("ping")
      p.get[String]("label") shouldBe Right("Ping")
      p.get[String]("path") shouldBe Right("/ui/x/test-module/ping")
      p.get[String]("bundle") shouldBe Right("/test-module/ping.js")

      pages(h.staffSession("sam", Set(Ping))) should have size 1
      pages(h.staffSession("nora", Set.empty)) shouldBe empty
      val bob = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        Some(SecurityFixtures.TenantId)
      )
      pages(bob) shouldBe empty
      val alice = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      pages(alice) shouldBe empty

      val anonymous = get(h.httpClient, s"${h.baseUrl}/api/console/pages")
      anonymous.statusCode() shouldBe 401
    }
