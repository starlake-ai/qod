package ai.starlake.quack.ondemand.fleet

import ai.starlake.quack.model.{
  NodePlacement,
  PoolCohort,
  PoolKey,
  Role,
  RoleDistribution,
  RunningNode
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class MissingSlotsSpec extends AnyFlatSpec with Matchers:
  private def node(id: String, role: Role) =
    RunningNode(id, PoolKey("a", "d", "p"), role, "h", 1, "t", None, None, Instant.EPOCH)

  "compute" should "return every slot for an empty pool in spawn order" in {
    MissingSlots.compute(RoleDistribution(writeonly = 1, readonly = 2, dual = 0), Nil) shouldBe
      List((1, Role.WriteOnly), (2, Role.ReadOnly), (3, Role.ReadOnly))
  }
  it should "return only the per-role deficit, indexed above the highest present index" in {
    val present = List(node("quack-a-d-p-1", Role.WriteOnly), node("quack-a-d-p-3", Role.ReadOnly))
    MissingSlots.compute(RoleDistribution(1, 2, 0), present) shouldBe List((4, Role.ReadOnly))
  }
  it should "return nothing at or above target" in {
    val present = List(node("quack-a-d-p-1", Role.Dual), node("quack-a-d-p-2", Role.Dual))
    MissingSlots.compute(RoleDistribution(0, 0, 1), present) shouldBe Nil
  }

  // Layout 1:W ssd, 2:W ssd, 3:W plain, 4:R plain (a cohort's roles in spawn order, W before R).
  private val ssd     = NodePlacement(Map("disktype" -> "ssd"))
  private val cohorts = List(
    PoolCohort(ssd, RoleDistribution(writeonly = 2, readonly = 0, dual = 0)),
    PoolCohort(NodePlacement.empty, RoleDistribution(writeonly = 1, readonly = 1, dual = 0))
  )
  private val layout = MissingSlots.layoutOf(cohorts)

  "layoutOf" should "list each cohort's roles in spawn order, cohorts in order" in {
    layout shouldBe List(
      (Role.WriteOnly, ssd),
      (Role.WriteOnly, ssd),
      (Role.WriteOnly, NodePlacement.empty),
      (Role.ReadOnly, NodePlacement.empty)
    )
  }

  "home" should "fold an index back onto the layout" in {
    (1 to 9).map(MissingSlots.home(_, 4)) shouldBe List(1, 2, 3, 4, 1, 2, 3, 4, 1)
  }

  "placementAt" should "be the placement of the index's home position" in {
    List(1, 2, 3, 4, 5, 6, 7, 8).map(MissingSlots.placementAt(layout, _)) shouldBe
      List(
        ssd,
        ssd,
        NodePlacement.empty,
        NodePlacement.empty,
        ssd,
        ssd,
        NodePlacement.empty,
        NodePlacement.empty
      )
  }

  "computeInLayout" should "refill uncovered positions with fresh ids that map home to them" in {
    val present = List(node("quack-a-d-p-3", Role.WriteOnly), node("quack-a-d-p-4", Role.ReadOnly))
    val slots   = MissingSlots.computeInLayout(layout, present)
    slots shouldBe List((5, Role.WriteOnly), (6, Role.WriteOnly))
    slots.map((i, _) => MissingSlots.home(i, layout.size)) shouldBe List(1, 2)
    slots.map((i, _) => MissingSlots.placementAt(layout, i)) shouldBe List(ssd, ssd)
  }

  it should "return nothing when every position is covered" in {
    val present = List(
      node("quack-a-d-p-1", Role.WriteOnly),
      node("quack-a-d-p-2", Role.WriteOnly),
      node("quack-a-d-p-3", Role.WriteOnly),
      node("quack-a-d-p-4", Role.ReadOnly)
    )
    MissingSlots.computeInLayout(layout, present) shouldBe Nil
  }

  it should "count a node whose role does not match its home once, on another position" in {
    // -1 is a reader (from before cohort-aware refill) on a writer's home: it covers the plain
    // reader position 4, the lowest free one with its role, and nothing else.
    val present = List(node("quack-a-d-p-1", Role.ReadOnly), node("quack-a-d-p-3", Role.WriteOnly))
    val slots   = MissingSlots.computeInLayout(layout, present)
    slots shouldBe List((5, Role.WriteOnly), (6, Role.WriteOnly))
    slots.map((i, _) => MissingSlots.placementAt(layout, i)) shouldBe List(ssd, ssd)
  }

  it should "count two nodes on the same home once each" in {
    // -1 and -5 both map home to 1: -5 covers the other ssd writer position (2).
    val present = List(
      node("quack-a-d-p-1", Role.WriteOnly),
      node("quack-a-d-p-5", Role.WriteOnly),
      node("quack-a-d-p-4", Role.ReadOnly)
    )
    MissingSlots.computeInLayout(layout, present) shouldBe List((7, Role.WriteOnly))
  }

  it should "never reuse an id at or below the high-water mark" in {
    // -9 (home 1) is the highest present index: the uncovered position 2 gets 10, above the
    // mark, never 2 or 6, which a dropped row may have used.
    val present = List(
      node("quack-a-d-p-9", Role.WriteOnly), // home 1
      node("quack-a-d-p-3", Role.WriteOnly),
      node("quack-a-d-p-4", Role.ReadOnly)
    )
    MissingSlots.computeInLayout(layout, present) shouldBe List((10, Role.WriteOnly))
  }

  "layoutFits" should "accept a layout whose per-role counts are the distribution's" in {
    MissingSlots.layoutFits(
      layout,
      RoleDistribution(writeonly = 3, readonly = 1, dual = 0)
    ) shouldBe
      true
  }

  it should "refuse a layout of the same size with other per-role counts" in {
    // A lagging cache after a constant-size role swap: same total, different roles.
    MissingSlots.layoutFits(
      layout,
      RoleDistribution(writeonly = 2, readonly = 2, dual = 0)
    ) shouldBe
      false
    MissingSlots.layoutFits(
      layout,
      RoleDistribution(writeonly = 4, readonly = 0, dual = 0)
    ) shouldBe
      false
  }

  it should "refuse a layout of another size" in {
    MissingSlots.layoutFits(
      layout,
      RoleDistribution(writeonly = 3, readonly = 2, dual = 0)
    ) shouldBe
      false
  }
