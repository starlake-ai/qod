package ai.starlake.quack.edge.authz

import cats.effect.IO
import io.circe.parser.parse
import org.http4s.{HttpRoutes, Status}
import org.http4s.dsl.io.*
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.security.MessageDigest
import java.util.Base64

/** A node may fetch only the immutable bundle vouched for by its signed handle. */
final class PolicyRoute(keys: NodeKeys, clock: () => Long = () => System.currentTimeMillis()):
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "internal" / "authz" / "policies" =>
      NodeCalls.serve(req, 64 * 1024)((_, _) => (Status.Unauthorized, "policy call refused"))(
        answer
      )
  }

  private def answer(node: String, signature: String, body: Array[Byte]): (Status, String) =
    val key      = keys.key(node)
    val expected = NodeHandles.hex(NodeHandles.hmac(key, body))
    if !MessageDigest.isEqual(expected.getBytes(US_ASCII), signature.getBytes(US_ASCII)) then
      (Status.Unauthorized, "policy call refused")
    else
      val request = for
        json   <- parse(new String(body, UTF_8)).left.map(_ => "malformed request")
        text   <- json.hcursor.get[String]("handle").left.map(_ => "missing handle")
        offset <- json.hcursor.get[Int]("offset").left.map(_ => "missing offset")
        handle <- NodeHandles.verify(text, key, node, clock())
      yield (handle, offset, text)
      request match
        case Left(_) => (Status.Unauthorized, "policy call refused")
        case Right((h, offset, text)) if keys.policyBundles.owns(h.nonce, text) =>
          keys.policyBundles.page(h.policyBundle, offset) match
            case None       => (Status.ServiceUnavailable, "policy snapshot unavailable; retry")
            case Some(page) =>
              val bytes = CanonicalJson.render(page).getBytes(UTF_8)
              (
                Status.Ok,
                Base64.getUrlEncoder.withoutPadding.encodeToString(bytes) + "." +
                  NodeHandles.hex(NodeHandles.hmac(key, bytes))
              )
        case Right(_) => (Status.Unauthorized, "policy handle was not minted by this replica")
