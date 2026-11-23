package ai.starlake.quack.edge.rls

import ai.starlake.acl.parser.AstChildren
import java.util.Locale
import net.sf.jsqlparser.expression.{
  CastExpression,
  Expression,
  Function => SqlFunction,
  IntervalExpression,
  JsonExpression,
  JsonFunction,
  LongValue,
  TimeKeyExpression
}
import net.sf.jsqlparser.expression.operators.arithmetic.{
  Addition,
  BitwiseLeftShift,
  BitwiseRightShift,
  IntegerDivision,
  Modulo,
  Multiplication,
  Subtraction
}
import net.sf.jsqlparser.expression.operators.relational.{
  ExpressionList,
  LikeExpression,
  ParenthesedExpressionList,
  RegExpMatchOperator
}
import net.sf.jsqlparser.schema.Column

import scala.jdk.CollectionConverters._

/** The forms of a row predicate DuckDB 2.0.0 can answer with an error quoting a value of the row it
  * evaluates: a cast's "Could not convert string '...'", a JSON parse's "Input: ...", a decoder's
  * first bad character, an overflow's operands, a date constructor's "Date out of range: ...", a
  * date part, time zone or regular expression read from the row, `error()`'s own argument. DuckDB
  * evaluates a predicate on every row it reads, the rows the policy removes included, so such an
  * error could hand a hidden row's value to the reader. A new predicate may not use one.
  *
  * A form is refused only when what it can fail on reads a value of the row (a column, or a time
  * keyword DuckDB may bind to one); on constants and identity tokens it fails on every row alike
  * and quotes nothing of any. `TRY(...)` exempts nothing: it does not catch every such error. The
  * rest of the language stays: comparisons and IN lists (a comparison of text with a constant of
  * another type casts the text and can still quote it, a known residual), the identity tokens, and
  * every function and operator not listed here. The evidence for each form is in the round 2 report
  * of the mask-names fix and in `RaisingPredicateSpec`.
  */
