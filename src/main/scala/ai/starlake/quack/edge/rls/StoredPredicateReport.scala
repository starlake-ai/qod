package ai.starlake.quack.edge.rls

import ai.starlake.quack.ondemand.state.{ControlPlaneStore, RbacRole, RoleRowPolicy}

/** The boot report of stored row predicates that use a form DuckDB can answer with an error quoting
  * a value of the row ([[PredicateForms]]), which a create or an update now refuses: the predicate
  * runs on every row DuckDB reads, the rows it hides included. Such a predicate keeps applying as
  * stored; an administrator learns of it here. One line per policy, on stderr (the logger's default
  * level is ERROR), naming the tenant, the role, the policy, the table and the kinds of the refused
  * parts, never the predicate's text.
  */
object StoredPredicateReport:

  private def quoted(s: String): String = "'" + s + "'"

  /** The WARN lines, sorted. */
  def lines(roles: List[RbacRole], policies: List[RoleRowPolicy]): List[String] =
    val byId = roles.map(r => r.id -> r).toMap
    policies.flatMap { p =>
      for
        r     <- byId.get(p.roleId)
        parts <- RowPredicateValidator.raisingParts(p.predicateSql)
      yield s"WARN: tenant ${quoted(r.tenantId)} role ${quoted(r.name)}: row policy ${p.id} on " +
        s"${p.catalogName}.${p.schemaName}.${p.tableName}: its predicate uses " +
        s"${parts.mkString("; ")}, which can raise an error quoting a value of a row it hides. " +
        s"It keeps applying as stored; update it: ${PredicateForms.NonRaising}."
    }.sorted

  /** [[lines]] of every tenant's row policies. */
  def linesOf(store: ControlPlaneStore): List[String] =
    val roles = store.listTenants().flatMap(t => store.listRoles(t.id))
    lines(roles, roles.flatMap(r => store.listRowPolicies(r.id)))

  /** Prints [[linesOf]] on stderr. */
  def report(store: ControlPlaneStore): Unit = linesOf(store).foreach(System.err.println)
