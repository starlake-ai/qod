package ai.starlake.quack.edge.cls

import java.util.Locale
import net.sf.jsqlparser.expression.{
  BooleanValue,
  CaseExpression,
  CastExpression,
  DateTimeLiteralExpression,
  DoubleValue,
  Expression,
  ExtractExpression,
  Function => SqlFunction,
  HexValue,
  IntervalExpression,
  JsonExpression,
  LongValue,
  SignedExpression,
  TimeKeyExpression
}
import net.sf.jsqlparser.expression.operators.arithmetic.{
  Addition,
  BitwiseLeftShift,
  BitwiseRightShift,
  Concat,
  IntegerDivision,
  Modulo,
  Multiplication,
  Subtraction
}
import net.sf.jsqlparser.expression.operators.conditional.{
  AndExpression,
  OrExpression,
  XorExpression
}
import net.sf.jsqlparser.expression.operators.relational.{
  Between,
  ComparisonOperator,
  ExpressionList,
  InExpression,
  IsBooleanExpression,
  IsDistinctExpression,
  IsNullExpression,
  LikeExpression,
  RegExpMatchOperator
}
import net.sf.jsqlparser.schema.Column

import scala.jdk.CollectionConverters._

/** The forms of a mask that DuckDB 2.0 can answer with an error on a row it cannot convert or
  * compute, an error that quotes the row's value (a cast's "Could not convert string '...'", a JSON
  * parse's "Input: ...", an overflow's operands, `error()`'s own argument). A mask so written would
  * hand the unmasked value to whoever reads the masked column, so a new mask may not use one
  * outside DuckDB's `TRY(...)`, which answers NULL instead.
  *
  * A form is refused only when what it can fail on reads a value of the row: a call failing on a
  * constant argument fails on every row alike and says nothing of any. The column's type is not
  * known here, so a comparison of an expression that may be text with a constant of another type
  * (DuckDB then casts the text and can fail) is refused too, as an explicit cast would be.
  */
