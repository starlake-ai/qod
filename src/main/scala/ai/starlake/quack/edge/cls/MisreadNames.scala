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
private[quack] object MisreadNames:

  /** Words DuckDB reads as an identifier (none is a DuckDB keyword with a role in a query, `key`
    * and `prior` are unreserved, `exists` only before a parenthesis) that JSqlParser reads, when
    * another token follows, as an operator over it (`KEY x`, `NEXTVAL x`, `PRIOR x`,
    * `CONNECT_BY_ROOT x`, `EXISTS x`), a set operator (`MINUS`) or a select modifier (`SQL_CACHE
    * x`, `STRAIGHT_JOIN x`, ...), and `OUTPUT`, `PREFERRING` and `XOR`, which it drops when
    * `ORDER BY` follows them in a call's arguments (`list(output ORDER BY id)` parses as `list()`),
    * where DuckDB reads the column of that name.
    */
  val QuotedWords: Set[String] = Set(
    "connect_by_root",
    "distinctrow",
    "exists",
    "high_priority",
    "key",
    "minus",
    "nextval",
    "output",
    "preferring",
    "prior",
    "sql_big_result",
    "sql_buffer_result",
    "sql_cache",
    "sql_calc_found_rows",
    "sql_no_cache",
    "sql_small_result",
    "straight_join",
    "xor"
  )

  /** The one text every policy step reads (the protected-write guard, the column and row policy
    * rewriters, their backstops): the caller's statement with [[quoteWords]] applied. Idempotent,
    * so a step handed it and normalizing again reads the same text.
    */
  def normalize(sql: String): String = quoteWords(sql)

  /** `sql` with each unquoted occurrence of [[QuotedWords]] not followed by `(` (a call, or an
    * EXISTS subquery) nor following `$` or `:` (a named parameter) written as the quoted identifier
    * DuckDB reads it as, so that JSqlParser parses a column there. DuckDB binds the quoted word
    * exactly as the bare one.
    */
  def quoteWords(sql: String): String =
    val view   = SqlLexer.engineView(sql)
    val tokens = SqlLexer.tokens(sql)
    val solid  = tokens.filterNot(_.isTrivia)
    val quoted = solid.indices.filter { i =>
      val t = solid(i)
      t.kind == SqlLexer.Kind.Word &&
      QuotedWords.contains(IdentifierFold.fold(view.substring(t.start, t.end))) &&
      !solid.lift(i + 1).exists(n => view.substring(n.start, n.end) == "(") &&
      !(i > 0 && Set("$", ":").contains(view.substring(solid(i - 1).start, solid(i - 1).end)))
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

  /** Why a node may not appear in a stored mask, which must read its own column alone: a star or a
    * `COLUMNS(..)` call expands into other columns, and a parameter takes one of the caller's.
    */
  def maskOnlyReading(node: AnyRef): Option[String] =
    node match
      case _: net.sf.jsqlparser.statement.select.AllColumns => Some("a star expression")
      case f: net.sf.jsqlparser.expression.Function
          if Option(f.getMultipartName).exists(_.size == 1) &&
            Option(f.getName).exists(n =>
              IdentifierFold.same(ai.starlake.sql.QuotedIdentifier.unquote(n), "columns")
            ) =>
        Some("a COLUMNS(..) expression")
      case _: net.sf.jsqlparser.expression.JdbcParameter |
          _: net.sf.jsqlparser.expression.JdbcNamedParameter =>
        Some("a statement parameter")
      case _ => None

  /** The number of arguments the text gives each call, by function name folded as DuckDB folds it,
    * summed over the calls of that name: an argument list is counted by its top-level commas, up to
    * an `ORDER BY`, `SEPARATOR` or `LIMIT` of its own, a lambda's parameter list (`lambda x, y:`)
    * not counted. A name is a word or a quoted identifier right before `(`.
    */
  def textArguments(text: String): Map[String, Int] =
    val view               = SqlLexer.engineView(text)
    val solid              = SqlLexer.tokens(text).filterNot(_.isTrivia)
    def at(i: Int): String = view.substring(solid(i).start, solid(i).end)
    val counts             = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    solid.indices.foreach { i =>
      val t        = solid(i)
      val nameText = at(i)
      val isName   = t.kind == SqlLexer.Kind.Word ||
        (t.kind == SqlLexer.Kind.Literal && nameText.startsWith("\""))
      if isName && i + 1 < solid.size && at(i + 1) == "(" then
        var j        = i + 2
        var depth    = 0
        var commas   = 0
        var any      = false
        var stopped  = false
        var inLambda = false
        while j < solid.size && !(depth == 0 && at(j) == ")") do
          val s = at(j)
          if s == "(" || s == "[" || s == "{" then depth += 1
          else if s == ")" || s == "]" || s == "}" then depth -= 1
          else if depth == 0 then
            val w = IdentifierFold.fold(s)
            if w == "order" || w == "separator" || w == "limit" then stopped = true
            else if w == "lambda" then inLambda = true
            else if s == ":" && inLambda then inLambda = false
            else if s == "," && !stopped && !inLambda then commas += 1
          any = true
          j += 1
        val name = IdentifierFold.fold(ai.starlake.sql.QuotedIdentifier.unquote(nameText))
        counts(name) += (if any then commas + 1 else 0)
    }
    counts.toMap

  /** The number of arguments JSqlParser kept for a call node, by its function name folded, None for
    * a node that is not a call.
    */
  def parsedArguments(node: AnyRef): Option[(String, Int)] =
    def fold(n: String)      = IdentifierFold.fold(ai.starlake.sql.QuotedIdentifier.unquote(n))
    def size(e: AnyRef): Int =
      e match
        case null                                                                   => 0
        case l: net.sf.jsqlparser.expression.operators.relational.ExpressionList[?] => l.size
        case _                                                                      => 1
    node match
      case f: net.sf.jsqlparser.expression.Function =>
        Option(f.getMultipartName).flatMap(_.toArray.lastOption).map { last =>
          val named  = Option(f.getNamedParameters).map(_.size).getOrElse(0)
          val params = Option(f.getParameters).map(_.size).getOrElse(0)
          fold(String.valueOf(last)) -> (params + named + (if params == 0 && f.isAllColumns then 1
                                                           else 0))
        }
      case a: AnalyticExpression =>
        Option(a.getName).map { n =>
          val star = if a.isAllColumns && a.getExpression == null then 1 else 0
          fold(n) -> (size(a.getExpression) + size(a.getOffset) + size(a.getDefaultValue) + star)
        }
      case _ => None

  /** Some(refusal) when JSqlParser kept fewer arguments for the calls of some name than the text
    * gives them: a word it consumed, which DuckDB reads as a column, left no node to judge.
    */
  def droppedArgument(text: String, calls: List[AnyRef]): Option[String] =
    val parsed  = calls.flatMap(parsedArguments).groupMapReduce(_._1)(_._2)(_ + _)
    val written = textArguments(text)
    parsed.collectFirst {
      case (name, kept) if written.getOrElse(name, 0) > kept =>
        s"an argument of $name(..) is a word the column policy rewrite cannot read; quote it"
    }

  /** Whether the method a call `x.f(..)` names is not a classified built-in scalar function, so
    * that its receiver `x` sits in an aggregate's arguments ([[aggregateContext]]).
    */
  def methodAggregate(f: net.sf.jsqlparser.expression.Function): Boolean =
    val method = Option(f.getMultipartName).flatMap(_.toArray.lastOption).map(String.valueOf)
    !method.exists(m =>
      FunctionClasses
        .of("system", "main", ai.starlake.sql.QuotedIdentifier.unquote(m), "scalar")
        .isDefined
    )

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
