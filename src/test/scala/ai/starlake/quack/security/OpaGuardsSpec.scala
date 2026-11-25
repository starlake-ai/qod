package ai.starlake.quack.security

import ai.starlake.quack.edge.cls.ColumnCatalog
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.policy.ProtectedWriteGuard
import ai.starlake.quack.ondemand.state.{RbacRole, RoleColumnPolicy}
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.apache.arrow.flight.{FlightRuntimeException, FlightStatusCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** OPA decides access only (on the node, spec decision 29): an OPA that allows everything must not
  * drop the node's signed policies or switch off gateway guards. Over the real FlightSQL wire,
  * `bob` (opa tenant, no grant) holds a QoD role `masked` that carries a REAL column policy masking
  * `orders.email`.
  */
class OpaGuardsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  import OpaEdgeFixtures.*

  private val wm                 = new WireMockServer(options().dynamicPort())
  override def beforeAll(): Unit = wm.start()
  override def afterAll(): Unit  = wm.stop()

  private val MaskedRoleId = "r-masked01"

  /** One catalog shared by the SELECT-path rewriter and the write-path guard, as in production. */
  private val catalog =
    new ColumnCatalog.MapCatalog()

  private val authorized = scala.collection.mutable.ListBuffer.empty[io.circe.Json]

  /** The keys the stub adapter mints with, so the spec can read the policy bundles they name. */
  private val keys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral()

  /** Every policy of the bundle `id`, read page by page as the node reads it from `PolicyRoute`. */
  private def bundlePolicies(id: String, offset: Int = 0): List[io.circe.Json] =
    keys.policyBundles.page(id, offset).toList.flatMap { page =>
      val c    = page.hcursor
      val here = c.get[List[io.circe.Json]]("policies").getOrElse(Nil)
      c.get[Int]("next")
        .toOption
        .filter(_ >= 0)
        .fold(here)(next => here ++ bundlePolicies(id, next))
    }

  private def withBoot[A](body: FlightEdgeHarness.Harness => A): A =
    val fix = seed(s"http://localhost:${wm.port()}")
    val s   = fix.store
    s.upsertRole(
      RbacRole(id = MaskedRoleId, tenantId = SecurityFixtures.TenantId, name = "masked")
    )
    s.insertColumnPolicy(
      RoleColumnPolicy(
        id = "cp-orders-email",
        roleId = MaskedRoleId,
        catalogName = "*",
        schemaName = "main",
        tableName = "orders",
        columnName = "email",
        action = RoleColumnPolicy.ActionMask,
        transformSql = Some("'***'")
      )
    )
    s.addUserRole(fix.bobUserId, MaskedRoleId)
    val opa = authorizer()
    val h   = FlightEdgeHarness.boot(
      s,
      wiring = FlightEdgeHarness.RouterWiring(
        opa = Some(opa),
        nodeQuery = duckNode(OrdersSetup),
        onAuthorize = prelude => {
          val handle  = prelude.split("'", -1)(1)
          val payload = new String(
            java.util.Base64.getUrlDecoder.decode(handle.takeWhile(_ != '.')),
            java.nio.charset.StandardCharsets.UTF_8
          )
          authorized.synchronized(authorized += io.circe.parser.parse(payload).toOption.get)
        },
        protectedWriteGuard =
          new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true),
        spawnNodes = true,
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
        keys = keys
      )
    )
    try body(h)
    finally h.shutdown()

  private val bob = headers(SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)

  private def allowEverything(): Unit =
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, allow)

  "an allow-everything OPA" should "preserve column policies in the signed node handle" in:
    allowEverything()
    authorized.clear()
    withBoot { h =>
      val rows = query(h, bob, "SELECT id, email FROM orders")
      rows.map(_("id")) shouldBe List("1", "2")
      // This stub executes SQL without native hooks. Real masking is tested by the node suites.
      // A handle carries its policies as the digest of a signed bundle the node pages through.
      val policies = authorized
        .synchronized(authorized.toList)
        .flatMap(_.hcursor.get[String]("policyBundle").toOption.filter(_.nonEmpty))
        .distinct
        .flatMap(bundlePolicies(_))
        .filter(_.hcursor.get[String]("kind").contains("column"))
      policies.exists(_.hcursor.get[String]("id").contains("cp-orders-email")) shouldBe true
      policies.exists(_.hcursor.get[String]("sql").contains("'***'")) shouldBe true
      wm.verify(0, statementCalls)
    }

  it should "not lift the protected-write guard: a CTAS laundering the masked column is refused" in:
    allowEverything()
    withBoot { h =>
      val e = intercept[FlightRuntimeException](
        query(h, bob, "CREATE TABLE main.scratch AS SELECT email FROM orders")
      )
      e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
      e.getMessage should include("cannot be read into a write statement")
      wm.verify(0, statementCalls)
    }
