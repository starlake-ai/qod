package ai.starlake.quack.edge.authz

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ExtraFunctionClassesSpec extends AnyFlatSpec with Matchers:

  private val linked = List(("qod_test_linked_scan", "table", "allowed"))

  FunctionClasses.installExtra(linked) shouldBe Right(())

  "FunctionClasses.of" should "class a module's linked function" in {
    FunctionClasses.of("system", "main", "qod_test_linked_scan", "table") shouldBe Some(
      FunctionClass.Allowed
    )
  }

  "FunctionClasses.installExtra" should "refuse reclassifying a built-in" in {
    FunctionClasses.validateExtra(List(("read_csv", "table", "allowed"))).isLeft shouldBe true
  }

  it should "refuse a superuser or file class" in {
    FunctionClasses.validateExtra(List(("qod_test_x", "scalar", "superuser"))).isLeft shouldBe true
    FunctionClasses.validateExtra(List(("qod_test_y", "table", "file"))).isLeft shouldBe true
  }

  it should "refuse an unsupported type" in {
    FunctionClasses.validateExtra(List(("qod_test_m", "macro", "allowed"))).isLeft shouldBe true
  }

  it should "accept a listing class" in {
    FunctionClasses.validateExtra(List(("qod_test_l", "table", "listing"))).isRight shouldBe true
  }

  it should "be idempotent for the same rows and refuse a different set" in {
    FunctionClasses.installExtra(linked) shouldBe Right(())
    FunctionClasses.installExtra(List(("qod_test_other", "table", "allowed"))).isLeft shouldBe true
    FunctionClasses.of("system", "main", "qod_test_other", "table") shouldBe None
    FunctionClasses.of("system", "main", "qod_test_linked_scan", "table") shouldBe Some(
      FunctionClass.Allowed
    )
  }
