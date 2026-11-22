// src/test/scala/ai/starlake/quack/security/ModuleEndpointsSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.module.TestModule
import ai.starlake.quack.spi.*
import cats.effect.IO
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import scala.compiletime.uninitialized

/** Pins the ManagerServer plumbing added in Task 7: module REST endpoints flow through the same
  * apiKeyGuard as core endpoints (gated unless whitelisted via `modulePublicPrefixes`), module
  * static mounts get an SPA-fallback route exactly like the core `/ui` mount, and duplicate routes
  * between core and module endpoints abort server construction.
  */
class ModuleEndpointsSpec
    extends AnyFlatSpec
    with Matchers
    with SecurityHttpHelpers
    with BeforeAndAfterAll:

  private val module = new TestModule

  private var harness: ManagerServerHarness.Harness = uninitialized

  override def beforeAll(): Unit =
    val fix = SecurityFixtures.freshStore()
    harness = ManagerServerHarness.boot(
      fix.store,
      moduleEndpoints = module.endpoints,
      modulePublicPrefixes = module.publicPathPrefixes,
      moduleStaticMounts = List(StaticMount("/portal", "/portal-test"))
    )

  override def afterAll(): Unit =
    if harness != null then harness.shutdown()

  "module endpoints" should "serve a public-prefix route without any credential" in {
    val resp = get(harness.httpClient, s"${harness.baseUrl}/api/hosted-test/ping")
    withClue(s"GET /api/hosted-test/ping body: ${resp.body()}") {
      resp.statusCode() shouldBe 200
    }
    resp.body() shouldBe "pong"
  }

  it should "gate non-public module routes behind the guard" in {
    val denied = get(harness.httpClient, s"${harness.baseUrl}/api/hosted-test/secret")
    withClue(s"GET /api/hosted-test/secret (no credential) body: ${denied.body()}") {
      denied.statusCode() shouldBe 401
    }

    val admitted = get(
      harness.httpClient,
      s"${harness.baseUrl}/api/hosted-test/secret",
      apiKey = Some(harness.superuserSession())
    )
    withClue(s"GET /api/hosted-test/secret (superuser session) body: ${admitted.body()}") {
      admitted.statusCode() shouldBe 200
    }
    admitted.body() shouldBe "classified"
  }

  "module static mounts" should "serve assets and SPA-fallback to index.html" in {
    val root = get(harness.httpClient, s"${harness.baseUrl}/portal/")
    withClue(s"GET /portal/ body: ${root.body()}") {
      root.statusCode() shouldBe 200
    }
    root.body() should include("portal-index")

    val deep = get(harness.httpClient, s"${harness.baseUrl}/portal/deep/route")
    withClue(s"GET /portal/deep/route body: ${deep.body()}") {
      deep.statusCode() shouldBe 200
    }
    deep.body() should include("portal-index")
  }

  "a public prefix" should "match on segment boundaries, not raw string prefixes" in {
    // Declared public prefix is "/api/hosted-test/ping"; the endpoint below is
    // "/api/hosted-test/pingx", which shares the raw string prefix but is NOT a
    // sub-path of it. Segment-safe matching must keep pingx gated behind the guard.
    val localModule = new ManagerModule:
      def name: String                             = "prefix-boundary-module"
      def changelogPath: Option[String]            = None
      def start(ctx: ManagerContext): IO[Unit]     = IO.unit
      def endpoints: List[ServerEndpoint[Any, IO]] = List(
        endpoint.get
          .in("api" / "hosted-test" / "pingx")
          .out(stringBody)
          .serverLogicSuccess[IO](_ => IO.pure("pongx"))
      )
      def publicPathPrefixes: Set[String]        = Set("/api/hosted-test/ping")
      def onEvent(event: ManagerEvent): IO[Unit] = IO.unit
      def stop: IO[Unit]                         = IO.unit

    val fix   = SecurityFixtures.freshStore()
    val local = ManagerServerHarness.boot(
      fix.store,
      moduleEndpoints = localModule.endpoints,
      modulePublicPrefixes = localModule.publicPathPrefixes
    )
    try
      val denied = get(local.httpClient, s"${local.baseUrl}/api/hosted-test/pingx")
      withClue(s"GET /api/hosted-test/pingx (no credential) body: ${denied.body()}") {
        denied.statusCode() shouldBe 401
      }
    finally local.shutdown()
  }

  it should "keep an encoded slash inside a segment from carrying a request past it" in {
    // A `%2F` decodes to `/` in the joined path, so `ping%2Fx` once read as `ping/x`, under the
    // public prefix, while the router reads one segment `ping/x` and serves the route below with
    // `id = "ping/x"`. Whole segments keep that route behind the guard.
    val localModule = new ManagerModule:
      def name: String                             = "encoded-slash-module"
      def changelogPath: Option[String]            = None
      def start(ctx: ManagerContext): IO[Unit]     = IO.unit
      def endpoints: List[ServerEndpoint[Any, IO]] = List(
        endpoint.get
          .in("api" / "hosted-test" / "ping")
          .out(stringBody)
          .serverLogicSuccess[IO](_ => IO.pure("pong")),
        endpoint.get
          .in("api" / "hosted-test" / "ping" / "sub")
          .out(stringBody)
          .serverLogicSuccess[IO](_ => IO.pure("pong-sub")),
        endpoint.get
          .in("api" / "hosted-test" / path[String]("id") / "secret")
          .out(stringBody)
          .serverLogicSuccess[IO](id => IO.pure(s"classified:$id"))
      )
      def publicPathPrefixes: Set[String]        = Set("/api/hosted-test/ping")
      def onEvent(event: ManagerEvent): IO[Unit] = IO.unit
      def stop: IO[Unit]                         = IO.unit

    val fix   = SecurityFixtures.freshStore()
    val local = ManagerServerHarness.boot(
      fix.store,
      moduleEndpoints = localModule.endpoints,
      modulePublicPrefixes = localModule.publicPathPrefixes
    )
    try
      val smuggled = get(local.httpClient, s"${local.baseUrl}/api/hosted-test/ping%2Fx/secret")
      withClue(s"GET /api/hosted-test/ping%2Fx/secret (no credential) body: ${smuggled.body()}") {
        smuggled.statusCode() shouldBe 401
      }
      smuggled.body() should not include "classified"
      // Lowercase escape too: the router decodes either.
      val lower = get(local.httpClient, s"${local.baseUrl}/api/hosted-test/ping%2fx/secret")
      lower.statusCode() shouldBe 401

      // The route itself still answers an admitted caller, so the 401 above is the guard's.
      val admitted = get(
        local.httpClient,
        s"${local.baseUrl}/api/hosted-test/ping%2Fx/secret",
        apiKey = Some(local.superuserSession())
      )
      withClue(s"GET /api/hosted-test/ping%2Fx/secret (superuser) body: ${admitted.body()}") {
        admitted.statusCode() shouldBe 200
      }
      admitted.body() shouldBe "classified:ping/x"

      // Controls: the prefix itself and a path under it stay public.
      val at = get(local.httpClient, s"${local.baseUrl}/api/hosted-test/ping")
      at.statusCode() shouldBe 200
      at.body() shouldBe "pong"
      val under = get(local.httpClient, s"${local.baseUrl}/api/hosted-test/ping/sub")
      under.statusCode() shouldBe 200
      under.body() shouldBe "pong-sub"
    finally local.shutdown()
  }

  it should "keep an encoded slash from carrying a request onto a prefix, and leave one past it public" in {
    val localModule = new ManagerModule:
      def name: String                             = "encoded-slash-tail-module"
      def changelogPath: Option[String]            = None
      def start(ctx: ManagerContext): IO[Unit]     = IO.unit
      def endpoints: List[ServerEndpoint[Any, IO]] = List(
        endpoint.get
          .in("api" / "hosted-test" / "ping" / path[String]("tail"))
          .out(stringBody)
          .serverLogicSuccess[IO](tail => IO.pure(s"pong:$tail"))
      )
      def publicPathPrefixes: Set[String]        = Set("/api/hosted-test/ping")
      def onEvent(event: ManagerEvent): IO[Unit] = IO.unit
      def stop: IO[Unit]                         = IO.unit

    val fix   = SecurityFixtures.freshStore()
    val local = ManagerServerHarness.boot(
      fix.store,
      moduleEndpoints = localModule.endpoints,
      modulePublicPrefixes = localModule.publicPathPrefixes
    )
    try
      // `api%2Fhosted-test` is one segment `api/hosted-test`: the joined path reads as under the
      // prefix, the segments do not, so the guard answers it.
      val head = get(local.httpClient, s"${local.baseUrl}/api%2Fhosted-test/ping/x")
      withClue(s"GET /api%2Fhosted-test/ping/x (no credential) body: ${head.body()}") {
        head.statusCode() shouldBe 401
        head.body() shouldBe ""
      }
      // Past the prefix, an encoded slash is the route's business: one segment `a/b` under it.
      val tail = get(local.httpClient, s"${local.baseUrl}/api/hosted-test/ping/a%2Fb")
      withClue(s"GET /api/hosted-test/ping/a%2Fb (no credential) body: ${tail.body()}") {
        tail.statusCode() shouldBe 200
      }
      tail.body() shouldBe "pong:a/b"
    finally local.shutdown()
  }

  "an exact core public path" should "not open a module route that captures an encoded slash" in {
    // A module route shaped `/api/{x}` captures `auth%2Flogin` as `x = "auth/login"`. Matched on
    // the joined path, the guard once read that request as the public `/api/auth/login` and let
    // the route answer it with no credential.
    val localModule = new ManagerModule:
      def name: String                             = "capture-module"
      def changelogPath: Option[String]            = None
      def start(ctx: ManagerContext): IO[Unit]     = IO.unit
      def endpoints: List[ServerEndpoint[Any, IO]] = List(
        endpoint.post
          .in("api" / path[String]("x"))
          .out(stringBody)
          .serverLogicSuccess[IO](x => IO.pure(s"module:$x")),
        endpoint.get
          .in("api" / path[String]("x"))
          .out(stringBody)
          .serverLogicSuccess[IO](x => IO.pure(s"module-get:$x"))
      )
      def publicPathPrefixes: Set[String]        = Set.empty
      def onEvent(event: ManagerEvent): IO[Unit] = IO.unit
      def stop: IO[Unit]                         = IO.unit

    val fix   = SecurityFixtures.freshStore()
    val local = ManagerServerHarness.boot(fix.store, moduleEndpoints = localModule.endpoints)
    try
      val login = post(local.httpClient, s"${local.baseUrl}/api/auth%2Flogin", "{}")
      withClue(s"POST /api/auth%2Flogin (no credential) body: ${login.body()}") {
        login.statusCode() shouldBe 401
      }
      login.body() should not include "module"
      val client = get(local.httpClient, s"${local.baseUrl}/api/config%2Fclient")
      withClue(s"GET /api/config%2Fclient (no credential) body: ${client.body()}") {
        client.statusCode() shouldBe 401
      }
      client.body() should not include "module"

      // The routes answer an admitted caller, so the 401s above are the guard's.
      val admitted = post(
        local.httpClient,
        s"${local.baseUrl}/api/auth%2Flogin",
        "{}",
        apiKey = Some(local.superuserSession())
      )
      withClue(s"POST /api/auth%2Flogin (superuser) body: ${admitted.body()}") {
        admitted.statusCode() shouldBe 200
      }
      admitted.body() shouldBe "module:auth/login"
    finally local.shutdown()
  }

  "route collisions" should "fail server construction" in {
    val fix = SecurityFixtures.freshStore()
    val ex  = intercept[IllegalStateException] {
      ManagerServerHarness.boot(
        fix.store,
        moduleEndpoints = module.endpoints ++ module.endpoints
      )
    }
    ex.getMessage should include("duplicate REST routes")
  }
