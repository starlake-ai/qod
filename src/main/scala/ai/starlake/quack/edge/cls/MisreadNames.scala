package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.authz.FunctionClasses
import ai.starlake.sql.{IdentifierFold, SqlLexer}
import net.sf.jsqlparser.expression.{
  AnalyticExpression,
  ConnectByPriorOperator,
  ConnectByRootOperator,
  DateUnitExpression,
  IntervalExpression,
  JsonAggregateFunction,
  KeyExpression,
  NextValExpression,
  Parenthesis,
  UserVariable
}
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression
import net.sf.jsqlparser.statement.select.{Distinct, PlainSelect, Select}

/** Where JSqlParser reads a column reference as something else (owner ruling D23, review round 2),
  * found by the enumeration the report records: every `K_*` token of JSqlParser's grammar, every
  * enum constant of its jar, DuckDB's `duckdb_keywords()` and DuckDB's SQL value names, in every
  * clause template, keeping each parse holding no column of the word, then the forms DuckDB 2.0
  * reads as that column; and every prefix and postfix operator spelling the same way.
  */
private[cls] object MisreadNames:

  /** Words DuckDB reads as an identifier (none is a DuckDB keyword with a role in a query, `key`
    * and `prior` are unreserved, `exists` only before a parenthesis) that JSqlParser reads, when
    * another token follows, as an operator over it (`KEY x`, `NEXTVAL x`, `PRIOR x`,
    * `CONNECT_BY_ROOT x`, `EXISTS x`), a set operator (`MINUS`) or a select modifier (`SQL_CACHE
    * x`, `STRAIGHT_JOIN x`, ...), where DuckDB reads the column of that name.
    */
  val QuotedWords: Set[String] = Set(
    "connect_by_root",
    "distinctrow",
    "exists",
    "high_priority",
    "key",
    "minus",
    "nextval",
    "prior",
    "sql_big_result",
    "sql_buffer_result",
    "sql_cache",
    "sql_calc_found_rows",
    "sql_no_cache",
    "sql_small_result",
    "straight_join"
  )

  /** `sql` with each unquoted occurrence of [[QuotedWords]] not followed by `(` (a call, or an
    * EXISTS subquery) written as the quoted identifier DuckDB reads it as, so that JSqlParser
    * parses a column there. DuckDB binds the quoted word exactly as the bare one.
    */
  def quoteWords(sql: String): String =
    val view   = SqlLexer.engineView(sql)
    val tokens = SqlLexer.tokens(sql)
    val solid  = tokens.filterNot(_.isTrivia)
    val quoted = solid.indices.filter { i =>
      val t = solid(i)
      t.kind == SqlLexer.Kind.Word &&
      QuotedWords.contains(IdentifierFold.fold(view.substring(t.start, t.end))) &&
      !solid.lift(i + 1).exists(n => view.substring(n.start, n.end) == "(")
    }
    if quoted.isEmpty then sql
    else
      val out  = new StringBuilder(sql)
      val desc = quoted.map(solid).sortBy(-_.start)
      desc.foreach { t =>
        out.insert(t.end, '"')
        out.insert(t.start, '"')
      }
      out.toString

  /** Why a node is a read the column policy rewrite cannot see, for a node JSqlParser makes of a
    * column reference DuckDB reads (one [[quoteWords]] and the time keyword step left), else None.
    */
  // Parenthesis is deprecated in jsqlparser 5.x but still built for `INTERVAL (x) DAY`.
  @scala.annotation.nowarn("msg=class Parenthesis")
  def opaqueReading(node: AnyRef): Option[String] =
    node match
      case _: UserVariable =>
        Some("@ (DuckDB's absolute value operator) over a column")
      case _: KeyExpression | _: NextValExpression | _: ConnectByPriorOperator |
          _: ConnectByRootOperator =>
        Some("a column named like a keyword followed by another word")
      case e: ExistsExpression if !e.getRightExpression.isInstanceOf[Select] =>
        Some("a column named exists followed by another word")
      case _: DateUnitExpression =>
        Some("a column named like a date part")
      case i: IntervalExpression
          if i.getExpression != null &&
            !(i.getExpression.isInstanceOf[net.sf.jsqlparser.expression.Function] ||
              i.getExpression.isInstanceOf[Parenthesis]) =>
        Some("a column named interval followed by another word")
      case ps: PlainSelect
          if Option(ps.getMySqlSelectModifiers).exists(!_.isEmpty) || ps.getMySqlSqlCalcFoundRows ||
            ps.getMySqlSqlCacheFlag != null || ps.getMySqlHintStraightJoin =>
        Some("a column named like a select modifier")
      case d: Distinct if d.isUseUnique || d.isUseDistinctRow =>
        Some("a column named like a select modifier")
      case _ => None

  /** The refusal of a statement holding an [[opaqueReading]]. */
  def refusal(reason: String): String =
    s"$reason reads a column the column policy rewrite cannot see under column policies; " +
      "quote the column name"

  /** Whether the arguments of `node` are an aggregate's (or may be): DuckDB moves an aggregate of a
    * subquery whose arguments name nothing of the subquery's own FROM to the enclosing query, where
    * a SQL value name in it binds the enclosing query's column. Any call that is not a classified
    * built-in scalar function counts (an aggregate, a window or analytic call, a macro, an unknown
    * function), since the rewriter cannot tell what it expands to.
    */
  def aggregateContext(node: AnyRef): Boolean =
    node match
      case _: AnalyticExpression | _: JsonAggregateFunction => true
      case f: net.sf.jsqlparser.expression.Function         =>
        val parts =
          Option(f.getMultipartName).map(_.toArray.toList.map(String.valueOf)).getOrElse(Nil)
        val name    = parts.lastOption.map(ai.starlake.sql.QuotedIdentifier.unquote).getOrElse("")
        val builtIn = parts.size match
          case 1 => true
          case 2 => IdentifierFold.same(parts.head, "main")
          case 3 =>
            IdentifierFold.same(parts.head, "system") && IdentifierFold.same(parts(1), "main")
          case _ => false
        !(builtIn && FunctionClasses.of("system", "main", name, "scalar").isDefined)
      case _ => false