private[quack] object RaisingForms:

  /** Called with any argument reading a value, each can raise on that value. */
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
    "decode",
    // date and time constructors, epoch and interval conversions
    "make_date",
    "make_time",
    "make_timestamp",
    "make_timestamptz",
    "make_timestamp_ns",
    "to_timestamp",
    "epoch_ms",
    "epoch_us",
    "epoch_ns",
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
    "date_add",
    // arithmetic that overflows
    "abs",
    "add",
    "subtract",
    "multiply",
    "divide",
    "mod",
    "factorial",
    "lcm",
    // code points and sizes
    "chr",
    "repeat"
  )

  /** The JSON functions that parse no argument as JSON (they build or test it). Every other
    * `json_*` function parses its first argument and raises on malformed input, quoting it.
    */
  private val JsonBuilders: Set[String] =
    Set("json_valid", "json_quote", "json_object", "json_array", "json_serialize_sql")

  /** Calls that raise when the argument at the given index (from 0) reads a value: a date part
    * named by the row, a time zone named by the row, a regular expression read from the row.
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

  /** Calls whose result is never text: compared with a number, a boolean or a date they need no
    * cast of text.
    */
  private val NonTextFunctions: Set[String] = Set(
    "length",
    "len",
    "char_length",
    "character_length",
    "strlen",
    "octet_length",
    "bit_length",
    "array_length",
    "list_length",
    "cardinality",
    "ascii",
    "unicode",
    "ord",
    "hash",
    "md5_number",
    "md5_number_lower",
    "md5_number_upper",
    "instr",
    "strpos",
    "position",
    "levenshtein",
    "editdist3",
    "damerau_levenshtein",
    "hamming",
    "mismatches",
    "jaccard",
    "jaro_similarity",
    "jaro_winkler_similarity",
    "year",
    "month",
    "day",
    "dayofmonth",
    "dayofweek",
    "dayofyear",
    "weekday",
    "hour",
    "minute",
    "second",
    "millisecond",
    "microsecond",
    "quarter",
    "week",
    "weekofyear",
    "yearweek",
    "isodow",
    "isoyear",
    "era",
    "decade",
    "century",
    "millennium",
    "epoch",
    "date_part",
    "datepart",
    "date_trunc",
    "datetrunc",
    "datediff",
    "date_diff",
    "datesub",
    "date_sub",
    "time_bucket",
    "last_day",
    "try_strptime",
    "round",
    "floor",
    "ceil",
    "ceiling",
    "trunc",
    "sign",
    "sqrt",
    "cbrt",
    "ln",
    "log",
    "log2",
    "log10",
    "exp",
    "pow",
    "power",
    "bit_count",
    "contains",
    "starts_with",
    "ends_with",
    "prefix",
    "suffix",
    "regexp_matches",
    "regexp_full_match",
    "json_valid",
    "isnan",
    "isinf",
    "isfinite",
    "list_contains",
    "array_contains",
    "list_has"
  )

  /** Calls whose result is one of their arguments. */
  private val PassThroughFunctions: Set[String] =
    Set("coalesce", "ifnull", "nullif", "greatest", "least", "try")

  private val TextTypes: Set[String] =
    Set("varchar", "text", "string", "char", "bpchar", "nvarchar", "character", "character varying")

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
    e != null && StarExpansion.nodes(e).exists {
      case _: Column            => true
      case _: TimeKeyExpression => true
      case _                    => false
    }

  /** A cast's target is text: no value fails to become text. */
  private def castsToText(c: CastExpression): Boolean =
    Option(c.getColDataType)
      .flatMap(t => Option(t.getDataType))
      .map(_.trim.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
      .exists(TextTypes.contains)

  private def isTryCast(c: CastExpression): Boolean =
    Option(c.keyword).exists { k =>
      val u = k.toUpperCase(Locale.ROOT)
      u == "TRY_CAST" || u == "SAFE_CAST"
    }

  /** A constant DuckDB would compare with text by casting the text to its type. */
  private def nonTextConstant(e: Expression): Boolean =
    e match
      case _: LongValue | _: DoubleValue | _: BooleanValue | _: DateTimeLiteralExpression |
          _: HexValue | _: TimeKeyExpression =>
        true
      case s: SignedExpression   => nonTextConstant(s.getExpression)
      case i: IntervalExpression => !readsValue(i)
      case c: CastExpression     => !readsValue(c) && !castsToText(c)
      case _                     => false

  /** Whether `e`'s result may be text, the column's type being unknown. */
  @scala.annotation.nowarn("msg=class Parenthesis")
  private def mayBeText(e: Expression): Boolean =
    e match
      case _: Column                                   => true
      case p: net.sf.jsqlparser.expression.Parenthesis => mayBeText(p.getExpression)
      case l: ExpressionList[?] if l.size == 1         =>
        mayBeText(l.get(0).asInstanceOf[Expression])
      case c: CastExpression => castsToText(c)
      case fn: SqlFunction   =>
        val name = nameOf(fn)
        if NonTextFunctions.contains(name) then false
        else if PassThroughFunctions.contains(name) then argsOf(fn).exists(mayBeText)
        else true
      case ce: CaseExpression =>
        Option(ce.getWhenClauses)
          .map(_.asScala.toList)
          .getOrElse(Nil)
          .exists(w => mayBeText(w.getThenExpression)) ||
        Option(ce.getElseExpression).exists(mayBeText)
      case _: Concat => true
      case _: ComparisonOperator | _: AndExpression | _: OrExpression | _: XorExpression |
          _: LikeExpression | _: IsNullExpression | _: InExpression | _: Between |
          _: IsBooleanExpression | _: IsDistinctExpression | _: RegExpMatchOperator |
          _: ExtractExpression | _: net.sf.jsqlparser.expression.NotExpression =>
        false
      case _: Addition | _: Subtraction | _: Multiplication | _: Modulo | _: IntegerDivision |
          _: net.sf.jsqlparser.expression.operators.arithmetic.Division =>
        false
      case _: net.sf.jsqlparser.expression.StringValue | _: LongValue | _: DoubleValue |
          _: net.sf.jsqlparser.expression.NullValue =>
        false
      case _ => true

  /** `value` compared with `constant` can cast a text value to the constant's type and fail. */
  private def textCast(value: Expression, constant: Expression): Boolean =
    value != null && constant != null && readsValue(value) && mayBeText(value) &&
      nonTextConstant(constant)

  private def compares(a: Expression, b: Expression): Boolean =
    textCast(a, b) || textCast(b, a)

  private def nonZeroLiteral(e: Expression): Boolean =
    e match
      case l: LongValue => l.getValue != 0L && l.getValue != -1L
      case _            => false

  /** The raising forms of `expr` outside `TRY(...)`, each named once, in the order met. */
  def of(expr: Expression): List[String] =
    val all    = StarExpansion.nodes(expr)
    val exempt = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    all.foreach {
      case fn: SqlFunction if nameOf(fn) == "try" =>
        argsOf(fn).foreach(a => StarExpansion.nodes(a).foreach(exempt.add))
      case _ => ()
    }
    val found = scala.collection.mutable.LinkedHashSet.empty[String]
    all.filterNot(exempt.contains).foreach {
      case c: CastExpression =>
        if !isTryCast(c) && !castsToText(c) && readsValue(c.getLeftExpression) then
          found += (if c.keyword == null then "::" else "CAST")
      case fn: SqlFunction =>
        val name = nameOf(fn)
        val args = argsOf(fn)
        if name == "error" then found += "error()"
        else if (RaisingFunctions.contains(name) ||
            (name.startsWith("json_") && !JsonBuilders.contains(name))) &&
          args.exists(readsValue)
        then found += s"$name()"
        else
          RaisingArgument.get(name).foreach { i =>
            if args.lift(i).exists(readsValue) then
              found += s"$name() with an argument read from the row"
          }
        if name == "nullif" then
          args match
            case a :: b :: _ if compares(a, b) =>
              found += "a comparison of text with a non-text constant"
            case _ => ()
      case jf: net.sf.jsqlparser.expression.JsonFunction =>
        val reads = Option(jf.getType)
          .map(_.toString)
          .exists(t => t == "VALUE" || t == "QUERY" || t == "EXISTS")
        if reads && readsValue(jf) then
          found += s"json_${jf.getType.toString.toLowerCase(Locale.ROOT)}()"
      case j: JsonExpression =>
        if readsValue(j.getExpression) then found += "-> / ->>"
      case b: (Addition | Subtraction | Multiplication | BitwiseLeftShift | BitwiseRightShift) =>
        if readsValue(b) then found += "arithmetic (+, -, *, <<, >>)"
      case m: Modulo =>
        if readsValue(m) && !nonZeroLiteral(m.getRightExpression) then found += "% (modulo)"
      case d: IntegerDivision =>
        if readsValue(d) && !nonZeroLiteral(d.getRightExpression) then
          found += "// (integer division)"
      case s: SignedExpression =>
        if s.getSign == '-' && readsValue(s.getExpression) then
          found += "arithmetic (+, -, *, <<, >>)"
      case i: IntervalExpression =>
        if readsValue(i) then found += "INTERVAL (<expression>)"
      case r: RegExpMatchOperator =>
        if readsValue(r.getRightExpression) then found += "~ with a pattern read from the row"
      case l: LikeExpression =>
        val similar =
          Option(l.getLikeKeyWord).exists(_.toString.toUpperCase(Locale.ROOT).contains("SIMILAR"))
        if similar && readsValue(l.getRightExpression) then
          found += "SIMILAR TO with a pattern read from the row"
      case c: ComparisonOperator =>
        if compares(c.getLeftExpression, c.getRightExpression) then
          found += "a comparison of text with a non-text constant"
      case d: IsDistinctExpression =>
        if compares(d.getLeftExpression, d.getRightExpression) then
          found += "a comparison of text with a non-text constant"
      case in: InExpression =>
        in.getRightExpression match
          case list: ExpressionList[?] =>
            if list.asScala.exists(e => compares(in.getLeftExpression, e.asInstanceOf[Expression]))
            then found += "a comparison of text with a non-text constant"
          case _ => ()
      case bt: Between =>
        if compares(bt.getLeftExpression, bt.getBetweenExpressionStart) ||
          compares(bt.getLeftExpression, bt.getBetweenExpressionEnd)
        then found += "a comparison of text with a non-text constant"
      case ib: IsBooleanExpression =>
        if readsValue(ib.getLeftExpression) && mayBeText(ib.getLeftExpression) then
          found += "a comparison of text with a non-text constant"
      case ce: CaseExpression =>
        Option(ce.getSwitchExpression).foreach { sw =>
          val whens = Option(ce.getWhenClauses).map(_.asScala.toList).getOrElse(Nil)
          if whens.exists(w => compares(sw, w.getWhenExpression)) then
            found += "a comparison of text with a non-text constant"
        }
      case _ => ()
    }
    found.toList
