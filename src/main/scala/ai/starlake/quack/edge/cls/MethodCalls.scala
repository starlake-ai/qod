package ai.starlake.quack.edge.cls

import ai.starlake.sql.{IdentifierFold, QuotedIdentifier, SqlLexer}

/** DuckDB's method-call syntax read as DuckDB reads it (owner ruling D28, review round 4).
  *
  * DuckDB 2.0 reads `<expr>.f(args)` as `f(<expr>, args)` once `<expr>` is not a schema or catalog
  * qualifier of a function named `f`: a column, a qualified column or struct field (`t.c.f()`), a
  * call (`x.g().f()`, so chains), a subscript, a parenthesized expression, a list or struct literal
  * or a string. The method binds tighter than any operator (`-n.abs()` is `-abs(n)`, `j->'a'.f()`
  * is `j->f('a')`). JSqlParser holds such a call as a function or analytic call with a dotted name
  * and no column node, so a policy step never saw the column. This pass, on the token stream,
  * writes each method call as the plain call DuckDB makes of it, so every later step sees plain
  * functions and columns, and reports a method call it cannot write so with certainty.
  */
private[quack] object MethodCalls:

  /** The text with every method call desugared, and the refusal of one that could not be. */
  final case class Desugared(text: String, refusal: Option[String])

  /** A function's qualifier, never a method's receiver: `main.f(`, `pg_catalog.f(`,
    * `information_schema.f(`, `system.main.f(`, `temp.main.f(` (DuckDB looks the function up there
    * first and finds it).
    */
  private val Schemas  = Set("main", "pg_catalog", "information_schema")
  private val Catalogs = Set("system", "temp")

  def isQualifier(parts: List[String]): Boolean =
    parts.map(p => IdentifierFold.fold(QuotedIdentifier.unquote(p))) match
      case s :: Nil      => Schemas.contains(s)
      case c :: s :: Nil => Catalogs.contains(c) && Schemas.contains(s)
      case _             => false

  /** Words before a `<name>.<name>(` that make it a qualified object name, not an expression: a
    * table, a table function, a view, a macro, a statement target. `on` only before a one-part head
    * (`CREATE INDEX i ON s.t (c)`), as a join condition is an expression.
    */
  private val ObjectContext = Set(
    "into",
    "table",
    "from",
    "join",
    "update",
    "view",
    "references",
    "lateral",
    "function",
    "macro",
    "call",
    "pragma",
    "copy",
    "exists",
    "type",
    "sequence",
    "index",
    "schema"
  )

  /** DuckDB's reserved keywords (`duckdb_keywords()`, category `reserved`): never a receiver name
    * (`CASE .. END.f()` ends with one).
    */
  private val Reserved = Set(
    "all",
    "analyse",
    "analyze",
    "and",
    "any",
    "array",
    "as",
    "asc",
    "asymmetric",
    "both",
    "case",
    "cast",
    "check",
    "collate",
    "column",
    "constraint",
    "create",
    "default",
    "deferrable",
    "desc",
    "describe",
    "distinct",
    "do",
    "else",
    "end",
    "except",
    "false",
    "fetch",
    "for",
    "foreign",
    "from",
    "group",
    "having",
    "in",
    "initially",
    "intersect",
    "into",
    "lambda",
    "lateral",
    "leading",
    "limit",
    "not",
    "null",
    "offset",
    "on",
    "only",
    "or",
    "order",
    "pivot",
    "pivot_longer",
    "pivot_wider",
    "placing",
    "primary",
    "qualify",
    "references",
    "returning",
    "select",
    "show",
    "some",
    "summarize",
    "symmetric",
    "table",
    "then",
    "to",
    "trailing",
    "true",
    "union",
    "unique",
    "unpivot",
    "using",
    "variadic",
    "when",
    "where",
    "window",
    "with"
  )

  /** Words that follow a call and change what it is (an aggregate's filter, a window, an ordered
    * set): the method form cannot be moved with certainty.
    */
  private val Suffixes = Set("filter", "over", "within")

  /** Words whose parenthesized group is a list or a clause, never a receiver of its own. */
  private val NotACallee =
    Set(
      "in",
      "any",
      "all",
      "some",
      "exists",
      "values",
      "filter",
      "over",
      "within",
      "group",
      "using"
    )

  private val MaxRewrites = 10000
  private val Openers     = Set("(", "[", "{")
  private val Closers     = Set(")", "]", "}")

  def desugar(sql: String): Desugared =
    var text    = sql
    var refusal = Option.empty[String]
    var done    = false
    var rounds  = 0
    var from    = 0 // tokens before this index hold no method call left to rewrite
    while !done && refusal.isEmpty do
      rounds += 1
      if rounds > MaxRewrites then refusal = Some(Refusal("too many method calls"))
      else
        step(text, from) match
          case Step.None               => done = true
          case Step.Refuse(reason)     => refusal = Some(Refusal(reason))
          case Step.Skip(next)         => from = next
          case Step.Rewrite(rewritten) => text = rewritten; from = 0
    Desugared(text, refusal)

  def Refusal(reason: String): String =
    s"a method call ($reason) cannot be read under column policies; write it as a function call"

  private enum Step:
    case None
    case Refuse(reason: String)
    case Skip(nextFrom: Int)
    case Rewrite(text: String)

  private def step(text: String, from: Int): Step =
    val view                    = SqlLexer.engineView(text)
    val solid                   = SqlLexer.tokens(text).filterNot(_.isTrivia)
    def at(i: Int): String      = view.substring(solid(i).start, solid(i).end)
    def word(i: Int): String    = IdentifierFold.fold(at(i))
    def isName(i: Int): Boolean =
      i >= 0 && i < solid.size &&
        (solid(i).kind == SqlLexer.Kind.Word ||
          (solid(i).kind == SqlLexer.Kind.Literal && at(i).startsWith("\"")))
    def isWord(i: Int): Boolean = i >= 0 && i < solid.size && solid(i).kind == SqlLexer.Kind.Word
    // The bracket matching the one at `i`, scanning forward from an opener or back from a closer.
    def matching(i: Int, forward: Boolean): Option[Int] =
      val (opens, closes) = if forward then (Openers, Closers) else (Closers, Openers)
      var depth           = 0
      var j               = i
      var found           = Option.empty[Int]
      while found.isEmpty && j >= 0 && j < solid.size do
        if opens.contains(at(j)) then depth += 1
        else if closes.contains(at(j)) then
          depth -= 1
          if depth == 0 then found = Some(j)
        j += (if forward then 1 else -1)
      found
    val dot = (from until solid.size).find(k =>
      at(k) == "." && isName(k + 1) && k + 2 < solid.size && at(k + 2) == "(" && k > 0
    )
    dot match
      case scala.None => Step.None
      case Some(k)    =>
        receiverStart(k, at, word, isName, isWord, matching) match
          case Left(Some(reason)) => Step.Refuse(reason)
          case Left(scala.None)   => Step.Skip(k + 1)
          case Right(r)           =>
            matching(k + 2, forward = true) match
              case scala.None  => Step.Refuse("unbalanced parentheses")
              case Some(close) =>
                if isWord(close + 1) && Suffixes.contains(word(close + 1)) then
                  Step.Refuse(s"followed by ${word(close + 1).toUpperCase}")
                else
                  val receiver = text.substring(solid(r).start, solid(k - 1).end)
                  val name     = text.substring(solid(k + 1).start, solid(k + 1).end)
                  val args     = text.substring(solid(k + 2).end, solid(close).start).trim
                  val firstArg = (k + 3 until close).headOption
                  if firstArg.exists(i => isWord(i) && Set("distinct", "all").contains(word(i)))
                  then Step.Refuse("with DISTINCT or ALL")
                  else
                    val inner =
                      if args.isEmpty then receiver
                      else if firstArg.exists(i =>
                          isWord(i) && word(i) == "order" && isWord(i + 1) && word(i + 1) == "by"
                        )
                      then s"$receiver $args"
                      else s"$receiver, $args"
                    Step.Rewrite(
                      text.substring(0, solid(r).start) + s"$name($inner)" +
                        text.substring(solid(close).end)
                    )

  /** The first token of the receiver of the method call whose dot is token `k`: Left(None) when the
    * dotted name is a function's qualifier or an object name, Left(Some(reason)) when the receiver
    * cannot be told with certainty.
    */
  private def receiverStart(
      k: Int,
      at: Int => String,
      word: Int => String,
      isName: Int => Boolean,
      isWord: Int => Boolean,
      matching: (Int, Boolean) => Option[Int]
  ): Either[Option[String], Int] =
    // The postfix chain before the dot: subscripts and a call close over their head, which ends at a
    // name chain, a literal or a bracket group.
    def headOf(end: Int): Either[Option[String], Int] =
      val s = at(end)
      if s == ")" || s == "]" || s == "}" then
        matching(end, false) match
          case scala.None => Left(Some("unbalanced brackets"))
          case Some(open) =>
            val before = open - 1
            if s == ")" && isName(before) then
              val w = if isWord(before) then word(before) else ""
              // A group a keyword opens is no call: a parenthesized expression after SELECT, WHERE,
              // AND, ...; after IN, ANY, a window or a filter it is a list or a clause.
              if NotACallee.contains(w) then Left(Some(s"after ${w.toUpperCase} (..)"))
              else if Reserved.contains(w) && w != "cast" then Right(open)
              else nameChain(before).map(_._1)
            else if s == "]" && before >= 0 &&
              ((isName(before) && !(isWord(before) && Reserved.contains(word(before)))) ||
                Set(")", "]").contains(at(before)))
            then headOf(before) // a subscript
            else if s == "]" && isWord(before) && word(before) == "array" then
              Left(Some("on ARRAY[..]"))
            else Right(open)
      else if isName(end) then
        if isWord(end) && Reserved.contains(word(end)) then
          Left(Some(s"on ${word(end).toUpperCase}"))
        else
          nameChain(end).flatMap { (start, parts) =>
            if parts.size > 2 then Left(Some("on a name of more than two parts"))
            else if isQualifier(parts) then Left(scala.None)
            else
              val prev = start - 1
              if prev >= 0 && at(prev) == "::" then Left(Some("after a cast"))
              else if isWord(prev) && ObjectContext.contains(word(prev)) then Left(scala.None)
              else if isWord(prev) && word(prev) == "on" && parts.size == 1 then Left(scala.None)
              else Right(start)
          }
      else if at(end).startsWith("'") || at(end).toLowerCase.startsWith("e'") then
        if isWord(end - 1) && !Reserved.contains(word(end - 1)) then
          Left(Some("on a typed literal"))
        else Right(end)
      else Left(Some("on this receiver"))
    // A name chain `a.b.c` ending at `end`: its first token and its parts.
    def nameChain(end: Int): Either[Option[String], (Int, List[String])] =
      var start = end
      while start - 2 >= 0 && at(start - 1) == "." && isName(start - 2) do start -= 2
      if start - 1 >= 0 && at(start - 1) == "." then Left(Some("on an expression it cannot place"))
      else Right(start -> (start to end by 2).map(at).toList)
    headOf(k - 1)
