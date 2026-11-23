package ai.starlake.quack.edge.cls

import net.sf.jsqlparser.expression.{Alias, Expression}
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.select.SelectItem

/** The result column name of a select item the column policy rewriter changed.
  *
  * DuckDB names an unaliased select item after its expression, so an item holding a masked column
  * would be named after the rewritten expression, the mask's text included, on every door (the
  * FlightSQL schema and stream, the native front door, MCP, REST). Every item the rewriter changes
  * and the caller gave no alias of their own gets one taken from the item as it read before the
  * rewrite: a bare column keeps its column name (what DuckDB names it), anything else the item's
  * text as the parser prints it, trimmed, a line break read as a space, as a quoted identifier. The
  * name therefore never depends on a policy. An item the rewriter left as it was keeps DuckDB's own
  * naming.
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
