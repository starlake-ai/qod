package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.cls.TransformSqlValidator
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

  // Reused across contexts as well as repeated handles. The protected column is part of the
  // key: identical SQL on a different column is a different containment check.
  private val masks = com.github.benmanes.caffeine.cache.Caffeine
    .newBuilder()
    .maximumWeight(8L * 1024 * 1024)
    .weigher((key: (String, String), value: Option[String]) =>
      128 + key._1.length * 2 + key._2.length * 2 + value.fold(0)(_.length * 2)
    )
    .build[(String, String), Option[String]]()

  private def validated(sql: String, column: String): Option[String] =
    masks.get(
      (sql, column),
      key =>
        TransformSqlValidator.validate(key._1, key._2) match
          case TransformSqlValidator.Valid(canonical) => Some(canonical)
          case _                                      => None
    )

  def of(principal: NodePrincipal): List[NodeColumnPolicy] =
    NodePrincipal.policyEffectiveSet(principal).toList.flatMap(ofEffective)

  private[authz] def ofEffective(
      e: ai.starlake.quack.ondemand.rbac.EffectiveSet
  ): List[NodeColumnPolicy] =
    e.columnPolicies.sortBy(_.id).map { p =>
      val sql = Option
        .when(p.action == "mask")(p.transformSql)
        .flatten
        .flatMap(raw => validated(raw, p.columnName))
      // An unusable persisted mask restricts its own source column. Never omit it or turn
      // it into an exception that blocks SELECT 1 and unrelated databases for this user.
      // New definitions, including manifest imports, are validated before persistence.
      NodeColumnPolicy(
        p.id,
        p.catalogName,
        p.schemaName,
        p.tableName,
        p.columnName,
        if sql.isDefined then "mask" else "deny",
        sql.getOrElse("")
      )
    }
