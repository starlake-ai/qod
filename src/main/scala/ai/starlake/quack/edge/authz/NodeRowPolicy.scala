package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.rls.{RowPolicyIdentity, RowPredicateValidator}
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
  // A deliberately unbindable expression: the native binder refuses only matching scans.
  val InvalidPredicate: String = "SELECT"
  private val reported         = com.github.benmanes.caffeine.cache.Caffeine
    .newBuilder()
    .maximumSize(10000L)
    .expireAfterWrite(1, java.util.concurrent.TimeUnit.MINUTES)
    .build[String, java.lang.Boolean]()
  private val log = org.slf4j.LoggerFactory.getLogger("ai.starlake.quack.edge.rls.RowPolicyFailure")
  private def safe(value: String): String = value.map(c => if c.isControl then '?' else c)
  private def prepare(
      predicate: String,
      e: ai.starlake.quack.ondemand.rbac.EffectiveSet,
      id: String,
      catalog: String,
      schema: String,
      table: String
  ): String =
    val result = RowPredicateValidator.validate(predicate) match
      case _: RowPredicateValidator.Invalid => Left("invalid_stored_predicate")
      case _                                =>
        val expanded = RowPolicyIdentity.fill(predicate, e)
        if expanded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536 then
          Left("expanded_predicate_too_large")
        else Right(expanded)
    result.fold(
      reason => {
        val key = e.user.tenant.getOrElse("") + ":" + id + ":" + reason
        if reported.asMap().putIfAbsent(key, java.lang.Boolean.TRUE) == null then
          log.error(
            s"row policy refused: tenant=${safe(e.user.tenant.getOrElse(""))} policy=${safe(id)} " +
              s"table=${safe(catalog)}.${safe(schema)}.${safe(table)} cause=$reason; matching table reads will be denied"
          )
        InvalidPredicate
      },
      identity
    )

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
              prepare(p.predicateSql, e, p.id, p.catalogName, p.schemaName, p.tableName)
            )
          )
      }
