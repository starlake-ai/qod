package ai.starlake.quack.edge.rls

import ai.starlake.acl.parser.TableExtractor
import net.sf.jsqlparser.expression.{Expression, MapExpression, StringValue}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.{PlainSelect, Select}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The row-policy rewrite wraps a reference only where DuckDB binds the table. Where the extractor
  * cannot say (a reference it never reached, a WITH item it could not scope), the statement is
  * refused. No statement JSqlParser parses is known to reach either case, so they are pinned on the
  * decision itself and on hand-built syntax trees.
  */
class RowPolicyScopeRefusalSpec extends AnyFlatSpec with Matchers:
  import RowPolicyRewriter._

  private def select(sql: String): Select =
    CCJSqlParserUtil.parse(sql).asInstanceOf[Select]

  private def fromTable(s: Select): Table =
    s.asInstanceOf[PlainSelect].getFromItem.asInstanceOf[Table]

  "a wrap of a reference the extractor reached" should "not be refused" in {
    val stmt    = select("SELECT * FROM secret")
    val scoping = TableExtractor.extractWithCteReferences(stmt)
    scoping.scopedEverywhere shouldBe true
    scopeRefusal(List(fromTable(stmt)), scoping, changed = true) shouldBe None
  }

  "a wrap of a reference the extractor never reached" should "be refused" in {
    val scoping = TableExtractor.extractWithCteReferences(select("SELECT * FROM secret"))
    scopeRefusal(List(new Table("secret")), scoping, changed = true) shouldBe
      Some(UnscopedRefusal)
  }

  /** `SELECT MAP {'k': <a WITH item>}`: a WITH item the walk meets as a child of another node, with
    * no clause to scope it in.
    */
  private def withItemAsMapValue: Select =
    val holder  = select("WITH w AS (SELECT * FROM secret) SELECT 1")
    val withItm = holder.getWithItemsList.get(0)
    val entries = new java.util.ArrayList[java.util.Map.Entry[AnyRef, AnyRef]]()
    entries.add(
      new java.util.AbstractMap.SimpleEntry[AnyRef, AnyRef](new StringValue("k"), withItm)
    )
    val map = new MapExpression()
    map.setEntries(
      entries.asInstanceOf[java.util.List[java.util.Map.Entry[Expression, Expression]]]
    )
    val out = new PlainSelect()
    out.addSelectItem(map)
    out.setFromItem(new Table("t"))
    out

  "a WITH item the extractor could not scope" should "mark the scoping incomplete" in {
    val stmt    = withItemAsMapValue
    val scoping = TableExtractor.extractWithCteReferences(stmt)
    scoping.scopedEverywhere shouldBe false
    scoping.extraction.tables.map(_.getName) should contain("secret")
  }

  it should "refuse a statement that wrapped anything" in {
    val stmt    = withItemAsMapValue
    val scoping = TableExtractor.extractWithCteReferences(stmt)
    val reached = scoping.extraction.tables.find(_.getName == "secret").toList
    scopeRefusal(reached, scoping, changed = true) shouldBe Some(UnscopedRefusal)
    scopeRefusal(Nil, scoping, changed = false) shouldBe None
  }
