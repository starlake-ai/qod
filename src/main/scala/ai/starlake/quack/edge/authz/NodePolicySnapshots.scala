package ai.starlake.quack.edge.authz

import com.github.benmanes.caffeine.cache.Caffeine
import io.circe.Json
import java.nio.charset.StandardCharsets

/** Immutable, prepared policy transport reused for the same authorization context and version.
  * Retains every effective policy: a manager's guessed catalog inventory must not drop a policy a
  * view, macro or federated source can reach on the node.
  */
final case class NodePolicySnapshot(rows: List[NodeRowPolicy], columns: List[NodeColumnPolicy]):
  require(rows.forall(NodeRowPolicy.valid), "invalid node row policies")
  require(columns.forall(NodeColumnPolicy.valid), "invalid node column policies")
  val rowJson: Json    = Json.arr(rows.map(summon[io.circe.Encoder[NodeRowPolicy]].apply)*)
  val columnJson: Json = Json.arr(columns.map(summon[io.circe.Encoder[NodeColumnPolicy]].apply)*)
  private[authz] lazy val entries: Vector[Json] =
    rows
      .map(p =>
        summon[io.circe.Encoder[NodeRowPolicy]]
          .apply(p)
          .deepMerge(Json.obj("kind" -> Json.fromString("row")))
      )
      .toVector ++
      columns
        .map(p =>
          summon[io.circe.Encoder[NodeColumnPolicy]]
            .apply(p)
            .deepMerge(Json.obj("kind" -> Json.fromString("column")))
        )
        .toVector
  private[authz] lazy val encoded: Array[Byte] =
    CanonicalJson.render(Json.arr(entries*)).getBytes(StandardCharsets.UTF_8)
  private[authz] lazy val digest: String =
    NodeHandles.hex(java.security.MessageDigest.getInstance("SHA-256").digest(encoded))
  private[authz] lazy val weight: Int = 256 + encoded.length * 2

object NodePolicySnapshot:
  val Empty: NodePolicySnapshot = NodePolicySnapshot(Nil, Nil)

final class NodePolicySnapshots(
    compile: (
        ai.starlake.quack.ondemand.rbac.EffectiveSet,
        Boolean,
        Boolean
    ) => NodePolicySnapshot = (e, rows, columns) =>
      NodePolicySnapshot(
        if rows then NodeRowPolicy.ofEffective(e) else Nil,
        if columns then NodeColumnPolicy.ofEffective(e) else Nil
      )
):
  private final case class Key(
      context: String,
      version: Long,
      rows: Boolean,
      columns: Boolean,
      identity: String
  )
  private val cache = Caffeine
    .newBuilder()
    .maximumWeight(32L * 1024 * 1024)
    .weigher((key: Key, value: NodePolicySnapshot) => value.weight)
    .build[Key, NodePolicySnapshot]()

  def of(
      principal: NodePrincipal,
      context: String,
      version: Long,
      rows: Boolean,
      columns: Boolean
  ): NodePolicySnapshot =
    if principal.superuser || principal.system || principal.context.isEmpty then
      NodePolicySnapshot.Empty
    else
      val e = NodePrincipal.policyEffectiveSet(principal).get
      // Context digests contain identity ids. Predicate substitution also uses ordered names.
      val identity = Json
        .obj(
          "user"   -> Json.fromString(e.user.username),
          "tenant" -> Json.fromString(e.user.tenant.getOrElse("")),
          "roles"  -> Json.arr(e.roles.map(r => Json.fromString(r.name))*),
          "groups" -> Json.arr(e.groups.map(g => Json.fromString(g.name))*)
        )
        .noSpaces
      cache.get(
        Key(context, version, rows, columns, identity),
        _ => compile(e, rows, columns)
      )
