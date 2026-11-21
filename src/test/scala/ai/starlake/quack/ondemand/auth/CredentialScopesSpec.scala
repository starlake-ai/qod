package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.api.SessionTokenStore
import ai.starlake.quack.ondemand.rbac.StaffPrincipal
import ai.starlake.quack.ondemand.state.RbacUser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
