package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleRowPolicy}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{Header, Method, Request, Status}
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64

class RowPolicyRouteSpec extends AnyFlatSpec with Matchers:
  private val pool = PoolKey("acme", "db", "bi")
  private val node =
    RunningNode("n-1", pool, Role.Dual, "127.0.0.1", 1, "t", None, None, Instant.EPOCH)
  private val clock                                = 1000L
  private def effective(rows: List[RoleRowPolicy]) = EffectiveSet(
    RbacUser("u-1", Some("acme"), "alice", "user"),
    Nil,
    Nil,
    Nil,
    Nil,
    rowPolicies = rows
  )
  private def policy(i: Int, sql: String = "owner = ${user}") =
    RoleRowPolicy(s"p-$i", "r-1", "*", "main", s"docs$i", sql)
  private val keys = new NodeKeys(
    "route-test-secret",
    "http://localhost:1",
    clock = () => clock,
    versions = PolicyVersions.zero,
    readOnlyCatalogs = ReadOnlyCatalogs.none,
    nodeRowPolicies = true
  )
  private val route                                   = new RowPolicyRoute(keys, () => clock)
  private def mint(rows: List[RoleRowPolicy]): String =
    keys.handle(node, NodePrincipal.of(Some(effective(rows)), pool, DecidePurpose.Live))
  private def call(handle: String, offset: Int = 0, badSignature: Boolean = false) =
    val bytes = Json
      .obj("handle" -> Json.fromString(handle), "offset" -> Json.fromInt(offset))
      .noSpaces
      .getBytes(UTF_8)
    val sig =
      if badSignature then "0" * 64
      else NodeHandles.hex(NodeHandles.hmac(keys.key(node.nodeId), bytes))
    val request = Request[IO](Method.POST, uri"/api/internal/authz/row-policies")
      .withEntity(bytes)
      .putHeaders(
        Header.Raw(CIString("X-QoD-Node"), node.nodeId),
        Header.Raw(CIString("X-QoD-Signature"), sig)
      )
    val response = route.routes.orNotFound.run(request).unsafeRunSync()
    (response.status, response.as[String].unsafeRunSync())
  private def page(text: String): Json =
    val parts = text.split('.')
    val bytes = Base64.getUrlDecoder.decode(parts(0))
    parts(1) shouldBe NodeHandles.hex(NodeHandles.hmac(keys.key(node.nodeId), bytes))
    parse(new String(bytes, UTF_8)).toOption.get

  "row policy snapshots" should "page more than 256 policies behind a small signed handle" in {
    val handle = mint((1 to 600).map(i => policy(i)).toList)
    handle.getBytes(UTF_8).length should be < 2048
    val verified =
      NodeHandles.verify(handle, keys.key(node.nodeId), node.nodeId, clock).toOption.get
    verified.rowPolicies shouldBe Nil
    verified.rowPolicyBundle.length shouldBe 64
    var offset = 0
    var count  = 0
    while offset >= 0 do
      val (status, text) = call(handle, offset)
      status shouldBe Status.Ok
      text.getBytes(UTF_8).length should be < 1024 * 1024
      val data = page(text)
      data.hcursor.get[String]("bundle").toOption.get shouldBe verified.rowPolicyBundle
      count += data.hcursor.get[List[Json]]("rows").toOption.get.size
      offset = data.hcursor.get[Int]("next").toOption.get
    count shouldBe 600
  }

  it should "mark an invalid stored predicate on its own scope without failing handle minting" in {
    val handle         = mint(List(policy(1, "owner = ${typo}"), policy(2)))
    val (status, text) = call(handle)
    status shouldBe Status.Ok
    val rows = page(text).hcursor.get[List[NodeRowPolicy]]("rows").toOption.get
    rows.head.sql shouldBe NodeRowPolicy.InvalidPredicate
    rows(1).sql shouldBe "owner = 'alice'"
    rows.head.table shouldBe "docs1"
  }

  it should "reject forged calls and even correctly signed handles this replica never minted" in {
    val handle = mint(List(policy(1)))
    call(handle, badSignature = true)._1 shouldBe Status.Unauthorized
    val h      = NodeHandles.verify(handle, keys.key(node.nodeId), node.nodeId, clock).toOption.get
    val forged = NodeHandles.mint(
      "route-test-secret",
      node.nodeId,
      "http://localhost:1",
      NodePrincipal("other", "other", Some("acme"), false, false),
      "acme/db/bi",
      0,
      NodeHandles.EmptyContextDigest,
      "",
      "",
      Set.empty,
      clock,
      rowPolicyBundle = h.rowPolicyBundle
    )
    call(forged.text)._1 shouldBe Status.Unauthorized
  }

  it should "page identity-expanded SQL larger than the decide request limit" in {
    val rows   = (1 to 40).map(i => policy(i, "owner IN (${groups})")).toList
    val groups = (1 to 400)
      .map(i => ai.starlake.quack.ondemand.state.RbacGroup(s"g-$i", "acme", "x" * 100))
      .toList
    val principal =
      NodePrincipal.of(Some(effective(rows).copy(groups = groups)), pool, DecidePurpose.Live)
    val handle = keys.handle(node, principal)
    handle.length should be < 2048
    var offset = 0
    var bytes  = 0
    var count  = 0
    while offset >= 0 do
      val (status, response) = call(handle, offset)
      status shouldBe Status.Ok
      response.getBytes(UTF_8).length should be < 1024 * 1024
      val json    = page(response)
      val entries = json.hcursor.get[List[NodeRowPolicy]]("rows").toOption.get
      bytes += entries.map(_.sql.getBytes(UTF_8).length).sum
      count += entries.size
      offset = json.hcursor.get[Int]("next").toOption.get
    count shouldBe 40
    bytes should be > 1024 * 1024
  }

  it should "invalidate prepared snapshots when identity names change without ids or versions changing" in {
    def handle(name: String) =
      val e = effective(List(policy(1, "owner IN (${groups})")))
        .copy(groups = List(ai.starlake.quack.ondemand.state.RbacGroup("same-id", "acme", name)))
      keys.handle(node, NodePrincipal.of(Some(e), pool, DecidePurpose.Live))
    val first  = handle("first")
    val second = handle("second")
    val a      = NodeHandles.verify(first, keys.key(node.nodeId), node.nodeId, clock).toOption.get
    val b      = NodeHandles.verify(second, keys.key(node.nodeId), node.nodeId, clock).toOption.get
    a.rowPolicyBundle should not be b.rowPolicyBundle
    page(call(second)._2).hcursor
      .get[List[NodeRowPolicy]]("rows")
      .toOption
      .get
      .head
      .sql should include("'second'")
  }
