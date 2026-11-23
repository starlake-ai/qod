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
    * line, and the caret line after it when there is one. A JSON error (`errors_as_json`, which
    * DuckDB writes as `{"exception_type":...,"position":"<n>","location":"[<n>,<m>]",...}`) also
    * loses its `position` and `location` members, offsets into the statement the node ran. Text
    * before and after is kept as is.
    */
  def strip(message: String): String =
    JsonOffsets.replaceAllIn(Excerpt.replaceAllIn(message, ""), "")

  /** The keys of an error's extra info that hold offsets into the statement the node ran
    * (`Exception::SetQueryLocation`), left out of what a policy holder is answered, as [[strip]]
    * leaves them out of a JSON message.
    */
  val OffsetKeys: Set[String] = Set("position", "location")

  /** The fixed text a policy holder is answered with in place of a node error the manager cannot
    * read; the error itself goes to the manager's log.
    */
  val Unreadable: String = "the node's error could not be read (see the manager log)"

  /** `message` as a principal is shown it: [[strip]]ped when `withheld`, untouched otherwise. */
  def shownTo(withheld: Boolean, message: String): String =
    if withheld then strip(message) else message

  private val Excerpt = """\s*(?<!\S)LINE \d+:[^\n]*(?:\n[ \t]*\^+[^\n]*)?""".r

  /** A JSON error's `position` or `location` member with the comma before it, or the run of them
    * that opens the object, each with the comma after it. Inside a JSON string every quote is
    * escaped, so a member can only start after an unescaped `{` or `,`.
    */
  private val JsonOffsets =
    val member = """"(?:position|location)"\s*:\s*(?:"\d+"|\d+|"\[\d+,\s*\d+\]"|\[\d+,\s*\d+\])"""
    raw"""\s*,\s*$member|(?<=\{)(?:\s*$member\s*,?)+""".r
