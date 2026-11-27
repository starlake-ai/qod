package ai.starlake.quack.ondemand.api

import Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.module.ResolvedSourceType
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

class FederatedSourceTypeHandlersSpec extends AnyFlatSpec with Matchers:

  private val types = List(
    ResolvedSourceType(
      "test-module",
      "test_connector",
      "Test connector",
      Some("/test-module/editor.js"),
      editsHeldSources = true
    ),
    ResolvedSourceType("test-module", "plain_connector", "plain_connector", None)
  )

  private val scopeOf: SessionScope.Resolver = {
    case Some("admin") => SessionScope(superuser = false, manageableTenants = Set("acme"))
    case Some("root")  => SessionScope.Superuser
    case _             => SessionScope.NoAccess
  }

  private val handlers = new FederatedSourceTypeHandlers(types, scopeOf)

  "FederatedSourceTypeHandlers.list" should "answer every module source type to a signed-in caller" in {
    for key <- List("admin", "root") do
      withClue(key) {
        handlers.list(Some(key)).unsafeRunSync() shouldBe Right(
          FederatedSourceTypesResponse(
            List(
              FederatedSourceTypeView(
                "test-module",
                "test_connector",
                "Test connector",
                Some("/test-module/editor.js"),
                editsHeldSources = true
              ),
              FederatedSourceTypeView(
                "test-module",
                "plain_connector",
                "plain_connector",
                None,
                editsHeldSources = false
              )
            )
          )
        )
      }
  }

  it should "encode a type without an editor with a null editorBundle" in {
    val json = handlers.list(Some("admin")).unsafeRunSync().toOption.get.asJson
    json.hcursor.downField("types").downN(1).downField("editorBundle").focus shouldBe
      Some(Json.Null)
  }

  it should "encode editsHeldSources on every type" in {
    val json  = handlers.list(Some("admin")).unsafeRunSync().toOption.get.asJson
    val types = json.hcursor.downField("types")
    types.downN(0).downField("editsHeldSources").focus shouldBe Some(Json.True)
    types.downN(1).downField("editsHeldSources").focus shouldBe Some(Json.False)
  }

  it should "answer an empty list when no module contributes a type" in {
    new FederatedSourceTypeHandlers(Nil, scopeOf).list(Some("root")).unsafeRunSync() shouldBe
      Right(FederatedSourceTypesResponse(Nil))
  }

  it should "refuse a credential that does not resolve" in {
    for key <- List(None, Some("stale")) do
      withClue(key) {
        val refusal = handlers.list(key).unsafeRunSync().left.toOption.get
        refusal._1 shouldBe StatusCode.Unauthorized
        refusal._2.error shouldBe "unauthorized"
      }
  }
