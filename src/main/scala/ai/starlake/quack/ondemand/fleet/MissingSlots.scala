package ai.starlake.quack.ondemand.fleet

import ai.starlake.quack.model.{NodePlacement, PoolCohort, Role, RoleDistribution, RunningNode}

import scala.collection.mutable

object MissingSlots:
  /** (nodeIndex, role) slots to spawn so `present` reaches `distribution`. Indices start above the
    * highest index parsed from present ids (trailing `-<n>`), never below present.size + 1.
    */
  def compute(distribution: RoleDistribution, present: List[RunningNode]): List[(Int, Role)] =
    val deficit: List[Role] = RoleDistribution.spawnOrder.flatMap { role =>
      List.fill((distribution.countFor(role) - present.count(_.role == role)).max(0))(role)
    }
    val base = nextIndexBase(present)
    deficit.zipWithIndex.map { case (role, i) => (base + i + 1, role) }

  /** Index after which fresh node ids are numbered: the highest trailing `-<n>` parsed from
    * `present` ids, never below `present.size`. Fresh ids are `base + 1, base + 2, ...`, so they
    * never collide with a present node even when a mid-index row was dropped.
    */
  def nextIndexBase(present: List[RunningNode]): Int =
    val highest = present.flatMap(n => indexOf(n.nodeId)).maxOption.getOrElse(0)
    math.max(highest, present.size)

  /** A pool's creation layout: (role, placement) at positions 1..N, cohorts in order and each
    * cohort's roles in spawn order. Node `-i` of a pool spawned at creation sits at position i.
    */
  def layoutOf(cohorts: List[PoolCohort]): List[(Role, NodePlacement)] =
    cohorts.flatMap(c => c.distribution.asRoleList.map(r => (r, c.placement)))

  /** The layout position (1..size) node index `index` belongs to. Refilled ids run above the
    * high-water mark, so an index past the layout folds back onto it and takes its home's cohort.
    */
  def home(index: Int, size: Int): Int = Math.floorMod(index - 1, size) + 1

  /** The placement of node index `index`: its home position's. Empty for an empty layout. */
  def placementAt(layout: List[(Role, NodePlacement)], index: Int): NodePlacement =
    if layout.isEmpty then NodePlacement.empty else layout(home(index, layout.size) - 1)._2

  /** [[compute]] for a pool with cohorts: (nodeIndex, role) slots for the layout positions no
    * present node covers, each numbered so it maps home to its position (and so inherits that
    * position's cohort placement through [[placementAt]]). Ids stay fresh: the smallest index above
    * [[nextIndexBase]] with that home, so a present or dropped id is never reused.
    *
    * A present node covers its home when its role is the home's. One that does not (a second node
    * on one home, or a role that does not match, as a node from before this rule may have) covers
    * the lowest uncovered position with its role instead, or nothing, so no node is counted twice.
    * Nodes are taken in index order, which keeps the choice deterministic.
    */
  def computeInLayout(
      layout: List[(Role, NodePlacement)],
      present: List[RunningNode]
  ): List[(Int, Role)] =
    if layout.isEmpty then Nil
    else
      val size    = layout.size
      val roleAt  = (h: Int) => layout(h - 1)._1
      val covered = mutable.Set.empty[Int]
      // An id with no index (none is minted so) cannot map home: it only covers by role, last.
      val ordered = present.sortBy(n => indexOf(n.nodeId).getOrElse(Int.MaxValue))
      val strayed = mutable.ListBuffer.empty[RunningNode]
      ordered.foreach { n =>
        indexOf(n.nodeId).map(home(_, size)) match
          case Some(h) if roleAt(h) == n.role && !covered(h) => covered += h
          case _                                             => strayed += n
      }
      strayed.foreach { n =>
        (1 to size).find(h => !covered(h) && roleAt(h) == n.role).foreach(covered += _)
      }
      val base = nextIndexBase(present)
      (1 to size).toList.filterNot(covered).map { h =>
        (base + Math.floorMod(h - 1 - base, size) + 1, roleAt(h))
      }

  private def indexOf(nodeId: String): Option[Int] =
    nodeId.split('-').lastOption.flatMap(_.toIntOption)
