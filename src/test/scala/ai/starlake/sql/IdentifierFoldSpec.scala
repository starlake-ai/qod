package ai.starlake.sql

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DuckDB's identifier fold: ASCII `A`-`Z` only (`StringUtil::Lower`), checked on the 2.0.0 CLI,
  * where `"Clés"` and `"clÉs"` are two tables of one schema and a CTE `"clÉs"` does not shadow the
  * table `"Clés"`.
  */
class IdentifierFoldSpec extends AnyFlatSpec with Matchers:

  "fold" should "lower ASCII letters and leave every other character as it is" in {
    IdentifierFold.fold("Customer_ID") shouldBe "customer_id"
    IdentifierFold.fold("Clés") shouldBe "clés"
    IdentifierFold.fold("CLÉS") shouldBe "clÉs"
    IdentifierFold.fold("ÜBER") shouldBe "Über"
    IdentifierFold.fold("K") shouldBe "K" // the Kelvin sign is no k
    IdentifierFold.fold("İ") shouldBe "İ" // nor the dotted capital I an i
    IdentifierFold.fold("ΣΊΣΥΦΟΣ") shouldBe "ΣΊΣΥΦΟΣ"
    IdentifierFold.fold("") shouldBe ""
    IdentifierFold.fold(null) shouldBe null
  }

  it should "return the same instance when nothing folds" in {
    val s = "already_lower_é"
    IdentifierFold.fold(s) should be theSameInstanceAs s
  }

  "same" should "match names as DuckDB binds them" in {
    IdentifierFold.same("Clés", "CLéS") shouldBe true
    IdentifierFold.same("Clés", "clÉs") shouldBe false
    IdentifierFold.same("secret", "ſecret") shouldBe false // Java's equalsIgnoreCase: true
    IdentifierFold.same("kpi", "Kpi") shouldBe false
    IdentifierFold.same("system", "SYSTEM") shouldBe true
    IdentifierFold.same(null, null) shouldBe true
    IdentifierFold.same("a", null) shouldBe false
  }
