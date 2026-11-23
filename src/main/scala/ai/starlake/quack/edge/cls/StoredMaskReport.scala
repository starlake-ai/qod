package ai.starlake.quack.edge.cls

import ai.starlake.quack.ondemand.state.{ControlPlaneStore, RbacRole, RoleColumnPolicy}

/** The boot report of stored column masks that a create or an update would now refuse
  * ([[TransformSqlValidator]]), most of all a mask using a form outside the allowlist
  * ([[MaskForms]]), whose error on a row may quote the unmasked value. Such a mask keeps applying
  * as stored; an administrator learns of it here. One line per policy, on stderr (the logger's
  * default level is ERROR), naming the tenant, the role, the policy, the table and the column, and
  * the kinds of the refused parts, never the mask's text.
  */
object StoredMaskReport:

  private def quoted(s: String): String = "'" + s + "'"

  /** The WARN lines, sorted. */
  def lines(roles: List[RbacRole], policies: List[RoleColumnPolicy]): List[String] =
    val byId = roles.map(r => r.id -> r).toMap
    policies
      .filter(_.action == RoleColumnPolicy.ActionMask)
      .flatMap { p =>
        for
          r     <- byId.get(p.roleId)
          mask  <- p.transformSql
          parts <- TransformSqlValidator.refusedParts(mask, p.columnName)
        yield
          val what =
            if parts.nonEmpty then
              s"uses ${parts.mkString("; ")}, which is not an allowed mask form (an error " +
                "evaluating it may quote the unmasked value)"
            else "no longer passes the checks a new mask meets"
          s"WARN: tenant ${quoted(r.tenantId)} role ${quoted(r.name)}: column policy ${p.id} on " +
            s"${p.catalogName}.${p.schemaName}.${p.tableName}.${p.columnName}: its mask $what. " +
            "It keeps applying as stored; update it with the allowed forms (text functions over " +
            "CAST(<column> AS VARCHAR), numbers over TRY_CAST(<column> AS DOUBLE))."
      }
      .sorted

  /** [[lines]] of every tenant's column policies. */
  def linesOf(store: ControlPlaneStore): List[String] =
    val roles = store.listTenants().flatMap(t => store.listRoles(t.id))
    lines(roles, roles.flatMap(r => store.listColumnPolicies(r.id)))

  /** Prints [[linesOf]] on stderr. */
  def report(store: ControlPlaneStore): Unit = linesOf(store).foreach(System.err.println)
