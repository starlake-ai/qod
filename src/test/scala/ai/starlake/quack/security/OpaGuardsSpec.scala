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
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true)
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
      val policies = authorized
        .synchronized(authorized.toList)
        .flatMap(_.hcursor.get[List[io.circe.Json]]("columnPolicies").toOption.toList.flatten)
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
