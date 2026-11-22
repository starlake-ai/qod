package ai.starlake.quack.edge.cls

import ai.starlake.sql.IdentifierFold
import net.sf.jsqlparser.expression.TimeKeyExpression

/** How DuckDB 2.0 binds a bare word that SQL also uses as a value keyword (owner ruling D23).
  *
  * DuckDB's binder falls back to a SQL value function (`GetSQLValueFunction`) for an unqualified
  * column reference of one of [[Names]], quoted or not, that no binding of the select's own FROM
  * resolves: the column (or the whole row of a FROM binding of that name) when the select's own
  * FROM has it, the function otherwise, and never a column of an enclosing query.
  *
  * JSqlParser reads three of those names (`current_date`, `current_time`, `current_timestamp`), and
  * also `current` and `current_timezone`, which DuckDB reads as plain identifiers, as a
  * `TimeKeyExpression` instead of a column, in any case. `current <word>` is one such expression
  * too, where DuckDB reads the column `current` under the alias `<word>`.
  */
private[cls] object SqlValueNames:

  val Names: Set[String] = Set(
    "current_catalog",
    "current_date",
    "current_role",
    "current_schema",
    "current_time",
    "current_timestamp",
    "current_user",
    "localtime",
    "localtimestamp",
    "session_user",
    "user"
  )

  /** Whether an unqualified reference to `name` binds as one of [[Names]]: in its own FROM only. */
  def isName(name: String): Boolean = Names.contains(IdentifierFold.fold(name))

  /** What DuckDB makes of the text JSqlParser held as one time keyword. */
  enum Reading:
    /** A column reference named `name` (as written), bound as [[Names]] bind when [[isName]]. */
    case Reference(name: String)

    /** The column `current` (as written) followed by its alias (`current date`). */
    case Aliased(name: String, rest: String)

    /** A call with parentheses (`current_date()`): never a column. */
    case Call

  private val Bare    = "(?i)current(?:_date|_timestamp|_timezone|_time)?".r
  private val Spaced  = "(?is)(current)(\\s+\\S.*)".r
  private val Quoting = "\""

  def reading(tk: TimeKeyExpression): Reading =
    Option(tk.getStringValue).map(_.trim).getOrElse("") match
      case word @ Bare()      => Reading.Reference(word)
      case Spaced(word, rest) => Reading.Aliased(word, rest)
      case _                  => Reading.Call

  /** The call DuckDB makes of a SQL value name that binds no column, None for a name that is not a
    * time keyword's: written so, the function binds no column wherever it is spliced.
    */
  def functionCall(name: String): Option[String] =
    IdentifierFold.fold(name) match
      case "current_date"      => Some("current_date()")
      case "current_time"      => Some("get_current_time()")
      case "current_timestamp" => Some("get_current_timestamp()")
      case _                   => None

  /** Writes the time keyword as the quoted column reference DuckDB reads it as, so that JSqlParser
    * parses it as a column when the statement is printed and read again. DuckDB binds a quoted name
    * of [[Names]] exactly as the bare one, so the statement means what it meant. False for a call.
    */
  def quote(tk: TimeKeyExpression): Boolean =
    reading(tk) match
      case Reading.Reference(name)     => tk.setStringValue(Quoting + name + Quoting); true
      case Reading.Aliased(name, rest) => tk.setStringValue(Quoting + name + Quoting + rest); true
      case Reading.Call                => false
