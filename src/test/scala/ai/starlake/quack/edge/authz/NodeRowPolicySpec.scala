package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacGroup, RbacUser, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NodeRowPolicySpec extends AnyFlatSpec with Matchers:
  private val user = RbacUser("u-1", Some("acme"), "O'Brien", "user")
  private def effective(predicate: String): EffectiveSet =
    EffectiveSet(
      user,
      Nil,
      List(RbacGroup("g-1", "acme", "sales")),
      Nil,
      Nil,
      rowPolicies = List(RoleRowPolicy("rp-1", "r-1", "*", "main", "docs", predicate))
    )
  private def principal(predicate: String): NodePrincipal =
    NodePrincipal.of(Some(effective(predicate)), PoolKey("acme", "db", "bi"), DecidePurpose.Live)

  "native row policies" should "use escaped user and list literals from the authenticated snapshot" in {
    NodeRowPolicy.of(
      principal("owner = ${user} AND team IN (${groups}) AND tenant = ${tenantId}")
    ) shouldBe
      List(
        NodeRowPolicy(
          "rp-1",
          "*",
          "main",
          "docs",
          "owner = 'O''Brien' AND team IN ('sales') AND tenant = 'acme'"
        )
      )
  }

  it should "refuse unknown tokens instead of weakening a negative comparison" in {
    intercept[IllegalArgumentException] {
      NodeRowPolicy.of(principal("owner IS DISTINCT FROM ${usr}"))
    }.getMessage should include("unknown identity token")
  }

  it should "carry no policies for privileged or unbound principals" in {
    NodeRowPolicy.of(NodePrincipal.System) shouldBe Nil
    NodeRowPolicy.of(NodePrincipal.Unbound) shouldBe Nil
    NodeRowPolicy.of(principal("id=1").copy(superuser = true)) shouldBe Nil
  }

  it should "sign and read back policy scope and SQL in the session handle" in {
    val p      = principal("owner = ${user}")
    val secret = "test-secret"
    val rows   = NodeRowPolicy.of(p)
    val minted = NodeHandles.mint(
      secret,
      "node-1",
      "http://localhost:1",
      p,
      "acme/db/bi",
      policyVersion = 1,
      ctx = NodeHandles.contextDigest(p.context),
      creds = "",
      path = "memory.main",
      readOnly = Set.empty,
      nowMs = 1000,
      rowPolicies = rows
    )
    NodeHandles
      .verify(minted.text, NodeHandles.nodeKey(secret, "node-1"), "node-1", 1000)
      .toOption
      .get
      .rowPolicies shouldBe rows
    NodeHandles
      .verify(minted.text.replace('A', 'B'), NodeHandles.nodeKey(secret, "node-1"), "node-1", 1000)
      .isLeft shouldBe true
  }

  it should "sign row policies by default and omit them only when the kill switch is off" in {
    val pool = PoolKey("acme", "db", "bi")
    val node = ai.starlake.quack.model.RunningNode(
      "node-1",
      pool,
      ai.starlake.quack.model.Role.Dual,
      "127.0.0.1",
      1,
      "t",
      None,
      None,
      java.time.Instant.EPOCH
    )
    val secret  = "test-secret"
    val p       = principal("owner = ${user}")
    val enabled = new NodeKeys(
      secret,
      "r",
      clock = () => 1000L,
      versions = PolicyVersions.zero,
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )
    val disabled = new NodeKeys(
      secret,
      "r",
      clock = () => 1000L,
      versions = PolicyVersions.zero,
      readOnlyCatalogs = ReadOnlyCatalogs.none,
      rowPoliciesEnabled = false
    )
    def rows(keys: NodeKeys) = NodeHandles
      .verify(
        keys.handle(node, p),
        keys.key(node.nodeId),
        node.nodeId,
        1000L
      )
      .toOption
      .get
      .rowPolicies
    rows(enabled) shouldBe NodeRowPolicy.of(p)
    rows(disabled) shouldBe Nil
  }

  it should "bound policy counts and the expanded UTF-8 predicate size" in {
    NodeRowPolicy.valid(NodeRowPolicy("p", "*", "*", "t", "é" * 32769)) shouldBe false
    NodeRowPolicy.valid(NodeRowPolicy("p", "*", "*", "t", "")) shouldBe false
  }

  it should "leave native reads to the node while retaining the protected-write guard" in {
    val catalog = new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(Map.empty)
    val native  = new ai.starlake.quack.edge.policy.ProtectedWriteGuard(
      catalog,
      clsEnabled = false,
      rlsEnabled = true
    )
    val e   = effective("owner = ${user}")
    val ctx =
      ai.starlake.quack.edge.cls.SchemaContext(Some("memory"), Some("main"), Set.empty, None)
    import ai.starlake.quack.edge.policy.GuardOutcome
    import ai.starlake.quack.model.StatementKind
    native.check(
      "SELECT * FROM query_table('docs')",
      StatementKind.Select,
      e,
      ctx
    ) shouldBe GuardOutcome.Allow
    native.check("DELETE FROM docs", StatementKind.Dml, e, ctx) shouldBe a[GuardOutcome.Deny]
  }
