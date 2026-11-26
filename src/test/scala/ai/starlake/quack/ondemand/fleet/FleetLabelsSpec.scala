package ai.starlake.quack.ondemand.fleet

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FleetLabelsSpec extends AnyFlatSpec with Matchers:

  "FleetLabels.problem" should "accept plain, empty-value and prefixed labels" in {
    FleetLabels.problem("disktype", "ssd") shouldBe None
    FleetLabels.problem("tier", "") shouldBe None
    FleetLabels.problem("example.com/zone", "eu-1") shouldBe None
  }

  it should "reject a key starting with a dash" in {
    FleetLabels.problem("-bad", "x").get should include("-bad")
  }

  it should "reject a key name of 64 characters" in {
    val key = "a" * 64
    FleetLabels.problem(key, "x").get should include(key)
  }

  it should "reject a prefix of 254 characters" in {
    val key = ("a." * 126) + "aa/name"
    FleetLabels.problem(key, "x").get should include(key)
  }

  it should "reject a value with a space" in {
    FleetLabels.problem("k", "a b").get should include("k")
  }

  it should "reject a value with an equals sign" in {
    FleetLabels.problem("k", "a=b").get should include("k")
  }

  it should "reject a value of 64 characters" in {
    FleetLabels.problem("k", "a" * 64).get should include("k")
  }

  "FleetLabels.validate" should "accept a valid map" in {
    val labels = Map("disktype" -> "ssd", "tier" -> "")
    FleetLabels.validate(labels) shouldBe Right(labels)
  }

  it should "reject more than 64 labels" in {
    val labels = (1 to 65).map(i => s"k$i" -> "v").toMap
    FleetLabels.validate(labels) shouldBe Left("at most 64 labels per server, got 65")
  }

  it should "report the first problem sorted by key" in {
    val result = FleetLabels.validate(Map("zz" -> "a b", "-aa" -> "x"))
    result.left.toOption.get should include("-aa")
  }

  "FleetLabels.render" should "sort by key" in {
    FleetLabels.render(Map("b" -> "2", "a" -> "1")) shouldBe "a=1, b=2"
  }

  it should "say no labels when empty" in {
    FleetLabels.render(Map.empty) shouldBe "no labels"
  }
