package ai.starlake.quack.security

import ai.starlake.quack.ondemand.module.ResolvedSourceType
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `GET /api/federated-source-types`: the module source types the console's federation form offers.
  * Any admin session reads them (a tenant admin or a superuser), like the federated-source routes
  * the form calls; the guard refuses a tenant user, a staff caller and an anonymous one.
  */
class FederatedSourceTypesRestSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val Types = List(
    ResolvedSourceType(
      "test-module",
      "test_connector",
      "Test connector",
      Some("/test-module/editor.js")
    ),
    ResolvedSourceType("test-module", "plain_connector", "plain_connector", None)
  )

  private val Path = "/api/federated-source-types"

  private def json(body: String): Json =
    parse(body).fold(e => fail(s"not JSON ($e): $body"), identity)

  private def withHarness(types: List[ResolvedSourceType])(
      f: ManagerServerHarness.Harness => Unit
  ): Unit =
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, sourceTypes = types)
    try f(h)
    finally h.shutdown()

  private def typesAs(h: ManagerServerHarness.Harness, token: String): List[Json] =
    val r = get(h.httpClient, s"${h.baseUrl}$Path", apiKey = Some(token))
    withClue(s"$Path: ${r.body()}")(r.statusCode() shouldBe 200)
    json(r.body()).hcursor.downField("types").values.getOrElse(Nil).toList

  "GET /api/federated-source-types" should "list every module source type to an admin session" in
    withHarness(Types) { h =>
      val asRoot = typesAs(h, h.superuserSession())
      asRoot.map(_.hcursor.get[String]("sourceType")) shouldBe
        List(Right("test_connector"), Right("plain_connector"))
      val first = asRoot.head.hcursor
      first.get[String]("module") shouldBe Right("test-module")
      first.get[String]("label") shouldBe Right("Test connector")
      first.get[String]("editorBundle") shouldBe Right("/test-module/editor.js")
      asRoot(1).hcursor.get[String]("label") shouldBe Right("plain_connector")
      asRoot(1).hcursor.downField("editorBundle").focus shouldBe Some(Json.Null)

      val alice = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      typesAs(h, alice) shouldBe asRoot
    }

  it should "answer an empty list when no module contributes a type" in
    withHarness(Nil) { h =>
      typesAs(h, h.superuserSession()) shouldBe empty
    }

  it should "refuse a tenant user, a staff caller and an anonymous caller" in
    withHarness(Types) { h =>
      val bob = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        Some(SecurityFixtures.TenantId)
      )
      for (who, token) <- List("tenant user" -> bob, "staff" -> h.staffSession("sam", Set.empty))
      do
        val r = get(h.httpClient, s"${h.baseUrl}$Path", apiKey = Some(token))
        withClue(s"$who: ${r.body()}") {
          r.statusCode() shouldBe 403
          errorCode(r.body()) should contain("admin_required")
        }
      get(h.httpClient, s"${h.baseUrl}$Path").statusCode() shouldBe 401
    }
