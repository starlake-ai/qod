package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.rls.RowPolicyIdentity
import io.circe.{Decoder, Encoder}

/** A predicate, identity-filled by the manager, carried inside the signed session handle. */
final case class NodeRowPolicy(
    id: String,
    catalog: String,
    schema: String,
    table: String,
    sql: String
)

object NodeRowPolicy:
  given Encoder[NodeRowPolicy] = Encoder.forProduct5("id", "catalog", "schema", "table", "sql")(p =>
    (p.id, p.catalog, p.schema, p.table, p.sql)
  )
  given Decoder[NodeRowPolicy] =
    Decoder.forProduct5("id", "catalog", "schema", "table", "sql")(NodeRowPolicy.apply)

  def valid(p: NodeRowPolicy): Boolean =
    List(p.id, p.catalog, p.schema, p.table, p.sql).forall(_.nonEmpty) &&
      p.sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65536

  def of(principal: NodePrincipal): List[NodeRowPolicy] =
    if principal.superuser || principal.system then Nil
    else
      principal.context.toList.flatMap { c =>
        val e = ai.starlake.quack.ondemand.rbac.Attenuation.attenuatedBy(
          c.effective,
          c.restriction.getOrElse(ai.starlake.quack.ondemand.auth.TokenRestriction.Unrestricted)
        )
        e.rowPolicies
          .sortBy(_.id)
          .map(p =>
            NodeRowPolicy(
              p.id,
              p.catalogName,
              p.schemaName,
              p.tableName,
              RowPolicyIdentity.fill(p.predicateSql, e)
            )
          )
      }
