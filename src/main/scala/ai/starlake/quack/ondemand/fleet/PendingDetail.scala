package ai.starlake.quack.ondemand.fleet

import ai.starlake.quack.ondemand.state.FleetServerRow

/** The sentence behind a pending fleet slot: what every server is doing and the next command. Pure:
  * the caller passes the server listing and its liveness classifier.
  */
object PendingDetail:
  private val MaxNames = 5
  // Display order, and the order a server's state is decided in (first match wins).
  private val States = List("pending approval", "drained", "unreachable", "busy", "free")

  /** `selector` is the slot's cohort nodeSelector: `none_match` names it, and `none_fits` measures
    * only the free servers that carry it (the claim's own rule).
    */
  def describe(
      reason: String,
      rows: List[FleetServerRow],
      liveness: FleetServerRow => ServerLiveness,
      requiredMemoryBytes: Option[Long],
      selector: Map[String, String] = Map.empty
  ): String =
    val free        = rows.filter(stateOf(_, liveness) == "free")
    val largestFree = free
      .filter(r => carries(r, selector) && r.memoryBytes.isDefined)
      .maxByOption(_.memoryBytes.get)
    (reason, requiredMemoryBytes, largestFree) match
      case ("none_match", _, _) if selector.nonEmpty => noneMatch(selector, free)
      case ("none_fits", Some(need), Some(big))      =>
        s"needs ${gib(need)}; the largest free server (${big.name}) reports " +
          s"${gib(big.memoryBytes.get)}: add a server with more memory or lower the pool's " +
          "memory request"
      case _ => noneFree(rows, liveness)

  /** Which reason a pool shows when its slots miss for different ones: lower is more pressing. A
    * label or memory miss needs an operator action; a free-server miss can clear on its own when a
    * server joins or frees up.
    */
  def precedence(reason: String): Int = reason match
    case "none_match" => 0
    case "none_fits"  => 1
    case "none_free"  => 2
    case _            => 3

  // The claim's rule (labels @> selector): exact, case-sensitive pairs.
  private def carries(r: FleetServerRow, selector: Map[String, String]): Boolean =
    selector.forall((k, v) => r.labels.get(k).contains(v))

  private def noneMatch(selector: Map[String, String], free: List[FleetServerRow]): String =
    val wanted = FleetLabels.render(selector)
    FleetLabels.validate(selector) match
      // Not refused at pool create or import (that contract is shared with Kubernetes), so the
      // pending sentence is where an impossible selector surfaces.
      case Left(problem) =>
        s"needs labels $wanted, which no fleet server can carry ($problem): change the cohort " +
          "nodeSelector of the pool"
      case Right(_) =>
        val listed = free.sortBy(_.name).map(r => s"${r.name} [${FleetLabels.render(r.labels)}]")
        val shown  = if listed.isEmpty then "" else s" (free: ${capped(listed)})"
        val flags  = selector.toSeq.sortBy(_._1).map((k, v) => s"--label $k=$v").mkString(" ")
        val them   = if selector.size == 1 then "that label" else "those labels"
        s"needs labels $wanted; no free server has them$shown: run qod fleet join $flags on a " +
          s"server, or restart a free one with $them"

  private def stateOf(r: FleetServerRow, liveness: FleetServerRow => ServerLiveness): String =
    if !r.approved then "pending approval"
    else if r.unschedulable then "drained"
    else if liveness(r) != ServerLiveness.Reachable then "unreachable"
    else if r.assignedNodeId.isDefined then "busy"
    else "free" // taken by a concurrent claim between the miss and this listing

  private def noneFree(rows: List[FleetServerRow], liveness: FleetServerRow => ServerLiveness) =
    if rows.isEmpty then "no server has joined the fleet: run qod fleet join on a server"
    else
      val byState = rows.groupBy(stateOf(_, liveness))
      val parts   = States.flatMap(s => byState.get(s).map(rs => s"${rs.size} $s (${names(rs)})"))
      val steps   = States.flatMap(s => byState.get(s).flatMap(rs => step(s, rs.map(_.name).min)))
      val plural  = if rows.size == 1 then "" else "s"
      s"${rows.size} server$plural: ${parts.mkString(", ")}: " +
        (steps :+ "join another server").mkString(", or ")

  private def step(state: String, name: String): Option[String] = state match
    case "pending approval" => Some(s"qod fleet approve $name")
    case "drained"          => Some(s"qod fleet undrain $name")
    case "unreachable"      => Some(s"check qod fleet join on $name")
    case _                  => None

  private def names(rs: List[FleetServerRow]): String = capped(rs.map(_.name).sorted)

  // The first MaxNames of `items`, in the order given.
  private def capped(items: List[String]): String =
    val shown = items.take(MaxNames).mkString(", ")
    if items.size > MaxNames then s"$shown, +${items.size - MaxNames} more" else shown

  private def gib(bytes: Long): String = f"${bytes.toDouble / (1L << 30)}%.1f GiB"
