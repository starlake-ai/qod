package ai.starlake.quack.edge.cls

import net.sf.jsqlparser.expression.{Alias, Expression}
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.select.{
  AllColumns,
  ParenthesedSelect,
  PlainSelect,
  Select,
  SelectItem,
  SetOperationList
}

/** The result column name of a select item a policy rewriter changed (the column policy rewriter,
  * or the row policy rewriter, which runs after it on its output).
  *
  * DuckDB names an unaliased select item after its expression, so an item holding a masked column
  * would be named after the rewritten expression, the mask's text included, on every door (the
  * FlightSQL schema and stream, the native front door, MCP, REST). Every item the rewriter changes
  * and the caller gave no alias of their own gets one taken from the item as it read before the
  * rewrite: a bare column keeps its column name (what DuckDB names it), anything else the item's
  * text as the parser prints it, trimmed, a line break read as a space, as a quoted identifier. The
  * name therefore never depends on a policy. An item the rewriter left as it was keeps DuckDB's own
  * naming, except in a `UNION [ALL] BY NAME`, whose arms match their columns by name: there every
  * unaliased item that is not a bare column is named so, changed or not, so the arms still match.
  * The row policy rewriter replaces a table with a filtered view holding the administrator's
  * predicate and a random alias, which an item holding a subquery over that table would otherwise
  * be named after; it names the items it changed the same way ([[before]], [[nameChanged]]). An
  * item the column rewriter already named carries that alias, so the second pass leaves it as it
  * is: the two never stack, and the name is the item's text before either rewrite.
  */
private[quack] object ResultNames:

  /** `text` as a DuckDB quoted identifier. */
  def quoted(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

  /** The quoted identifier's text, `""` read as `"`; a name holding no quotes as it is. */
  def unquoted(name: String): String =
    if name.length >= 2 && name.startsWith("\"") && name.endsWith("\"") then
      name.substring(1, name.length - 1).replace("\"\"", "\"")
    else name

  /** The alias the rewriter gives `expr`, the item as it read before the rewrite. */
  def aliasFor(expr: Expression): Alias =
    expr match
      case col: Column => new Alias(col.getColumnName)
      case other       =>
        val text = other.toString.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim
        new Alias(quoted(text))

  /** Applies `f` to the unquoted text of every select item alias of `root` that `holds`, at any
    * depth, and writes the result back quoted: how a step that renamed parts of the statement for
    * the rewriter (a time-travel placeholder, a table renamed apart) puts the caller's spelling
    * back into the names the rewriter printed from them.
    */
  def rewriteAliases(root: AnyRef, holds: String => Boolean)(f: String => String): Unit =
    StarExpansion.nodes(root).foreach {
      case si: SelectItem[?] =>
        Option(si.getAlias).foreach { a =>
          val name = Option(a.getName).getOrElse("")
          if holds(name) then
            val text = f(unquoted(name)).replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
            a.setName(quoted(text))
        }
      case _ => ()
    }

  /** Whether a select item's expression is a star (`*`, `t.*`, `COLUMNS(*)`-free forms). */
  private def isStar(si: SelectItem[?]): Boolean = si.getExpression.isInstanceOf[AllColumns]

  /** Whether `sol` matches its arms' columns by name. */
  def byName(sol: SetOperationList): Boolean =
    Option(sol.getOperations).exists(_.stream().anyMatch { op =>
      Option(op.getModifier).exists(_.toUpperCase(java.util.Locale.ROOT).contains("BY NAME"))
    })

  /** The plain selects of the arms of `sol`, parentheses unwrapped. */
  private def arms(sol: SetOperationList): List[PlainSelect] =
    def plain(s: Select): Option[PlainSelect] =
      s match
        case ps: PlainSelect      => Some(ps)
        case p: ParenthesedSelect => Option(p.getSelect).flatMap(plain)
        case _                    => None
    Option(sol.getSelects).map(_.toArray.toList).getOrElse(Nil).flatMap {
      case s: Select => plain(s)
      case _         => None
    }

  /** The unaliased items of a `BY NAME` set operation's arms that are neither a bare column nor a
    * star: what [[nameByName]] names.
    */
  private def byNameItems(sol: SetOperationList): List[SelectItem[?]] =
    if !byName(sol) then Nil
    else
      arms(sol)
        .flatMap(ps => Option(ps.getSelectItems).map(_.toArray.toList).getOrElse(Nil))
        .collect {
          case si: SelectItem[?]
              if si.getAlias == null && !isStar(si) && !si.getExpression.isInstanceOf[Column] =>
            si
        }

  /** Names every unaliased item of `sol`'s arms that is not a bare column after its text now, when
    * the arms match by name: called before a rewrite changes them.
    */
  def nameByName(sol: SetOperationList): Unit =
    byNameItems(sol).foreach(si => si.setAlias(aliasFor(si.getExpression)))

  /** The unaliased select items of a statement, each with the name it would get and its text, taken
    * before a rewrite.
    */
  final class Before private[ResultNames] (
      val items: List[(SelectItem[?], Alias, String)],
      val byNameSets: List[SetOperationList]
  )

  /** [[Before]] of `root`, at any depth. */
  def before(root: AnyRef): Before =
    val nodes = StarExpansion.nodes(root)
    val items = nodes.collect {
      case si: SelectItem[?] if si.getAlias == null && si.getExpression != null && !isStar(si) =>
        (si, aliasFor(si.getExpression), si.getExpression.toString)
    }
    new Before(items, nodes.collect { case sol: SetOperationList if byName(sol) => sol })

  /** After a rewrite: names every item of `b` it changed (its text now differs) and that still has
    * no alias after its text before; in a `BY NAME` set operation every such item that is not a
    * bare column, changed or not.
    */
  def nameChanged(b: Before): Unit =
    val inByName = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    b.byNameSets.foreach(sol => byNameItems(sol).foreach(inByName.add))
    b.items.foreach { (si, alias, text) =>
      if si.getAlias == null && (si.getExpression.toString != text || inByName.contains(si)) then
        si.setAlias(alias)
    }
