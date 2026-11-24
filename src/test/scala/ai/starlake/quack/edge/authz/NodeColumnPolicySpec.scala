package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter, SchemaContext}
import ai.starlake.quack.model.{PoolKey, StatementKind}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NodeColumnPolicySpec extends AnyFlatSpec with Matchers:
  "CLS enforcement mode" should "select one path and honor the kill switch" in {
    def config(mode: String, enabled: Boolean) = com.typesafe.config.ConfigFactory.parseString(
      s"""enforcement = "$mode", enabled = $enabled"""
    )
    ai.starlake.quack.edge.cls.ColumnPolicyEnforcement
      .nativeEnabled(config("node", true)) shouldBe true
    ai.starlake.quack.edge.cls.ColumnPolicyEnforcement
      .nativeEnabled(config("gateway", true)) shouldBe false
    ai.starlake.quack.edge.cls.ColumnPolicyEnforcement
      .nativeEnabled(config("node", false)) shouldBe false
    intercept[IllegalArgumentException] {
      ai.starlake.quack.edge.cls.ColumnPolicyEnforcement.nativeEnabled(config("fallback", true))
    }
  }

  private val user = RbacUser("u-1", Some("acme"), "alice", "user")
  private val mask = RoleColumnPolicy(
    "m1",
    "r",
    "memory",
    "main",
    "docs",
    "secret",
    "mask",
    Some("left(secret, 2) || '***'")
  )
  private def principal(policies: List[RoleColumnPolicy]): NodePrincipal =
    NodePrincipal.of(
      Some(EffectiveSet(user, Nil, Nil, Nil, Nil, columnPolicies = policies)),
      PoolKey("acme", "db", "bi"),
      DecidePurpose.Live
    )

  "native column policies" should "validate and carry the complete scope in deterministic order" in {
    val deny     = mask.copy(id = "d1", action = "deny", transformSql = None)
    val policies = NodeColumnPolicy.of(principal(List(mask, deny)))
    policies.map(_.id) shouldBe List("d1", "m1")
    policies.head shouldBe NodeColumnPolicy("d1", "memory", "main", "docs", "secret", "deny", "")
    policies.last.sql should include("left(secret, 2)")
    policies.forall(NodeColumnPolicy.valid) shouldBe true
  }

  it should "refuse unsafe stored masks without disclosing their SQL" in {
    val bad     = mask.copy(transformSql = Some("CAST(secret AS INTEGER)"))
    val message =
      intercept[IllegalArgumentException](NodeColumnPolicy.of(principal(List(bad)))).getMessage
    message should include("m1")
    message should not include "CAST"
    message should not include "secret"
  }

  it should "reject missing masks and invalid actions" in {
    intercept[IllegalArgumentException](
      NodeColumnPolicy.of(principal(List(mask.copy(transformSql = None))))
    )
    intercept[IllegalArgumentException](
      NodeColumnPolicy.of(principal(List(mask.copy(action = "unknown"))))
    )
  }

  it should "carry no policies for privileged or unbound callers" in {
    NodeColumnPolicy.of(NodePrincipal.System) shouldBe Nil
    NodeColumnPolicy.of(NodePrincipal.Unbound) shouldBe Nil
    NodeColumnPolicy.of(principal(List(mask)).copy(superuser = true)) shouldBe Nil
  }

  it should "sign and verify its column policy vector" in {
    val p        = principal(List(mask))
    val policies = NodeColumnPolicy.of(p)
    val handle   = NodeHandles.mint(
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
      columnPolicies = policies
    )
    NodeHandles
      .verify(handle.text, NodeHandles.nodeKey("secret", "node"), "node", 1000L)
      .toOption
      .get
      .columnPolicies shouldBe policies
  }

  it should "bound counts, UTF-8 lengths and action payload shapes" in {
    NodeColumnPolicy.valid(
      NodeColumnPolicy("m", "*", "*", "*", "c", "mask", "é" * 2049)
    ) shouldBe false
    NodeColumnPolicy.valid(NodeColumnPolicy("m", "*", "*", "*", "c", "deny", "NULL")) shouldBe false
    NodeColumnPolicy.valid(NodeColumnPolicy("m", "*", "*", "*", "c", "mask", "")) shouldBe false
  }

  it should "bypass gateway masking in native mode without reading the schema catalog" in {
    val catalog = new ColumnCatalog:
      def columnsOf(t: String, p: Option[PoolKey], c: String, s: String, n: String) =
        cats.effect.IO.raiseError[List[String]](new IllegalStateException("unexpected lookup"))
    val rewriter = new ColumnPolicyRewriter(catalog, nativeEnforcement = true)
    val p        = principal(List(mask))
    rewriter
      .rewrite(
        "SELECT secret, id // 2 FROM docs",
        StatementKind.Select,
        p.context.get.effective,
        SchemaContext(Some("memory"), Some("main"), Set("memory"))
      )
      .unsafeRunSync() shouldBe ColumnPolicyRewriter.Passthrough
  }

  it should "transport policies only when native CLS is enabled" in {
    val pool = PoolKey("acme", "db", "bi")
    val node = ai.starlake.quack.model.RunningNode(
      "node",
      pool,
      ai.starlake.quack.model.Role.Dual,
      "127.0.0.1",
      1,
      "t",
      None,
      None,
      java.time.Instant.EPOCH
    )
    def policies(enabled: Boolean) =
      val keys = new NodeKeys(
        "secret",
        "replica",
        clock = () => 1000L,
        versions = PolicyVersions.zero,
        readOnlyCatalogs = ReadOnlyCatalogs.none,
        nativeColumnPoliciesEnabled = enabled
      )
      NodeHandles
        .verify(keys.handle(node, principal(List(mask))), keys.key("node"), "node", 1000L)
        .toOption
        .get
        .columnPolicies
    policies(false) shouldBe Nil
    policies(true).map(_.id) shouldBe List("m1")
  }
