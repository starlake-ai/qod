package ai.starlake.quack.edge.rls

import ai.starlake.quack.ondemand.rbac.EffectiveSet

/** Identity literals for predicates signed into node handles; never a query rewriter. */
object RowPolicyIdentity:
  private val Tokens             = "\\$\\{[^}]*\\}".r
  val AllowedTokens: Set[String] = Set("user", "tenant", "tenantId", "roles", "groups")
  def hasUnknownToken(predicate: String): Boolean =
    Tokens.findAllMatchIn(predicate).exists(m => !AllowedTokens(m.matched.drop(2).dropRight(1)))

  /** Tokens are complete SQL values, never fragments of strings, identifiers or E'...' prefixes. */
  def safeTokenLocations(predicate: String): Boolean =
    val tokens                    = ai.starlake.sql.SqlLexer.tokens(predicate)
    def boundary(i: Int): Boolean = i < 0 || i >= predicate.length ||
      predicate(i).isWhitespace || "(),=<>!+-*/%|:[]".contains(predicate(i))
    Tokens.findAllMatchIn(predicate).forall { m =>
      boundary(m.start - 1) && boundary(m.end) &&
      !tokens.exists(t =>
        (t.kind == ai.starlake.sql.SqlLexer.Kind.Literal || t.isTrivia) &&
          t.start <= m.start && m.start < t.end
      )
    }

  private def literal(value: String): String         = "'" + value.replace("'", "''") + "'"
  private def literals(values: List[String]): String =
    if values.isEmpty then "NULL" else values.map(literal).mkString(", ")

  def fill(predicate: String, eff: EffectiveSet): String =
    require(safeTokenLocations(predicate), "identity tokens must be complete SQL values")
    val tenant = eff.user.tenant.getOrElse("")
    val values = Map(
      "user"     -> literal(eff.user.username),
      "tenant"   -> literal(tenant),
      "tenantId" -> literal(tenant),
      "roles"    -> literals(eff.roles.map(_.name)),
      "groups"   -> literals(eff.groups.map(_.name))
    )
    Tokens.replaceAllIn(
      predicate,
      m =>
        val token = m.matched.drop(2).dropRight(1)
        require(values.contains(token), "row policy contains an unknown identity token")
        java.util.regex.Matcher.quoteReplacement(values(token))
    )

  /** Diagnostics do not run as a principal and need no identity values. */
  private[rls] def neutralize(predicate: String): String = Tokens.replaceAllIn(predicate, "NULL")
