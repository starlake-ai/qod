package ai.starlake.quack.edge.policy

import ai.starlake.acl.parser.SqlParser
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.util.TablesNamesFinder
import scala.util.Try

/** Locates historical table references for name-stability checks. Caller SQL is never rewritten. */
object TimeTravelCarrier:
  /** Some(table coordinates), or None when a historical clause cannot be tied to one table. */
  def pinnedTables(sql: String): Option[List[(Option[String], Option[String], String)]] =
    val (stripped, clauses) = SqlParser.timeTravelClauses(sql)
    if clauses.isEmpty then Some(Nil)
    else
      Try(CCJSqlParserUtil.parse(stripped)).toOption.flatMap { stmt =>
        val offsets = lineOffsets(stripped)
        val ends    = tablesOf(stmt).flatMap(t => endOffset(t, offsets).map(t -> _))
        val seen    = java.util.Collections.newSetFromMap(
          new java.util.IdentityHashMap[Table, java.lang.Boolean]()
        )
        val pinned = clauses.map { clause =>
          ends.filter { (_, end) =>
            end <= clause.at && stripped.substring(end, clause.at).forall(_.isWhitespace)
          } match
            case List((t, _))
                if t.getTimeTravel == null && t.getTimeTravelStrAfterAlias == null && seen.add(t) =>
              Some(t)
            case _ => None
        }
        Option.when(pinned.forall(_.isDefined)) {
          pinned.flatten.map(t =>
            (
              Option(t.getDatabaseName).map(unquote),
              Option(t.getSchemaName).map(unquote),
              unquote(t.getName)
            )
          )
        }
      }

  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)

  /** Every [[Table]] node reached by jsqlparser's traversal, each once. */
  private def tablesOf(stmt: Statement): List[Table] =
    val found = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[Table, java.lang.Boolean]()
    )
    val order  = List.newBuilder[Table]
    val finder = new TablesNamesFinder[Void]:
      override def visit[S](table: Table, context: S): Void =
        if found.add(table) then order += table
        super.visit(table, context)
    Try(finder.getTables(stmt)): Unit
    order.result()

  /** Offset of the first character of each line, with JavaCC's line breaks (`\n`, `\r\n`, a lone
    * `\r`); columns count UTF-16 units and a tab as one, like `String` indices.
    */
  private def lineOffsets(s: String): IndexedSeq[Int] =
    val starts = IndexedSeq.newBuilder[Int]
    starts += 0
    var i = 0
    while i < s.length do
      s(i) match
        case '\n'                                          => starts += i + 1
        case '\r' if i + 1 >= s.length || s(i + 1) != '\n' => starts += i + 1
        case _                                             => ()
      i += 1
    starts.result()

  /** Offset just past the table reference, its alias included, in the text it was parsed from. */
  private def endOffset(t: Table, lines: IndexedSeq[Int]): Option[Int] =
    Option(t.getASTNode).flatMap(n => Option(n.jjtGetLastToken)).collect {
      case tok if tok.endLine >= 1 && tok.endLine <= lines.size =>
        lines(tok.endLine - 1) + tok.endColumn
    }
