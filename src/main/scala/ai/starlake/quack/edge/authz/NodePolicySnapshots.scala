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
  private[authz] val weight: Int = 256 +
    rowJson.noSpaces.getBytes(StandardCharsets.UTF_8).length * 2 +
    columnJson.noSpaces.getBytes(StandardCharsets.UTF_8).length * 2

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
  private final case class Key(context: String, version: Long, rows: Boolean, columns: Boolean)
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
      cache.get(
        Key(context, version, rows, columns),
        _ => compile(NodePrincipal.policyEffectiveSet(principal).get, rows, columns)
      )
