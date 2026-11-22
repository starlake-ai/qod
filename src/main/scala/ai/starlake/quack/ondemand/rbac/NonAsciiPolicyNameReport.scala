package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  RbacRole,
  RoleColumnPolicy,
  RolePermission,
  RoleRowPolicy
}

/** The boot report of stored grants, column policies and row policies whose names hold a non-ASCII
  * letter that has another case (owner ruling D21). Grants and policies now match their tables and
  * columns as DuckDB binds names, ASCII letters folded and nothing else
  * (`ai.starlake.sql.IdentifierFold`), so such a row covers only an object spelled with exactly
  * these non-ASCII letters: one spelled with another case of one of them (`"CLÉS"` for the table
  * `"Clés"`) stopped matching, and an operator checks each. One line per row, on stderr (the
  * logger's default level is ERROR), naming the tenant, the role and the object, never a mask's
  * expression or a row policy's predicate.
  */
object NonAsciiPolicyNameReport:

  /** Whether `name` holds a letter outside ASCII whose case can change (`É`, `é`, the Kelvin sign,
    * the long s): the names whose matching the ASCII-only fold changed.
    */
  def holdsCasedNonAscii(name: String): Boolean =
    Option(name).exists(
      _.codePoints().anyMatch(cp =>
        cp >= 0x80 && (Character.toUpperCase(cp) != cp || Character.toLowerCase(cp) != cp)
      )
    )

  /** The WARN line of every grant, column policy and row policy so named, sorted. */
  def lines(
      roles: List[RbacRole],
      grants: List[RolePermission],
      columnPolicies: List[RoleColumnPolicy],
      rowPolicies: List[RoleRowPolicy]
  ): List[String] =
    val byId = roles.map(r => r.id -> r).toMap
    def line(roleId: String, what: String, parts: List[String]): Option[String] =
      Option.when(parts.exists(holdsCasedNonAscii))(byId.get(roleId)).flatten.map { r =>
        s"WARN: tenant '${r.tenantId}' role '${r.name}': the $what on ${parts.mkString(".")} " +
          "names a non-ASCII letter that has another case. Names now match as DuckDB binds them, " +
          "folding ASCII letters only, so it covers only an object spelled with exactly these " +
          "letters: check that it names the object as stored, and respell it if not."
      }
    (grants.flatMap(g => line(g.roleId, "grant", List(g.catalogName, g.schemaName, g.tableName))) ++
      columnPolicies.flatMap(p =>
        line(
          p.roleId,
          "column policy",
          List(p.catalogName, p.schemaName, p.tableName, p.columnName)
        )
      ) ++
      rowPolicies.flatMap(p =>
        line(p.roleId, "row policy", List(p.catalogName, p.schemaName, p.tableName))
      )).sorted

  /** [[lines]] of every tenant's roles in `store`. */
  def linesOf(store: ControlPlaneStore): List[String] =
    val roles = store.listTenants().flatMap(t => store.listRoles(t.id))
    val ids   = roles.map(_.id).toSet
    lines(
      roles,
      store.listRolePermissionsForRoles(ids),
      store.listAllColumnPolicies().filter(p => ids.contains(p.roleId)),
      roles.flatMap(r => store.listRowPolicies(r.id))
    )

  /** Prints [[linesOf]] `store` on stderr. */
  def report(store: ControlPlaneStore): Unit = linesOf(store).foreach(System.err.println)
