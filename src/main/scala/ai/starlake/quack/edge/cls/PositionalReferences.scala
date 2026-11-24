package ai.starlake.quack.edge.cls

import scala.util.Try

private[edge] object PositionalReferences:

  private val PositionalRe = """^#(\d+)$""".r

  /** The 1-based position a column named `#n` stands for, or None for any other name; a `#n` whose
    * number does not fit an Int reads as position 0, which no column has.
    */
  def position(columnName: String): Option[Int] =
    columnName match
      case PositionalRe(digits) => Some(Try(digits.toInt).getOrElse(0))
      case _                    => None

  /** `#n` outside single-quoted literals and quoted identifiers (`'#1'` is text, `"#1"` a name). */
  private val TextualRe = """(?<!["\w])#\d+""".r
  private val LiteralRe = """'(?:[^']|'')*'""".r

  def textualCount(sql: String): Int =
    TextualRe.findAllMatchIn(LiteralRe.replaceAllIn(sql, "''")).size
