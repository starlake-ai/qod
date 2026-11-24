package ai.starlake.quack.edge.rls

/** Literal-aware validation shared by row predicates and column transforms. Row predicates are
  * independently parsed and bound on the node; column transforms still use gateway rewriting.
  */
private[edge] object SqlCommentScan:

  /** True if `sql` contains a line comment or a block comment marker outside any string literal or
    * quoted identifier.
    */
  def hasComment(sql: String): Boolean = scan(sql).comment

  /** True if `sql` contains a bare `;` outside any string literal or quoted identifier. */
  def hasSemicolon(sql: String): Boolean = scan(sql).semicolon

  private final case class Findings(comment: Boolean, semicolon: Boolean)

  private def scan(sql: String): Findings =
    var inStr     = false
    var inQuote   = false
    var comment   = false
    var semicolon = false
    var j         = 0
    val n         = sql.length
    while j < n do
      val c = sql.charAt(j)
      if inStr then
        if c == '\'' then if j + 1 < n && sql.charAt(j + 1) == '\'' then j += 1 else inStr = false
        j += 1
      else if inQuote then
        if c == '"' then inQuote = false
        j += 1
      else if c == '\'' then { inStr = true; j += 1 }
      else if c == '"' then { inQuote = true; j += 1 }
      else if c == '-' && j + 1 < n && sql.charAt(j + 1) == '-' then { comment = true; j += 1 }
      else if c == '/' && j + 1 < n && sql.charAt(j + 1) == '*' then { comment = true; j += 1 }
      else if c == ';' then { semicolon = true; j += 1 }
      else j += 1
    Findings(comment, semicolon)
