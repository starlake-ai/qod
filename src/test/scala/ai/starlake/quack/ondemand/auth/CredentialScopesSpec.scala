package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.api.SessionTokenStore
import ai.starlake.quack.ondemand.rbac.StaffPrincipal
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, RbacUser, UserGrant, UserStore}
import ai.starlake.quack.spi.PlatformCaller
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

class CredentialScopesSpec extends AnyFlatSpec with Matchers:

  private val sam                   = RbacUser("u-sam", None, "sam", "staff")
  private def profile(role: String) =
    AuthenticatedProfile(
      username = "sam",
      role = role,
      groups = Set.empty,
      claims = Map.empty,
      authMethod = "db",
      tenant = None
    )

  "SessionScope.has" should "be true for a superuser and for a held permission only" in {
    SessionScope.Superuser.has("m.x") shouldBe true
    SessionScope(false, Set.empty, Set("m.x")).has("m.x") shouldBe true
    SessionScope(false, Set.empty, Set("m.x")).has("m.y") shouldBe false
    SessionScope.NoAccess.has("m.x") shouldBe false
  }

  "CredentialScopes" should "fill a staff session's permissions live" in {
    val sessions = new SessionTokenStore()
    var perms    = Set("m.read")
    val creds    = new CredentialScopes(
      sessions,
      None,
      n => if n == "sam" then Some(StaffPrincipal(sam, perms)) else None
    )
    val token = sessions.mintWithScope(profile("staff"), SessionScope.NoAccess)
    creds.scopeOf(Some(token)).platformPermissions shouldBe Set("m.read")
    perms = Set("m.read", "m.write")
    creds.scopeOf(Some(token)).platformPermissions shouldBe Set("m.read", "m.write")
    creds.sessionOf(token).map(_.profile.role) shouldBe Some("staff")
  }

  it should "end a staff session whose row is gone, disabled or no longer staff" in {
    val sessions = new SessionTokenStore()
    val creds    = new CredentialScopes(sessions, None, _ => None)
    val token    = sessions.mintWithScope(profile("staff"), SessionScope.NoAccess)
    creds.sessionOf(token) shouldBe None
    creds.scopeOf(Some(token)) shouldBe SessionScope.NoAccess
  }

  it should "end a staff session that names a tenant, whatever the lookup says" in {
    val sessions = new SessionTokenStore()
    val creds    = new CredentialScopes(sessions, None, _ => Some(StaffPrincipal(sam, Set("m.x"))))
    val token    =
      sessions.mintWithScope(profile("staff").copy(tenant = Some("acme")), SessionScope.NoAccess)
    creds.sessionOf(token) shouldBe None
  }

  it should "leave other sessions unchanged" in {
    val sessions = new SessionTokenStore()
    val creds    = new CredentialScopes(sessions, None, _ => fail("not a staff session"))
    val token    = sessions.mintWithScope(profile("admin"), SessionScope.Superuser)
    creds.scopeOf(Some(token)) shouldBe SessionScope.Superuser
    creds.scopeOf(None) shouldBe SessionScope.NoAccess
    creds.scopeOf(Some("not-a-token")) shouldBe SessionScope.NoAccess
  }

  it should "build a staff session's scope from the row alone, whatever its JWT says" in {
    val sessions = new SessionTokenStore()
    val creds    = new CredentialScopes(sessions, None, _ => Some(StaffPrincipal(sam, Set("m.x"))))
    Seq(SessionScope.Superuser, SessionScope(superuser = true, manageableTenants = Set("acme")))
      .foreach { minted =>
        val token = sessions.mintWithScope(profile("staff"), minted)
        val scope = creds.scopeOf(Some(token))
        scope.superuser shouldBe false
        scope.manageableTenants shouldBe empty
        scope.platformPermissions shouldBe Set("m.x")
        creds.enrich(sessions.get(token).get).map(_.scope) shouldBe Some(scope)
      }
  }

  "CredentialScopes.platformCallerOf" should "hold every permission for a superuser session" in {
    val sessions = new SessionTokenStore()
    val creds    = new CredentialScopes(sessions, None, _ => fail("not a staff session"))
    val token    = sessions.mintWithScope(profile("admin"), SessionScope.Superuser)
    val caller   = creds.platformCallerOf(Some(token)).getOrElse(fail("no platform caller"))
    caller shouldBe PlatformCaller(None, "sam", superuser = true, Set.empty)
    caller.has("any.permission") shouldBe true
    caller.auditDetail("m.x") shouldBe Map("permission" -> "m.x", "actorKind" -> "superuser")
  }

  it should "give a staff session its row's live permissions and id" in {
    val sessions = new SessionTokenStore()
    var perms    = Set("m.read")
    val creds    = new CredentialScopes(
      sessions,
      None,
      n => if n == "sam" then Some(StaffPrincipal(sam, perms)) else None
    )
    val token = sessions.mintWithScope(profile("staff"), SessionScope.NoAccess)
    creds.platformCallerOf(Some(token)) shouldBe Some(
      PlatformCaller(Some("u-sam"), "sam", superuser = false, Set("m.read"))
    )
    perms = Set("m.write")
    val caller = creds.platformCallerOf(Some(token)).get
    caller.has("m.write") shouldBe true
    caller.has("m.read") shouldBe false
    caller.auditDetail("m.write") shouldBe Map("permission" -> "m.write", "actorKind" -> "staff")
  }

  it should "look a staff session's row up once" in {
    val sessions = new SessionTokenStore()
    var lookups  = 0
    val creds    = new CredentialScopes(
      sessions,
      None,
      n =>
        lookups += 1
        if n == "sam" then Some(StaffPrincipal(sam, Set("m.read"))) else None
    )
    val token = sessions.mintWithScope(profile("staff"), SessionScope.NoAccess)
    creds.platformCallerOf(Some(token)) shouldBe Some(
      PlatformCaller(Some("u-sam"), "sam", superuser = false, Set("m.read"))
    )
    lookups shouldBe 1
  }

  it should "be None for a staff session whose row stopped qualifying" in {
    val sessions = new SessionTokenStore()
    val creds    = new CredentialScopes(sessions, None, _ => None)
    val token    = sessions.mintWithScope(profile("staff"), SessionScope.NoAccess)
    creds.platformCallerOf(Some(token)) shouldBe None
  }

  it should "be None for a tenant admin, a tenant user, no credential and garbage" in {
    val sessions = new SessionTokenStore()
    val creds    = new CredentialScopes(sessions, None, _ => fail("not a staff session"))
    val admin    = sessions.mintWithScope(
      profile("admin").copy(tenant = Some("acme")),
      SessionScope(superuser = false, manageableTenants = Set("acme"))
    )
    val user = sessions.mintWithScope(
      profile("user").copy(tenant = Some("acme")),
      SessionScope(superuser = false, manageableTenants = Set.empty)
    )
    creds.platformCallerOf(Some(admin)) shouldBe None
    creds.platformCallerOf(Some(user)) shouldBe None
    creds.platformCallerOf(None) shouldBe None
    creds.platformCallerOf(Some("not-a-token")) shouldBe None
  }

  it should "be None for a superuser's dropAdmin PAT, and every permission for its plain PAT" in {
    TestPostgres.dropStrayTestDatabases("qodcsc")
    TestPostgres.ensureReachable()
    val dbName = s"qodcsc_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val pats  = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        users.upsertUser(None, "root", "pw", "admin")
        val uid            = users.userIdOf(None, "root").get
        val (_, plain)     = pats.mint(uid, "plain", TokenRestriction.Unrestricted, None, 0)
        val (_, dropAdmin) =
          pats.mint(uid, "agent", TokenRestriction.Unrestricted.copy(dropAdmin = true), None, 0)
        val auth = new PatAuthenticator(
          pats,
          id => users.userById(id),
          u => List(UserGrant(u.tenant, u.kind)),
          tenantDisabled = _ => false
        )
        val creds = new CredentialScopes(new SessionTokenStore(), Some(auth), _ => None)
        creds.sessionOf(dropAdmin).map(_.scope.superuser) shouldBe Some(false)
        creds.platformCallerOf(Some(dropAdmin)) shouldBe None
        creds.platformCallerOf(Some(plain)) shouldBe Some(
          PlatformCaller(None, "root", superuser = true, Set.empty)
        )
      finally
        pats.close()
        users.close()
    finally Try(TestPostgres.dropDatabase(dbName))
  }
