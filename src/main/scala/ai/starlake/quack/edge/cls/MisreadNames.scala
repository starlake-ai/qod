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

  /** A stored mask or row predicate as DuckDB reads it, by the one function both are read with: the
    * words quoted, then every method call written as the plain call DuckDB makes of it, a
    * name-chain receiver included ([[MethodCalls.desugarStored]]), with the refusal of one that
    * cannot be.
    */
  def stored(text: String): MethodCalls.Desugared = MethodCalls.desugarStored(quoteWords(text))

  /** [[normalize]] for a principal holding column policies: also every method call written as the
    * plain call DuckDB makes of it ([[MethodCalls.desugar]]), with the refusal of one that cannot
    * be. Idempotent like [[normalize]].
    */
  def normalized(sql: String, columnPolicies: Boolean): MethodCalls.Desugared =
    val quoted = quoteWords(sql)
    if columnPolicies then MethodCalls.desugar(quoted) else MethodCalls.Desugared(quoted, None)

  /** `sql` with each unquoted occurrence of [[QuotedWords]] not followed by `(` (a call, or an
    * EXISTS subquery) nor following `$` or `:` (a named parameter) written as the quoted identifier
    * DuckDB reads it as, so that JSqlParser parses a column there. DuckDB binds the quoted word
    * exactly as the bare one.
    */
  def quoteWords(sql: String): String =
    val view                  = SqlLexer.engineView(sql)
    val tokens                = SqlLexer.tokens(sql)
    val solid                 = tokens.filterNot(_.isTrivia)
    def at(i: Int): String    = view.substring(solid(i).start, solid(i).end)
    def ends(i: Int): Boolean =
      i >= 0 && (solid(i).kind == SqlLexer.Kind.Word || solid(i).kind == SqlLexer.Kind.Literal ||
        solid(i).kind == SqlLexer.Kind.Number || Set(")", "]", "}").contains(at(i)))
    // `$key` is a named parameter; `:key` one too when the colon starts an expression. A colon
    // after an expression or a name separates (a struct value, a lambda body, a slice bound), and
    // `::` (one token) is a cast.
    def afterParameterMark(i: Int): Boolean =
      i > 0 && (at(i - 1) == "$" || at(i - 1) == "::" || (at(i - 1) == ":" && !ends(i - 2)))
    // DDL's own uses: `PRIMARY KEY`, `FOREIGN KEY`, `IF [NOT] EXISTS`.
    def keywordRole(i: Int): Boolean =
      i > 0 && {
        val prev = IdentifierFold.fold(at(i - 1))
        IdentifierFold.fold(at(i)) match
          case "key"    => prev == "primary" || prev == "foreign"
          case "exists" => prev == "if" || prev == "not"
          case _        => false
      }
    val quoted = solid.indices.filter { i =>
      val t = solid(i)
      t.kind == SqlLexer.Kind.Word &&
      QuotedWords.contains(IdentifierFold.fold(view.substring(t.start, t.end))) &&
      !solid.lift(i + 1).exists(n => view.substring(n.start, n.end) == "(") &&
      !afterParameterMark(i) && !keywordRole(i)
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

  /** The number of arguments the text gives the call whose argument list opens at token `open` of
    * `solid`: its top-level commas plus one (none for an empty list), up to the call's own
    * `ORDER BY` (DuckDB's only clause there that holds commas; `SEPARATOR` and `LIMIT` are no
    * clause of DuckDB's), a lambda's parameter list (`lambda x, y:`) not counted.
    */
  private def writtenArguments(solid: Vector[SqlLexer.Token], at: Int => String, open: Int): Int =
    var j        = open + 1
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
        if w == "order" && j + 1 < solid.size && IdentifierFold.fold(at(j + 1)) == "by" then
          stopped = true
        else if w == "lambda" then inLambda = true
        else if s == ":" && inLambda then inLambda = false
        else if s == "," && !stopped && !inLambda then commas += 1
      any = true
      j += 1
    if any then commas + 1 else 0

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

  /** Some(refusal) when JSqlParser kept fewer arguments of some call than its text gives it, call
    * by call: each call node is placed in `text` by the parser's own token position, and its
    * argument list there is counted ([[writtenArguments]]). A word the parser consumed, which
    * DuckDB reads as a column, left no node to judge; a call it cannot place is refused too.
    */
  def droppedArgument(text: String, calls: List[AnyRef]): Option[String] =
    val view                  = SqlLexer.engineView(text)
    val solid                 = SqlLexer.tokens(text).filterNot(_.isTrivia)
    def at(i: Int): String    = view.substring(solid(i).start, solid(i).end)
    def refusal(name: String) =
      s"an argument of $name(..) is a word the column policy rewrite cannot read; quote it"
    calls.iterator
      .flatMap { node =>
        parsedArguments(node).map { (name, kept) =>
          firstOffset(node) match
            case None        => Some(refusal(name))
            case Some(start) =>
              solid.indices.find(i => solid(i).start >= start && at(i) == "(") match
                case None       => Some(refusal(name))
                case Some(open) =>
                  Option.when(writtenArguments(solid, at, open) > kept)(refusal(name))
        }.flatten
      }
      .nextOption()

  /** The 0-based offset in the parsed text of a node's first token, from the parser's own node. */
  private def firstOffset(node: AnyRef): Option[Int] =
    node match
      case n: net.sf.jsqlparser.parser.ASTNodeAccess =>
        Option(n.getASTNode).flatMap(a => Option(a.jjtGetFirstToken)).map(_.absoluteBegin - 1)
      case _ => None

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