private[quack] object PredicateForms:

  /** Called with an argument reading a value, each can raise quoting it. */
  private val RaisingFunctions: Set[String] = Set(
    // parsing text
    "strptime",
    "json",
    "from_json",
    "from_json_strict",
    "from_base64",
    "unhex",
    "from_hex",
    "unbin",
    "from_binary",
    // date, time and interval constructors
    "make_date",
    "make_time",
    "make_timestamp",
    "make_timestamptz",
    "make_timestamp_ns",
    "to_centuries",
    "to_days",
    "to_decades",
    "to_hours",
    "to_microseconds",
    "to_millennia",
    "to_milliseconds",
    "to_minutes",
    "to_months",
    "to_quarters",
    "to_seconds",
    "to_weeks",
    "to_years",
    // arithmetic that overflows
    "abs",
    "add",
    "subtract",
    "multiply",
    "divide",
    "mod",
    // code points and sizes
    "chr",
    "repeat",
    "error"
  )

  /** The JSON functions that parse no argument as JSON (they build or test it). Every other
    * `json_*` function parses its first argument and raises on malformed input, quoting it.
    */
  private val JsonBuilders: Set[String] =
    Set("json_valid", "json_quote", "json_object", "json_array", "json_serialize_sql")

  /** Calls that raise when the argument at the given index (from 0) reads a value: a date part, a
    * time zone or a regular expression read from the row.
    */
  private val RaisingArgument: Map[String, Int] = Map(
    "date_part"             -> 0,
    "datepart"              -> 0,
    "date_trunc"            -> 0,
    "datetrunc"             -> 0,
    "date_diff"             -> 0,
    "datediff"              -> 0,
    "date_sub"              -> 0,
    "datesub"               -> 0,
    "timezone"              -> 0,
    "regexp_matches"        -> 1,
    "regexp_full_match"     -> 1,
    "regexp_extract"        -> 1,
    "regexp_extract_all"    -> 1,
    "regexp_replace"        -> 1,
    "regexp_split_to_array" -> 1,
    "regexp_split_to_table" -> 1,
    "string_split_regex"    -> 1,
    "str_split_regex"       -> 1
  )

  private val TextTarget = "(?i)(varchar|text|string)( \\(\\d+\\))?".r

  /** What a refusal points to instead. */
  val NonRaising: String =
    "compare columns with constants of their own type, and write TRY_CAST(<x> AS <type>) for a " +
      "cast, try_strptime for strptime, CAST(<x> AS VARCHAR) for text; keep arithmetic over " +
      "columns, JSON extraction, decoding, date constructors and patterns read from the row out " +
      "of row predicates"

  /** Every node of `root`, at any depth (the reflective walk every fail-closed walk shares). */
  private def nodes(root: AnyRef): List[AnyRef] =
    val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    val out                    = scala.collection.mutable.ListBuffer.empty[AnyRef]
    def walk(value: Any): Unit =
      value match
        case null                         => ()
        case it: java.lang.Iterable[?]    => it.forEach(walk(_))
        case arr: Array[?]                => arr.foreach(walk)
        case e: java.util.Map.Entry[?, ?] => walk(e.getKey); walk(e.getValue)
        case m: java.util.Map[?, ?]       => m.entrySet.forEach(walk(_))
        case n: AnyRef if AstChildren.isAstNode(n) && seen.add(n) =>
          out += n
          AstChildren.accessors(n.getClass).foreach(m => walk(AstChildren.read(m, n)))
        case _ => ()
    walk(root)
    out.toList

  private def nameOf(fn: SqlFunction): String =
    Option(fn.getMultipartName)
      .map(_.asScala.toList)
      .filter(_.nonEmpty)
      .map(_.last)
      .orElse(Option(fn.getName))
      .map(n => ai.starlake.sql.QuotedIdentifier.unquote(n).toLowerCase(Locale.ROOT))
      .getOrElse("")

  private def argsOf(fn: SqlFunction): List[Expression] =
    Option(fn.getParameters)
      .map(_.asInstanceOf[ExpressionList[Expression]].asScala.toList)
      .getOrElse(Nil)

  /** Whether `e` reads a value of the row: a column, or a time keyword DuckDB may bind to one. */
  private def readsValue(e: AnyRef): Boolean =
    e != null && nodes(e).exists {
      case _: Column            => true
      case _: TimeKeyExpression => true
      case _                    => false
    }

  private def castsToText(c: CastExpression): Boolean =
    val t = c.getColDataType
    t != null && Option(t.getArrayData).forall(_.isEmpty) && TextTarget.matches(t.toString)

  private def isTryCast(c: CastExpression): Boolean =
    Option(c.keyword).exists(_.equalsIgnoreCase("TRY_CAST"))

  @scala.annotation.tailrec
  private def unwrap(e: Expression): Expression =
    e match
      case p: ParenthesedExpressionList[?] if p.size == 1 =>
        unwrap(p.get(0).asInstanceOf[Expression])
      case other => other

  private def positiveInteger(e: Expression): Boolean =
    unwrap(e) match
      case l: LongValue => scala.util.Try(BigInt(l.getStringValue) > 0).getOrElse(false)
      case _            => false

  /** A function name as a refusal names it: one of the fixed lists above, so never the text. */
  private def call(name: String): String = s"$name()"

  /** The raising forms of `expr`, each named once, in the order met: fixed phrases and the names of
    * the listed functions, never the predicate's text. Empty when it holds none.
    */
  def refusals(expr: Expression): List[String] =
    val found = scala.collection.mutable.LinkedHashSet.empty[String]
    nodes(expr).foreach {
      case c: CastExpression =>
        if !isTryCast(c) && !castsToText(c) && readsValue(c.getLeftExpression) then
          found += "CAST or :: to a type other than VARCHAR"
      case fn: SqlFunction =>
        val name = nameOf(fn)
        val args = argsOf(fn)
        if (RaisingFunctions.contains(name) ||
            (name.startsWith("json_") && !JsonBuilders.contains(name))) &&
          args.exists(readsValue)
        then found += call(name)
        else
          RaisingArgument.get(name).foreach { i =>
            if args.lift(i).exists(readsValue) then
              found += s"${call(name)} with an argument read from the row"
          }
      case jf: JsonFunction =>
        if readsValue(jf) then found += "JSON_VALUE, JSON_QUERY or JSON_EXISTS"
      case j: JsonExpression =>
        if readsValue(j.getExpression) then found += "-> / ->>"
      case b: (Addition | Subtraction | Multiplication | BitwiseLeftShift | BitwiseRightShift) =>
        if readsValue(b) then found += "arithmetic (+, -, *, <<, >>)"
      case b: (Modulo | IntegerDivision) =>
        if readsValue(b) && !positiveInteger(b.getRightExpression) then
          found += "% or // by anything but a positive integer constant"
      case i: IntervalExpression =>
        if readsValue(i) then found += "INTERVAL (<expression>)"
      case r: RegExpMatchOperator =>
        if readsValue(r.getRightExpression) then found += "~ with a pattern read from the row"
      case l: LikeExpression =>
        val similar =
          Option(l.getLikeKeyWord).exists(_.toString.toUpperCase(Locale.ROOT).contains("SIMILAR"))
        if similar && readsValue(l.getRightExpression) then
          found += "SIMILAR TO with a pattern read from the row"
      case _ => ()
    }
    found.toList
