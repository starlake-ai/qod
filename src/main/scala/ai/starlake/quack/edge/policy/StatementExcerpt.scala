package ai.starlake.quack.edge.policy

import ai.starlake.quack.ondemand.rbac.EffectiveSet

/** DuckDB's excerpt of a failed statement, withheld from principals with column or row policies.
  *
  * DuckDB appends to a parse, bind or execution error the line of the statement around the error
  * position (`QueryErrorContext::Format`): a blank line, `LINE <n>: ` and up to 60 columns of the
  * statement on each side of the position, then a line of blanks and carets under it. The statement
  * a node runs for a principal holding column or row policies is the gateway's rewrite, which holds
  * the policies' mask expressions and row predicates, so the excerpt would show them (owner ruling
  * D30). Every node error the manager answers such a principal, on every door, goes through
  * [[shownTo]] first: the client's answer, the statement history and the audit detail read the text
  * without the excerpt. Principals without policies, superusers and the system principal keep it.
  */
object StatementExcerpt:

  /** Whether node errors answered for `effectiveSet`'s principal lose their excerpt: a principal
    * that is neither the system principal nor a superuser and holds at least one column or row
    * policy, whatever table it names.
    */
  def withheldFor(effectiveSet: Option[EffectiveSet]): Boolean =
    effectiveSet.exists(e =>
      !e.system && !e.user.isSuperuser && (e.columnPolicies.nonEmpty || e.rowPolicies.nonEmpty)
    )

  /** `message` without any statement excerpt: from the blanks before each `LINE <n>:` (at the start
    * of the message or after a blank, as `NodeHandles.refusalText` matches it) to the end of that
    * line, and the caret line after it when there is one. Text before and after is kept as is.
    */
  def strip(message: String): String =
    Excerpt.replaceAllIn(message, "")

  /** `message` as a principal is shown it: [[strip]]ped when `withheld`, untouched otherwise. */
  def shownTo(withheld: Boolean, message: String): String =
    if withheld then strip(message) else message

  private val Excerpt = """\s*(?<!\S)LINE \d+:[^\n]*(?:\n[ \t]*\^+[^\n]*)?""".r
