package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.cls.TransformSqlValidator
import ai.starlake.quack.ondemand.rbac.Attenuation
import ai.starlake.quack.ondemand.auth.TokenRestriction
import io.circe.{Decoder, Encoder}

/** A validated column policy carried in the signed handle, never in caller SQL. */
final case class NodeColumnPolicy(
    id: String,
    catalog: String,
    schema: String,
    table: String,
    column: String,
    action: String,
    sql: String
)

object NodeColumnPolicy:
  given Encoder[NodeColumnPolicy] =
    Encoder.forProduct7("id", "catalog", "schema", "table", "column", "action", "sql")(p =>
      (p.id, p.catalog, p.schema, p.table, p.column, p.action, p.sql)
    )
  given Decoder[NodeColumnPolicy] =
    Decoder.forProduct7("id", "catalog", "schema", "table", "column", "action", "sql")(
      NodeColumnPolicy.apply
    )

  def valid(p: NodeColumnPolicy): Boolean =
    List(p.id, p.catalog, p.schema, p.table, p.column).forall(_.nonEmpty) &&
      p.sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096 &&
      ((p.action == "deny" && p.sql.isEmpty) || (p.action == "mask" && p.sql.nonEmpty))

  def of(principal: NodePrincipal): List[NodeColumnPolicy] =
    if principal.superuser || principal.system then Nil
    else
      principal.context.toList.flatMap { context =>
        val effective = Attenuation.attenuatedBy(
          context.effective,
          context.restriction.getOrElse(TokenRestriction.Unrestricted)
        )
        effective.columnPolicies.sortBy(_.id).map { p =>
          val sql = p.action match
            case "deny" => ""
            case "mask" =>
              TransformSqlValidator.validate(p.transformSql.getOrElse(""), p.columnName) match
                case TransformSqlValidator.Valid(canonical) => canonical
                case _                                      =>
                  throw new IllegalArgumentException(
                    s"column policy ${p.id} cannot be used by native CLS; update it with a valid mask"
                  )
            case _ =>
              throw new IllegalArgumentException(s"column policy ${p.id} has an invalid action")
          NodeColumnPolicy(
            p.id,
            p.catalogName,
            p.schemaName,
            p.tableName,
            p.columnName,
            p.action,
            sql
          )
        }
      }
