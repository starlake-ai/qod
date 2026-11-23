package ai.starlake.quack.edge.policy

import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DuckDB's excerpt of a failed statement, as `QueryErrorContext::Format` writes it, is withheld
  * from principals with column or row policies, whose statement the gateway rewrote.
  */
class StatementExcerptSpec extends AnyFlatSpec with Matchers:

  private val head = "Binder Error: Referenced column \"nonexist\" not found in FROM clause!\n" +
    "Candidate bindings: \"s\""

  // As the DuckDB 2.0.0 CLI prints a bind error on a rewritten row-policy read.
  private val excerpt = "LINE 1: ... * FROM t AS __qod_rls WHERE s <> 'SECRETPRED') AS t WHERE " +
    "nonexist = 1;"
  private val carets = " " * 63 + "^^^^^^^^"

  "strip" should "drop the excerpt and its caret line, keeping the message before it" in {
    StatementExcerpt.strip(s"$head\n\n$excerpt\n$carets") shouldBe head
    StatementExcerpt.strip(s"$head\n\n$excerpt\n$carets\n") shouldBe s"$head\n"
  }

  it should "drop an excerpt on the message's own line" in {
    StatementExcerpt.strip(s"Parser Error: syntax error at or near \"x\" $excerpt") shouldBe
      "Parser Error: syntax error at or near \"x\""
    StatementExcerpt.strip(excerpt) shouldBe ""
  }

  it should "drop an excerpt with no caret line after it" in {
    StatementExcerpt.strip(s"$head\n\n$excerpt") shouldBe head
  }

  it should "keep what follows the caret line" in {
    StatementExcerpt.strip(s"$head\n\n$excerpt\n$carets\nmore text") shouldBe s"$head\nmore text"
  }

  it should "drop every excerpt of a message holding several" in {
    val two = s"$head\n\n$excerpt\n$carets\nand then\n\nLINE 2: SELECT 'SECRETPRED'\n       ^"
    val out = StatementExcerpt.strip(two)
    out shouldBe s"$head\nand then"
    out should not include "SECRETPRED"
  }

  it should "leave a message with no excerpt as it is" in {
    StatementExcerpt.strip(head) shouldBe head
    StatementExcerpt.strip("") shouldBe ""
  }

  it should "leave the word LINE elsewhere in the message alone" in {
    val m = "Catalog Error: Table with name LINE does not exist! Did you mean \"LINES\"?\n" +
      "PIPELINE 3: is not an excerpt"
    StatementExcerpt.strip(m) shouldBe m
    StatementExcerpt.strip("Binder Error: column LINE1: not found") shouldBe
      "Binder Error: column LINE1: not found"
  }

  // As the DuckDB 2.0.0 CLI answers a bind error under `SET errors_as_json = true`.
  private val jsonError =
    """{"exception_type":"Binder","exception_message":"Referenced column \"nonexist\" not """ +
      """found in FROM clause!\nCandidate bindings: \"email\"","error_subtype":""" +
      """"COLUMN_NOT_FOUND","location":"[97,8]","position":"97","name":"nonexist"}"""
  private val jsonStripped =
    """{"exception_type":"Binder","exception_message":"Referenced column \"nonexist\" not """ +
      """found in FROM clause!\nCandidate bindings: \"email\"","error_subtype":""" +
      """"COLUMN_NOT_FOUND","name":"nonexist"}"""

  it should "drop a JSON error's position and location, keeping every other member" in {
    StatementExcerpt.strip(jsonError) shouldBe jsonStripped
    StatementExcerpt.strip(s"Binder Error: $jsonError") shouldBe s"Binder Error: $jsonStripped"
    StatementExcerpt.strip(
      """{"exception_type":"Parser","exception_message":"syntax error at or near \"1\"",""" +
        """"location":"[33,1]","position":"33","error_subtype":"SYNTAX_ERROR"}"""
    ) shouldBe
      """{"exception_type":"Parser","exception_message":"syntax error at or near \"1\"",""" +
      """"error_subtype":"SYNTAX_ERROR"}"""
    StatementExcerpt.strip("""{"position":"3","location":"[3,1]","exception_type":"X"}""") shouldBe
      """{"exception_type":"X"}"""
    // an excerpt after the JSON, as a client that adds one would write it
    StatementExcerpt.strip(s"$jsonError\n\n$excerpt\n$carets") shouldBe jsonStripped
  }

  it should "leave position-like text inside a JSON string, and plain text, alone" in {
    val inString =
      """{"exception_type":"Binder","exception_message":"column \"a,\"position\":\"5\"\" """ +
        """not found"}"""
    StatementExcerpt.strip(inString) shouldBe inString
    val plain = "Binder Error: no position 97 at location [97,8]"
    StatementExcerpt.strip(plain) shouldBe plain
  }

  "withheldFor" should "hold for a column or row policy holder only" in {
    val user  = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
    val plain = EffectiveSet(user, Nil, Nil, Nil, Nil)
    val mask  = RoleColumnPolicy("cp-1", "r-1", "*", "*", "t", "c", "mask", Some("'***'"))
    val row   = RoleRowPolicy("rp-1", "r-1", "*", "*", "t", "region = 'eu'")
    StatementExcerpt.withheldFor(Some(plain.copy(columnPolicies = List(mask)))) shouldBe true
    StatementExcerpt.withheldFor(Some(plain.copy(rowPolicies = List(row)))) shouldBe true
    StatementExcerpt.withheldFor(Some(plain)) shouldBe false
    StatementExcerpt.withheldFor(None) shouldBe false
    val superuser = RbacUser(id = "u-0", tenant = None, username = "admin", kind = "admin")
    StatementExcerpt.withheldFor(
      Some(EffectiveSet(superuser, Nil, Nil, Nil, Nil, List(mask), List(row)))
    ) shouldBe false
    StatementExcerpt.withheldFor(
      Some(plain.copy(rowPolicies = List(row), system = true))
    ) shouldBe false
  }

  "shownTo" should "strip only when withheld" in {
    val m = s"$head\n\n$excerpt\n$carets"
    StatementExcerpt.shownTo(withheld = true, m) shouldBe head
    StatementExcerpt.shownTo(withheld = false, m) shouldBe m
  }
