package ai.starlake.quack.edge.cls

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PolicyExpressionAnalysisSpec extends AnyFlatSpec with Matchers:
  "the backstop" should "refuse a call left in method form, never a function's qualifier" in {
    def parsed(sql: String) = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql)
    PolicyExpressionAnalysis.methodCallLeft(
      parsed("SELECT ssn.max() FILTER (WHERE true) FROM kv")
    ) shouldBe
      defined
    // A scalar call in method form is left to the method normalizer, which judges its receiver.
    PolicyExpressionAnalysis.methodCallLeft(parsed("SELECT ssn.upper() FROM kv")) shouldBe None
    PolicyExpressionAnalysis.methodCallLeft(
      parsed("SELECT pg_catalog.max() FILTER (WHERE true) FROM kv AS pg_catalog")
    ) shouldBe defined
    PolicyExpressionAnalysis.methodCallLeft(
      parsed("SELECT main.max(ssn) FILTER (WHERE true) FROM kv")
    ) shouldBe
      None
    PolicyExpressionAnalysis.methodCallLeft(parsed("SELECT main.upper(ssn) FROM kv")) shouldBe None
    PolicyExpressionAnalysis.methodCallLeft(
      parsed("SELECT system.main.upper(ssn) FROM kv")
    ) shouldBe None
  }

  // ---- the argument check, call by call (m-r4) -------------------------------------------------

  "the argument check" should "compare each call with its own text" in {
    def check(sql: String) =
      PolicyExpressionAnalysis.droppedArgument(
        sql,
        List(net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql))
      )
    check("SELECT list(output ORDER BY id), list(separator, id) FROM w") shouldBe defined
    check("SELECT list(a, b),\n\tlist(\n  output ORDER BY id) FROM w") shouldBe defined
    check("SELECT list(a ORDER BY b, a),\n\tmax(\n b) FROM w") shouldBe None
  }

  // ---- named parameters and colons (m-r5) ------------------------------------------------------

  "the word quoting" should "quote after a separating colon and never after a parameter's" in {
    MisreadNames.quoteWords("SELECT {'a': key} FROM t") shouldBe "SELECT {'a': \"key\"} FROM t"
    MisreadNames.quoteWords("SELECT {a: minus} FROM t") shouldBe "SELECT {a: \"minus\"} FROM t"
    MisreadNames.quoteWords("SELECT list_transform(l, lambda x: key || x) FROM t") shouldBe
      "SELECT list_transform(l, lambda x: \"key\" || x) FROM t"
    MisreadNames.quoteWords("SELECT s[1:key] FROM t") shouldBe "SELECT s[1:\"key\"] FROM t"
    MisreadNames.quoteWords("SELECT s FROM t WHERE id = :key") shouldBe
      "SELECT s FROM t WHERE id = :key"
    MisreadNames.quoteWords("SELECT x::key FROM t") shouldBe "SELECT x::key FROM t"
    MisreadNames.quoteWords("SELECT $key") shouldBe "SELECT $key"
  }

  it should "leave DDL's own uses of the words alone" in
    List(
      "CREATE TABLE t (id INTEGER PRIMARY KEY, b INTEGER)",
      "CREATE TABLE t (id INTEGER, FOREIGN KEY (id) REFERENCES o (id))",
      "CREATE TABLE IF NOT EXISTS t (id INTEGER)",
      "DROP TABLE IF EXISTS t"
    ).foreach(sql => withClue(sql)(MisreadNames.quoteWords(sql) shouldBe sql))
