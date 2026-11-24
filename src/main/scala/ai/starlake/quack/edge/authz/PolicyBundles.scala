package ai.starlake.quack.edge.authz

import com.github.benmanes.caffeine.cache.Caffeine
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit

/** Content-addressed row and column policy snapshots. Handles carry only their digest. */
final class PolicyBundles:
  private val issued = Caffeine
    .newBuilder()
    .maximumSize(200000L)
    .expireAfterWrite(6, TimeUnit.MINUTES)
    .build[String, String]()
  private def digest(text: String): String =
    NodeHandles.hex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)))
  def authorize(handle: MintedHandle): Unit      = issued.put(handle.nonce, digest(handle.text))
  def owns(nonce: String, text: String): Boolean =
    Option(issued.getIfPresent(nonce)).contains(digest(text))

  private val cache = Caffeine
    .newBuilder()
    .maximumWeight(128L * 1024 * 1024)
    .weigher((_: String, b: NodePolicySnapshot) => b.weight)
    .expireAfterAccess(6, TimeUnit.MINUTES)
    .build[String, NodePolicySnapshot]()

  def put(snapshot: NodePolicySnapshot): String =
    if snapshot.entries.isEmpty then ""
    else
      require(snapshot.encoded.length <= 32 * 1024 * 1024, "policy snapshot exceeds capacity")
      cache.put(snapshot.digest, snapshot)
      snapshot.digest

  def page(id: String, offset: Int): Option[Json] =
    Option(cache.getIfPresent(id)).filter(b => offset >= 0 && offset < b.entries.size).flatMap {
      b =>
        var end  = offset
        var size = 0
        while end < b.entries.size && end - offset < 64 &&
          size + b.entries(end).noSpaces.getBytes(UTF_8).length <= 512 * 1024
        do
          size += b.entries(end).noSpaces.getBytes(UTF_8).length
          end += 1
        Option.when(end > offset)(
          Json.obj(
            "v"        -> Json.fromInt(1),
            "bundle"   -> Json.fromString(id),
            "offset"   -> Json.fromInt(offset),
            "next"     -> Json.fromInt(if end == b.entries.size then -1 else end),
            "policies" -> Json.arr(b.entries.slice(offset, end)*)
          )
        )
    }
