package ai.starlake.quack.edge.cls

import java.util.Locale
import net.sf.jsqlparser.expression.{
  ArrayExpression,
  BooleanValue,
  CaseExpression,
  CastExpression,
  DoubleValue,
  Expression,
  Function => SqlFunction,
  LongValue,
  NullValue,
  RangeExpression,
  SignedExpression,
  StringValue,
  TrimFunction
}
import net.sf.jsqlparser.expression.operators.arithmetic.{
  Addition,
  Concat,
  Division,
  Modulo,
  Multiplication,
  Subtraction
}
import net.sf.jsqlparser.expression.operators.relational.{
  ExpressionList,
  IsNullExpression,
  ParenthesedExpressionList
}
import net.sf.jsqlparser.schema.Column

import scala.jdk.CollectionConverters._

/** The forms a column mask may be written with: an allowlist, each form admitted because DuckDB
  * 2.0.0 answers it, over a value of any type the column may have, with a result, a binder error
  * (the same for every row, before any is read) or an error that quotes no value of the row. A mask
  * DuckDB cannot evaluate on a row answers with DuckDB's error, and its conversion, parsing and
  * overflow errors quote the value they failed on, so a form outside this list could hand the
  * unmasked value to whoever reads the masked column. The column's type is not known when a mask is
  * written, so a form is judged for every type: a `VARIANT` column, for one, is cast implicitly by
  * every function taking text, a blob or a number, and that cast quotes the value; a `BIGNUM` one
  * by every function taking a number. The evidence for each form is in the round 1 report of the
  * mask-names fix.
  *
  * The forms, `<x>` being any allowed form and `<text>` one whose result is text:
  *   - a constant: a plain string literal (no prefix, no dollar quotes), a number, `NULL`, `TRUE`
  *     or `FALSE` as the whole mask; the column itself;
  *   - `<x> || <x>` and `concat(<x>, ...)`, with string constants or NULL beside them;
  *   - `CAST(<x> AS VARCHAR)`, `TRY_CAST(<x> AS VARCHAR)`, `<x>::VARCHAR` (also `TEXT`, `STRING`, a
  *     length allowed) and `hash(<x>, ...)`;
  *   - a subscript or a slice of `<x>` (`<x>[i]`, `<x>[a:b]`, `<x>[a:b:s]`, nested), each index an
  *     integer or string constant and each slice bound or step an integer constant or left out: an
  *     index that reads the value is cast to the subscript's type, and that cast quotes it;
  *   - over `<x>`: `upper`, `lower`, `trim` (with a string constant), `left` / `right` (an integer
  *     constant), `substr` (integer constants), `lpad` / `rpad` (an integer and a string constant),
  *     `regexp_replace` (string constants): text or a binder error over a value of every type;
  *   - over `<text>` only: `md5`, `sha1`, `sha256`, `length`, `ltrim`, `rtrim` (with a string
  *     constant), `substring` (integer constants): over a `VARIANT` or `BIGNUM` value the first
  *     four cast it to a blob or a bit string and quote it;
  *   - `coalesce(<x>, ...)` and `CASE WHEN <x> IS [NOT] NULL THEN <x> ... [ELSE <x>] END`, whose
  *     values are string constants or NULL beside either text forms only or one other form;
  *     `nullif(<x>, <string constant or NULL>)`;
  *   - numbers: `TRY_CAST(<x> AS DOUBLE)`, `/` by a constant other than 0, `%` by a positive
  *     integer constant, `round` (with an integer constant), `floor` and `ceil` of a DOUBLE, and
  *     `+`, `-`, `*` and a minus sign over DOUBLE results and numeric constants, the operands of
  *     `/` and `%` being DOUBLE results or `length` / `hash`.
  *
  * Function names are written plain (no quotes, no catalog or schema), so no macro can stand in for
  * the built-in.
  */
