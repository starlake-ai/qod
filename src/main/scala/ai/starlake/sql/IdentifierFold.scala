package ai.starlake.sql

/** How DuckDB compares two identifiers: case-insensitively for the ASCII letters `A`-`Z` only,
  * every other character as it is (`StringUtil::Lower` / `CIEquals`, used by every catalog, CTE,
  * alias and column binding). `"Clés"` and `"clÉs"` are two names to DuckDB: one schema can hold a
  * table of each, and a CTE named `"clÉs"` does not shadow the table `"Clés"` (checked on the 2.0.0
  * CLI). Java's `toLowerCase(Locale.ROOT)` and `equalsIgnoreCase` fold every cased letter (and map
  * the Kelvin sign to `k`, the long s to `s`, the dotless i to `i`), so a step that compares names
  * with them reads such a pair as one name: a policy step took a table reference for a CTE and
  * passed its masked columns raw (security review C1), and a grant on one table covered the other.
  *
  * Every comparison of a name the gateway or the node decider reads against what DuckDB resolved (a
  * CTE name, an alias, a table or column binding, a grant's or a column or row policy's name) goes
  * through this fold. A broader match is not safe for policies either: a policy covering one more
  * table can displace that table's own deny or stronger mask, or OR a row filter written for
  * another table into its own (security review I1, M1, M2; owner ruling D21).
  */
private[starlake] object IdentifierFold:

  /** `s` with `A`-`Z` turned to `a`-`z` and nothing else changed; null stays null. */
  def fold(s: String): String =
    if s == null then s
    else
      var i = 0
      while i < s.length && !isUpperAscii(s.charAt(i)) do i += 1
      if i == s.length then s
      else
        val out = s.toCharArray
        while i < out.length do
          val c = out(i)
          if isUpperAscii(c) then out(i) = (c + ('a' - 'A')).toChar
          i += 1
        new String(out)

  /** Whether DuckDB reads `a` and `b` as one identifier. */
  def same(a: String, b: String): Boolean =
    if a == null || b == null then a == b
    else a.length == b.length && fold(a) == fold(b)

  private def isUpperAscii(c: Char): Boolean = c >= 'A' && c <= 'Z'
