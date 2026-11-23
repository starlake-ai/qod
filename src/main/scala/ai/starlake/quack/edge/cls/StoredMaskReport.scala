package ai.starlake.quack.edge.cls

import ai.starlake.quack.ondemand.state.{ControlPlaneStore, RbacRole, RoleColumnPolicy}
import net.sf.jsqlparser.parser.CCJSqlParserUtil

import scala.util.Try

/** The boot report of stored column masks that a create or an update would now refuse
  * ([[TransformSqlValidator]]), most of all a mask that can raise an error quoting the unmasked
  * value ([[RaisingForms]]). Such a mask keeps applying as stored; an administrator learns of it
  * here. One line per policy, on stderr (the logger's default level is ERROR), naming the tenant,
  * the role, the policy, the table and the column, and the forms found, never the mask's text.
  */
object StoredMaskReport:

  private def quoted(s: String): String = "'" + s + "'"

  private def refused(mask: String, column: String): Boolean =
    TransformSqlValidator.validate(mask, column) match
      case TransformSqlValidator.Invalid(_) => true
      case TransformSqlValidator.Valid(_)   => false

  /** The WARN lines, sorted. */
  def lines(roles: List[RbacRole], policies: List[RoleColumnPolicy]): List[String] =
    val byId = roles.map(r => r.id -> r).toMap
    policies
      .filter(_.action == RoleColumnPolicy.ActionMask)
      .flatMap { p =>
        for
          r    <- byId.get(p.roleId)
          mask <- p.transformSql
          if refused(mask, p.columnName)
        yield
          val forms = Try(CCJSqlParserUtil.parseExpression(mask.trim)).toOption
            .map(RaisingForms.of)
            .getOrElse(Nil)
          val what =
            if forms.nonEmpty then
              s"uses ${forms.mkString(", ")}, which can raise an error quoting the unmasked " +
                "value on a row it cannot convert or compute"
            else "no longer passes the checks a new mask meets"
          s"WARN: tenant ${quoted(r.tenantId)} role ${quoted(r.name)}: column policy ${p.id} on " +
            s"${p.catalogName}.${p.schemaName}.${p.tableName}.${p.columnName}: its mask $what. " +
            "It keeps applying as stored; update it (TRY_CAST for CAST or ::, try_strptime for " +
            "strptime, or the expression wrapped in TRY(...))."
      }
      .sorted

  /** [[lines]] of every tenant's column policies. */
  def linesOf(store: ControlPlaneStore): List[String] =
    val roles = store.listTenants().flatMap(t => store.listRoles(t.id))
    lines(roles, roles.flatMap(r => store.listColumnPolicies(r.id)))

  /** Prints [[linesOf]] on stderr. */
  def report(store: ControlPlaneStore): Unit = linesOf(store).foreach(System.err.println)
