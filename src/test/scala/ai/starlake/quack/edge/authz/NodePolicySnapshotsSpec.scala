package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NodePolicySnapshotsSpec extends AnyFlatSpec with Matchers:
  private val mask =
    RoleColumnPolicy("m", "r", "memory", "main", "docs", "secret", "mask", Some("'***'"))
  private def principal(policies: List[RoleColumnPolicy], pool: String = "bi") = NodePrincipal.of(
    Some(
      EffectiveSet(
        RbacUser("u", Some("acme"), "alice", "user"),
        Nil,
        Nil,
        Nil,
        Nil,
        columnPolicies = policies
      )
    ),
    PoolKey("acme", "db", pool),
    DecidePurpose.Live
  )
  private def mint(
      p: NodePrincipal,
      columns: List[NodeColumnPolicy],
      rows: List[NodeRowPolicy] = Nil
  ) =
    NodeHandles.mint(
      "secret",
      "node",
      "replica",
      p,
      "acme/db/bi",
      1L,
      NodeHandles.contextDigest(p.context),
      "",
      "memory.main",
      Set.empty,
      1000L,
      rowPolicies = rows,
      columnPolicies = columns
    )

  "policy snapshots" should "compile once per context, policy version and enable flags" in {
    var compiled  = 0
    val snapshots = new NodePolicySnapshots((e, rows, columns) => {
      compiled += 1
      NodePolicySnapshot(
        if rows then NodeRowPolicy.ofEffective(e) else Nil,
        if columns then NodeColumnPolicy.ofEffective(e) else Nil
      )
    })
    def read(p: NodePrincipal, version: Long = 1L, enabled: Boolean = true) =
      snapshots.of(p, NodeHandles.contextDigest(p.context), version, true, enabled)
    val p = principal(List(mask))
    read(p) should be theSameInstanceAs read(principal(List(mask)))
    compiled shouldBe 1
    read(p, version = 2L)
    read(p, enabled = false).columns shouldBe Nil
    read(principal(List(mask.copy(transformSql = Some("'changed'")))))
    read(principal(List(mask), pool = "etl"))
    compiled shouldBe 5
    read(NodePrincipal.System) shouldBe NodePolicySnapshot.Empty
    read(NodePrincipal.Unbound) shouldBe NodePolicySnapshot.Empty
    compiled shouldBe 5
  }

  it should "retain more than 256 policies across catalogs and both policy kinds" in {
    val many    = (1 to 300).toList.map(i => mask.copy(id = s"m$i", catalogName = s"db_$i"))
    val p       = principal(many)
    val columns = NodeColumnPolicy.of(p)
    val rows   = (1 to 300).toList.map(i => NodeRowPolicy(s"r$i", s"db_$i", "main", "docs", "true"))
    val handle = mint(p, columns, rows)
    val verified = NodeHandles
      .verify(handle.text, NodeHandles.nodeKey("secret", "node"), "node", 1000L)
      .toOption
      .get
    verified.columnPolicies shouldBe columns
    verified.rowPolicies shouldBe rows
  }

  it should "preserve unrelated bad policies as local restrictions instead of failing minting" in {
    val bad    = mask.copy(catalogName = "db_b", transformSql = Some("CAST(secret AS INTEGER)"))
    val p      = principal(List(bad))
    val handle = mint(p, NodeColumnPolicy.of(p))
    NodeHandles
      .verify(handle.text, NodeHandles.nodeKey("secret", "node"), "node", 1000L)
      .toOption
      .get
      .columnPolicies
      .head shouldBe NodeColumnPolicy("m", "db_b", "main", "docs", "secret", "deny", "")
  }

  it should "bound encoded and decoded bytes without truncating the policy set" in {
    val p     = principal(List(mask))
    val large = (1 to 600).toList.map(i =>
      NodeColumnPolicy(s"m$i", "*", "*", "*", "secret", "mask", "x" * 4096)
    )
    intercept[IllegalArgumentException](mint(p, large)).getMessage should include("byte budget")
    NodeHandles
      .verify("x" * (NodeHandles.MaxPayloadBytes * 2), Array.emptyByteArray, "node", 1000L)
      .left
      .toOption
      .get should include("byte budget")
  }
