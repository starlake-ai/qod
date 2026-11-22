package ai.starlake.quack.edge.policy

/** The (catalog, schema, table) wildcard coverage rule shared by the RLS rewriter, the column
  * policy rewriter's scoping and the protected-write guard. A policy part of "*" matches anything;
  * otherwise the parts compare as DuckDB binds names, ASCII letters folded and nothing else
  * ([[ai.starlake.sql.IdentifierFold]], owner ruling D21): a policy on `"Clés"` never covers the
  * distinct table `"clÉs"`, whose own policies (a row filter ORed in, a weaker mask) would
  * otherwise apply to it too. Kept in one place so the guard's coverage check and the rewriters'
  * cannot drift.
  */
object PolicyCoverage:

  private val Wildcard = "*"

  def covers(
      policyCatalog: String,
      policySchema: String,
      policyTable: String,
      catalog: String,
      schema: String,
      table: String
  ): Boolean =
    def matchesPart(policyPart: String, actual: String): Boolean =
      policyPart == Wildcard || ai.starlake.sql.IdentifierFold.same(policyPart, actual)
    matchesPart(policyTable, table) &&
    matchesPart(policySchema, schema) &&
    matchesPart(policyCatalog, catalog)
