package ai.starlake.quack.edge.authz

import com.github.benmanes.caffeine.cache.Caffeine
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit

/** Immutable, content-addressed policy snapshots. Handles carry only their digest. */
final class RowPolicyBundles:
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
    .weigher((_: String, b: RowPolicyBundles.Bundle) => b.bytes)
    .expireAfterAccess(6, TimeUnit.MINUTES)
    .build[String, RowPolicyBundles.Bundle]()

  private val contexts = Caffeine
    .newBuilder()
    .maximumSize(10000L)
    .expireAfterAccess(6, TimeUnit.MINUTES)
    .build[String, String]()

  /** Include every substitution input, including ordered names absent from the context's id lists.
    */
  def of(principal: NodePrincipal, ctx: String, version: Long): String =
    if principal.superuser || principal.system then ""
    else
      principal.context.filter(_.effective.rowPolicies.nonEmpty).fold("") { context =>
        val e        = context.effective
        val identity = Json.obj(
          "ctx"     -> Json.fromString(ctx),
          "version" -> Json.fromLong(version),
          "user"    -> Json.fromString(e.user.username),
          "tenant"  -> Json.fromString(e.user.tenant.getOrElse("")),
          "roles"   -> Json.arr(e.roles.map(r => Json.fromString(r.name))*),
          "groups"  -> Json.arr(e.groups.map(g => Json.fromString(g.name))*)
        )
        val key      = digest(CanonicalJson.render(identity))
        val existing = contexts.getIfPresent(key)
        if existing != null && cache.getIfPresent(existing) != null then existing
        else
          val id = put(NodeRowPolicy.of(principal))
          if id.nonEmpty then contexts.put(key, id)
          id
      }

  def put(rows: List[NodeRowPolicy]): String =
    if rows.isEmpty then ""
    else
      val json  = rows.map(summon[io.circe.Encoder[NodeRowPolicy]].apply).toVector
      val bytes = CanonicalJson.render(Json.arr(json*)).getBytes(UTF_8)
      val id    = NodeHandles.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
      cache.put(id, RowPolicyBundles.Bundle(json, bytes.length))
      id

  def page(id: String, offset: Int): Option[Json] =
    Option(cache.getIfPresent(id)).filter(b => offset >= 0 && offset < b.rows.size).flatMap { b =>
      var end  = offset
      var size = 0
      while end < b.rows.size && end - offset < 64 &&
        size + b.rows(end).noSpaces.getBytes(UTF_8).length <= 512 * 1024
      do
        size += b.rows(end).noSpaces.getBytes(UTF_8).length
        end += 1
      Option.when(end > offset)(
        Json.obj(
          "v"      -> Json.fromInt(1),
          "bundle" -> Json.fromString(id),
          "offset" -> Json.fromInt(offset),
          "next"   -> Json.fromInt(if end == b.rows.size then -1 else end),
          "rows"   -> Json.arr(b.rows.slice(offset, end)*)
        )
      )
    }

object RowPolicyBundles:
  private final case class Bundle(rows: Vector[Json], bytes: Int)
