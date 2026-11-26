package ai.starlake.quack.ondemand.fleet

import ai.starlake.quack.ondemand.state.FleetServerRow
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class PendingDetailSpec extends AnyFlatSpec with Matchers:

  private def row(
      name: String,
      approved: Boolean = true,
      drained: Boolean = false,
      assigned: Option[String] = None,
      silent: Long = 0,
      mem: Option[Long] = Some(4L << 30),
      labels: Map[String, String] = Map.empty
  ) = FleetServerRow(
    name = name,
    advertiseHost = "10.0.0.1",
    nodePort = 21900,
    joinedAt = Instant.EPOCH,
    unschedulable = drained,
    assignedNodeId = assigned,
    assignment = None,
    assignmentEpoch = 0,
    claimedAt = None,
    lastHeartbeatAt = Instant.EPOCH,
    silentSeconds = silent,
    claimAgeSeconds = None,
    qodVersion = None,
    os = None,
    duckdbVersion = None,
    cpus = None,
    memoryBytes = mem,
    nodeState = "none",
    nodeError = None,
    nodePid = None,
    nodeStartedAt = None,
    approved = approved,
    approvedAt = None,
    approvedBy = None,
    sourceAddr = None,
    approvedSource = None,
    labels = labels
  )

  private val live: FleetServerRow => ServerLiveness =
    r => FleetLiveness.classify(r.silentSeconds, 30, 600)

  "describe" should "compare the memory a slot needs with the largest free server" in {
    PendingDetail.describe(
      "none_fits",
      List(row("a"), row("b", mem = Some(2L << 30))),
      live,
      Some(8L << 30)
    ) shouldBe
      "needs 8.0 GiB; the largest free server (a) reports 4.0 GiB: add a server with more " +
      "memory or lower the pool's memory request"
  }

  it should "count servers by state and name the next command for each" in {
    val rows = List(
      row("a", drained = true),
      row("b", approved = false),
      row("c", assigned = Some("n1"))
    )
    PendingDetail.describe("none_free", rows, live, None) shouldBe
      "3 servers: 1 pending approval (b), 1 drained (a), 1 busy (c): qod fleet approve b, " +
      "or qod fleet undrain a, or join another server"
  }

  it should "say so when no server has joined" in {
    PendingDetail.describe("none_free", Nil, live, None) shouldBe
      "no server has joined the fleet: run qod fleet join on a server"
  }

  it should "point at the join process of an unreachable server" in {
    PendingDetail.describe("none_free", List(row("d", silent = 100)), live, None) shouldBe
      "1 server: 1 unreachable (d): check qod fleet join on d, or join another server"
  }

  it should "cap the names listed per state" in {
    val rows = (1 to 7).toList.map(i => row(s"s$i", assigned = Some(s"n$i")))
    PendingDetail.describe("none_free", rows, live, None) shouldBe
      "7 servers: 7 busy (s1, s2, s3, s4, s5, +2 more): join another server"
  }

  "describe (none_match)" should "name the selector and the labels of every free server" in {
    val rows = List(
      row("db-2"),
      row("db-1", labels = Map("tier" -> "highmem")),
      row("busy", assigned = Some("n1"), labels = Map("disktype" -> "ssd"))
    )
    PendingDetail.describe("none_match", rows, live, None, Map("disktype" -> "ssd")) shouldBe
      "needs labels disktype=ssd; no free server has them (free: db-1 [tier=highmem], " +
      "db-2 [no labels]): run qod fleet join --label disktype=ssd on a server, or restart a " +
      "free one with that label"
  }

  it should "cap the free servers it lists" in {
    val rows = (1 to 7).toList.map(i => row(s"s$i"))
    PendingDetail.describe("none_match", rows, live, None, Map("a" -> "b", "c" -> "d")) shouldBe
      "needs labels a=b, c=d; no free server has them (free: s1 [no labels], s2 [no labels], " +
      "s3 [no labels], s4 [no labels], s5 [no labels], +2 more): run qod fleet join --label a=b " +
      "--label c=d on a server, or restart a free one with those labels"
  }

  it should "say a selector outside the label rules can never match a fleet server" in {
    val problem = FleetLabels.problem("-bad", "x").get
    PendingDetail.describe("none_match", List(row("a")), live, None, Map("-bad" -> "x")) should
      startWith(s"needs labels -bad=x, which no fleet server can carry ($problem)")
  }

  "describe (none_fits)" should "measure only the free servers that carry the selector" in {
    val rows = List(
      row("big", mem = Some(64L << 30)),
      row("ssd", mem = Some(2L << 30), labels = Map("disktype" -> "ssd"))
    )
    PendingDetail.describe(
      "none_fits",
      rows,
      live,
      Some(8L << 30),
      Map("disktype" -> "ssd")
    ) shouldBe
      "needs 8.0 GiB; the largest free server (ssd) reports 2.0 GiB: add a server with more " +
      "memory or lower the pool's memory request"
  }

  "precedence" should "rank none_match, then none_fits, then none_free, then anything else" in {
    List("none_match", "none_fits", "none_free", "other").map(PendingDetail.precedence) shouldBe
      List(0, 1, 2, 3)
    List("none_free", "other", "none_match", "none_fits").sortBy(PendingDetail.precedence) shouldBe
      List("none_match", "none_fits", "none_free", "other")
  }
