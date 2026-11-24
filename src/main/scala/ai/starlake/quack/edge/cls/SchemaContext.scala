package ai.starlake.quack.edge.cls

/** Session defaults and pool identity used by metadata and protected-write checks. */
final case class SchemaContext(
    defaultDatabase: Option[String],
    defaultSchema: Option[String],
    attachedCatalogs: Set[String],
    pool: Option[ai.starlake.quack.model.PoolKey] = None
)
