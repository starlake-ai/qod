package ai.starlake.quack.ondemand.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UserKindSpec extends AnyFlatSpec with Matchers:

  "UserKind.isSuperuser" should "hold for a tenant-less row of kind admin, in any case" in {
    UserKind.isSuperuser(None, "admin") shouldBe true
    UserKind.isSuperuser(None, "ADMIN") shouldBe true
    UserKind.isSuperuser(None, " admin ") shouldBe true
  }

  it should "not hold for another kind or for a tenant row" in {
    UserKind.isSuperuser(None, "user") shouldBe false
    UserKind.isSuperuser(None, "staff") shouldBe false
    UserKind.isSuperuser(Some("t"), "admin") shouldBe false
  }

  "RbacUser.isSuperuser and UserGrant.isSuperuser" should "apply the same predicate" in {
    RbacUser("u1", None, "root", kind = "Admin").isSuperuser shouldBe true
    RbacUser("u2", None, "ops", kind = "staff").isSuperuser shouldBe false
    RbacUser("u3", Some("t"), "boss", kind = "admin").isSuperuser shouldBe false
    UserGrant(None, "ADMIN").isSuperuser shouldBe true
    UserGrant(None, "user").isSuperuser shouldBe false
    UserGrant(Some("t"), "admin").isSuperuser shouldBe false
  }