private[quack] object MaskForms:

  /** What a form's result is known to be. */
  private enum Kind:
    /** A plain string literal. */
    case Str

    /** NULL. */
    case Null

    /** A numeric literal. */
    case Num

    /** A boolean literal (only as the whole mask). */
    case Bool

    /** Text, or a binder error. */
    case Text

    /** An integer of a numeric type (`length`, `hash`), or a binder error. */
    case Int

    /** A DOUBLE (or FLOAT), or a binder error. */
    case Dbl

    /** A value of a type not known here: the column's own, at least. */
    case Unknown

  import Kind._

  private def isConstant(k: Kind): Boolean = k == Str || k == Null || k == Num || k == Bool
  private def textLike(k: Kind): Boolean   = k == Text || k == Str || k == Null
  private def numeric(k: Kind): Boolean    = k == Dbl || k == Int

  /** The parts of `expr` that are not allowed mask forms, each named once, in the order met: a
    * fixed phrase, or a function name the writer wrote, never the mask's text. Empty when every
    * part is an allowed form.
    */
  def refusals(expr: Expression): List[String] =
    val found = scala.collection.mutable.LinkedHashSet.empty[String]
    unwrap(expr) match
      case _: BooleanValue => ()
      case _               => kindOf(expr, found): Unit
    found.toList

  /** The allowed forms, for a refusal's message. */
  val Allowed: String =
    "string and numeric constants, NULL, the column, || and concat, CAST or TRY_CAST to VARCHAR, " +
      "hash, a subscript or slice with constant indexes, upper / lower / trim / left / right / " +
      "substr / lpad / rpad / regexp_replace with constant arguments, md5 / sha1 / sha256 / " +
      "length / ltrim / rtrim / substring over text (CAST(<column> AS VARCHAR) first) with " +
      "constant arguments, coalesce, nullif with a text constant, CASE WHEN " +
      "<x> IS [NOT] NULL THEN ... END, and DOUBLE arithmetic: TRY_CAST(<column> AS DOUBLE), / by " +
      "a nonzero constant, % by a positive integer, round / floor / ceil, + - *"

  @scala.annotation.tailrec
  private def unwrap(e: Expression): Expression =
    e match
      case p: ParenthesedExpressionList[?] if p.size == 1 =>
        unwrap(p.get(0).asInstanceOf[Expression])
      case other => other

  private def plainString(s: StringValue): Boolean =
    (s.getPrefix == null || s.getPrefix.isEmpty) && s.toString.startsWith("'")

  private def numericLiteral(e: Expression): Option[BigDecimal] =
    unwrap(e) match
      case l: LongValue   => scala.util.Try(BigDecimal(l.getStringValue)).toOption
      case d: DoubleValue => scala.util.Try(BigDecimal(d.toString)).toOption
      case s: SignedExpression if s.getSign == '-' =>
        unwrap(s.getExpression) match
          case _: LongValue | _: DoubleValue => numericLiteral(s.getExpression).map(-_)
          case _                             => None
      case _ => None

  private def integerLiteral(e: Expression): Boolean =
    unwrap(e) match
      case _: LongValue                            => true
      case s: SignedExpression if s.getSign == '-' =>
        unwrap(s.getExpression).isInstanceOf[LongValue]
      case _ => false

  private def stringLiteral(e: Expression): Boolean =
    unwrap(e) match
      case s: StringValue => plainString(s)
      case _              => false

  private def argsOf(fn: SqlFunction): List[Expression] =
    Option(fn.getParameters)
      .map(_.asInstanceOf[ExpressionList[Expression]].asScala.toList)
      .getOrElse(Nil)

  /** The plain, lower-case name of a call written `name(args)` and nothing more, else None. */
  private def plainName(fn: SqlFunction): Option[String] =
    val name  = Option(fn.getName).getOrElse("")
    val parts = Option(fn.getMultipartName).map(_.asScala.toList).getOrElse(Nil)
    val plain = name.nonEmpty && name.forall(c => c.isLetterOrDigit || c == '_') &&
      parts.size <= 1
    val shape = fn.toString == s"$name(${argsOf(fn).map(_.toString).mkString(", ")})"
    Option.when(plain && shape)(name.toLowerCase(Locale.ROOT))

  private val TextTarget   = "(?i)(varchar|text|string)( \\(\\d+\\))?".r
  private val DoubleTarget = "(?i)double".r

  /** The cast's keyword (`CAST`, `TRY_CAST`, or None for `::`) and target type text. */
  private def castShape(c: CastExpression): Option[(Option[String], String)] =
    val t      = c.getColDataType
    val noDims = t != null && Option(t.getArrayData).forall(_.isEmpty) &&
      Option(t.getArgumentsStringList).forall(_.isEmpty) && c.getFormat == null &&
      Option(c.getColumnDefinitions).forall(_.isEmpty)
    val kw = Option(c.keyword).map(_.toUpperCase(Locale.ROOT))
    if !noDims then None
    else
      val ty    = t.toString
      val shape = kw match
        case None    => c.toString == s"${c.getLeftExpression}::$ty"
        case Some(k) => c.toString.equalsIgnoreCase(s"$k(${c.getLeftExpression} AS $ty)")
      Option.when(shape && (kw.isEmpty || kw.contains("CAST") || kw.contains("TRY_CAST")))(
        (kw, ty)
      )

  /** The kind the values of a coalesce or a CASE unify to, or a refusal. */
  private def unify(
      values: List[Expression],
      what: String,
      found: scala.collection.mutable.LinkedHashSet[String]
  ): Kind =
    val kinds = values.map(v => kindOf(v, found))
    if kinds.contains(Num) then
      found += s"a numeric constant among the values of $what (DuckDB may cast the column to " +
        "its type; write a string constant)"
    val valued = kinds.filterNot(isConstant)
    if valued.forall(_ == Text) then Text
    else if valued.size == 1 then valued.head
    else
      found += s"$what mixing values that are not all text (CAST each to VARCHAR)"
      Unknown

  private def kindOf(
      e: Expression,
      found: scala.collection.mutable.LinkedHashSet[String]
  ): Kind =
    def refuse(part: String): Kind =
      found += part
      Unknown
    def constArg(name: String, ok: Boolean, what: String): Unit =
      if !ok then found += s"$name() with an argument that is not $what"
    unwrap(e) match
      case s: StringValue =>
        if plainString(s) then Str
        else refuse("a string constant with a prefix or dollar quotes")
      case _: NullValue                            => Null
      case _: LongValue | _: DoubleValue           => Num
      case s: SignedExpression if s.getSign == '-' =>
        kindOf(s.getExpression, found) match
          case Num => Num
          case Dbl => Dbl
          case _   => refuse("a minus sign outside DOUBLE arithmetic")
      case c: Column =>
        Option(c.getArrayConstructor).foreach(ac => subscript(ac, found))
        Unknown
      case a: ArrayExpression =>
        kindOf(a.getObjExpression, found)
        val index  = Option(a.getIndexExpression).toList
        val bounds = List(a.getStartIndexExpression, a.getStopIndexExpression, a.getStepExpression)
          .filter(_ != null)
        val ok = (index, bounds) match
          case (List(i), Nil) => integerLiteral(i) || stringLiteral(i)
          case (Nil, bs)      => bs.forall(integerLiteral)
          case _              => false
        if !ok then found += SubscriptPart
        Unknown
      case c: Concat =>
        val kinds = List(c.getLeftExpression, c.getRightExpression).map(kindOf(_, found))
        if kinds.contains(Num) then found += "a numeric constant beside || (write a string one)"
        if kinds.exists(k => k == Text || k == Str) then Text else Unknown
      case c: CastExpression =>
        castShape(c) match
          case Some((_, TextTarget(_*))) =>
            kindOf(c.getLeftExpression, found)
            Text
          case Some((Some("TRY_CAST"), DoubleTarget())) =>
            kindOf(c.getLeftExpression, found)
            Dbl
          case _ =>
            refuse(
              "a cast other than CAST or :: to VARCHAR, or TRY_CAST to VARCHAR or DOUBLE"
            )
      case t: TrimFunction =>
        if t.getTrimSpecification != null || t.isUsingFromKeyword then
          refuse("TRIM(BOTH | LEADING | TRAILING ... FROM ...)")
        else
          kindOf(t.getExpression, found): Unit
          Option(t.getFromExpression).foreach(f =>
            constArg("trim", stringLiteral(f), "a string constant")
          )
          Text
      case ce: CaseExpression =>
        if ce.getSwitchExpression != null then
          refuse("CASE <expression> WHEN (a comparison: write CASE WHEN <x> IS NULL)")
        else
          val whens = Option(ce.getWhenClauses).map(_.asScala.toList).getOrElse(Nil)
          whens.foreach { w =>
            unwrap(w.getWhenExpression) match
              case n: IsNullExpression if n.toString.matches("(?is).* IS (NOT )?NULL") =>
                kindOf(n.getLeftExpression, found)
              case _ => found += "a CASE condition other than <x> IS [NOT] NULL"
          }
          unify(
            whens.map(_.getThenExpression) ++ Option(ce.getElseExpression).toList,
            "a CASE",
            found
          )
      case d: Division =>
        val left = kindOf(d.getLeftExpression, found)
        if !numeric(left) then
          found += "/ of a value not known to be a number (write TRY_CAST(<column> AS DOUBLE))"
        if !numericLiteral(d.getRightExpression).exists(_ != 0) then
          found += "/ by anything but a numeric constant other than 0"
        Dbl
      case m: Modulo =>
        val left = kindOf(m.getLeftExpression, found)
        if !numeric(left) then
          found += "% of a value not known to be a number (write TRY_CAST(<column> AS DOUBLE))"
        val positive = integerLiteral(m.getRightExpression) &&
          numericLiteral(m.getRightExpression).exists(_ > 0)
        if !positive then found += "% by anything but a positive integer constant"
        if left == Dbl then Dbl else Int
      case b: (Addition | Subtraction | Multiplication) =>
        val kinds = List(b.getLeftExpression, b.getRightExpression).map(kindOf(_, found))
        if kinds.forall(k => k == Dbl || k == Num) && kinds.contains(Dbl) then Dbl
        else
          refuse(
            "+, - or * outside DOUBLE arithmetic (an operand round(), floor(), ceil() or " +
              "TRY_CAST(<column> AS DOUBLE), the other one too or a numeric constant)"
          )
      case fn: SqlFunction =>
        plainName(fn) match
          case None       => refuse("a function call written other than name(arguments)")
          case Some(name) => functionKind(name, argsOf(fn), found)
      case _: IsNullExpression =>
        refuse("IS [NOT] NULL outside a CASE condition")
      case other => refuse(s"a ${describe(other)}")

  private val SubscriptPart =
    "a subscript or slice other than one with integer or string constant indexes (an index read " +
      "from the value is cast, and the cast quotes it)"

  /** A column's subscripts (`c[i]`, `c[a:b]`, `c[a:b:s]`): each index an integer or string
    * constant, each slice bound or step an integer constant or left out.
    */
  private def subscript(
      ac: net.sf.jsqlparser.expression.ArrayConstructor,
      found: scala.collection.mutable.LinkedHashSet[String]
  ): Unit =
    val items =
      Option(ac.getExpressions).map(_.asScala.toList.map(_.asInstanceOf[Expression])).getOrElse(Nil)
    val ok = !ac.isArrayKeyword && ac.getDataType == null && items.size == 1 && (unwrap(
      items.head
    ) match
      case r: RangeExpression =>
        List(r.getStartExpression, r.getEndExpression, r.getStepExpression)
          .filter(_ != null)
          .forall(integerLiteral)
      case i => integerLiteral(i) || stringLiteral(i))
    if !ok then found += SubscriptPart

  private def functionKind(
      name: String,
      args: List[Expression],
      found: scala.collection.mutable.LinkedHashSet[String]
  ): Kind =
    def textArg(a: Expression): Unit =
      if !textLike(kindOf(a, found)) then
        found += s"$name() of a value that is not text (write $name(CAST(<column> AS VARCHAR)))"
    def arity(ok: Boolean): Boolean =
      if !ok then found += s"$name() with another number of arguments"
      ok
    def const(ok: Boolean, what: String): Unit =
      if !ok then found += s"$name() with an argument that is not $what"
    def anyValue(a: Expression): Kind =
      val k = kindOf(a, found)
      if k == Num then found += s"a numeric constant as an argument of $name()"
      k
    // The first argument of the functions DuckDB answers with text or a binder error over a value
    // of every type (owner ruling): any allowed form.
    def valueArg(a: Expression): Unit = kindOf(a, found): Unit
    name match
      case "md5" | "sha1" | "sha256" =>
        if arity(args.size == 1) then textArg(args.head)
        Text
      case "upper" | "lower" =>
        if arity(args.size == 1) then valueArg(args.head)
        Text
      case "ltrim" | "rtrim" =>
        if arity(args.size == 1 || args.size == 2) then
          textArg(args.head)
          args.lift(1).foreach(a => const(stringLiteral(a), "a string constant"))
        Text
      case "trim" =>
        if arity(args.size == 1 || args.size == 2) then
          valueArg(args.head)
          args.lift(1).foreach(a => const(stringLiteral(a), "a string constant"))
        Text
      case "left" | "right" =>
        if arity(args.size == 2) then
          valueArg(args.head)
          const(integerLiteral(args(1)), "an integer constant")
        Text
      case "substr" =>
        if arity(args.size == 2 || args.size == 3) then
          valueArg(args.head)
          args.tail.foreach(a => const(integerLiteral(a), "an integer constant"))
        Text
      case "substring" =>
        if arity(args.size == 2 || args.size == 3) then
          textArg(args.head)
          args.tail.foreach(a => const(integerLiteral(a), "an integer constant"))
        Text
      case "lpad" | "rpad" =>
        if arity(args.size == 3) then
          valueArg(args.head)
          const(integerLiteral(args(1)), "an integer constant")
          const(stringLiteral(args(2)), "a string constant")
        Text
      case "regexp_replace" =>
        if arity(args.size == 3 || args.size == 4) then
          valueArg(args.head)
          args.tail.foreach(a => const(stringLiteral(a), "a string constant"))
        Text
      case "length" =>
        if arity(args.size == 1) then textArg(args.head)
        Int
      case "hash" =>
        if arity(args.nonEmpty) then args.foreach(anyValue)
        Int
      case "concat" =>
        if arity(args.nonEmpty) then
          val kinds = args.map(anyValue)
          if kinds.exists(k => k == Text || k == Str) then Text else Unknown
        else Unknown
      case "coalesce" =>
        if arity(args.nonEmpty) then unify(args, "coalesce()", found) else Unknown
      case "nullif" =>
        if arity(args.size == 2) then
          val k = anyValue(args.head)
          unwrap(args(1)) match
            case _: NullValue                     => ()
            case s: StringValue if plainString(s) => ()
            case _                                =>
              found += "nullif() with a second argument other than a string constant or NULL"
          if k == Str then Text else k
        else Unknown
      case "round" =>
        if arity(args.size == 1 || args.size == 2) then
          if kindOf(args.head, found) != Dbl then
            found += "round() of a value that is not a DOUBLE (write TRY_CAST(<column> AS DOUBLE))"
          args.lift(1).foreach(a => const(integerLiteral(a), "an integer constant"))
        Dbl
      case "floor" | "ceil" | "ceiling" =>
        if arity(args.size == 1) && kindOf(args.head, found) != Dbl then
          found += s"$name() of a value that is not a DOUBLE (write TRY_CAST(<column> AS DOUBLE))"
        Dbl
      case other =>
        found += s"function '$other'"
        args.foreach(kindOf(_, found))
        Unknown

  /** A fixed name for a node kind no form allows. */
  private def describe(e: Expression): String =
    e match
      case _: BooleanValue => "boolean constant inside an expression"
      case _: net.sf.jsqlparser.expression.operators.relational.ComparisonOperator =>
        "comparison"
      case _: net.sf.jsqlparser.expression.operators.conditional.AndExpression |
          _: net.sf.jsqlparser.expression.operators.conditional.OrExpression |
          _: net.sf.jsqlparser.expression.operators.conditional.XorExpression |
          _: net.sf.jsqlparser.expression.NotExpression =>
        "boolean operator (AND, OR, XOR, NOT)"
      case _: net.sf.jsqlparser.expression.operators.relational.InExpression   => "IN list"
      case _: net.sf.jsqlparser.expression.operators.relational.Between        => "BETWEEN"
      case _: net.sf.jsqlparser.expression.operators.relational.LikeExpression =>
        "LIKE or SIMILAR TO"
      case _: net.sf.jsqlparser.expression.TimezoneExpression => "AT TIME ZONE"
      case _: net.sf.jsqlparser.expression.IntervalExpression => "INTERVAL"
      case _: net.sf.jsqlparser.expression.TimeKeyExpression  =>
        "time keyword (quote a column of that name)"
      case _: net.sf.jsqlparser.expression.LambdaExpression   => "lambda"
      case _: net.sf.jsqlparser.statement.select.Select       => "subquery"
      case _: net.sf.jsqlparser.expression.AnalyticExpression =>
        "window or aggregate clause (OVER, FILTER)"
      case _: net.sf.jsqlparser.expression.operators.arithmetic.IntegerDivision =>
        "// (integer division)"
      case other =>
        val cls   = other.getClass.getSimpleName.replaceAll("Expression$", "")
        val words = cls.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT)
        s"$words expression"
