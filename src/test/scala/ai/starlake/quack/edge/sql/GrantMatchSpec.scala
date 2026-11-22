package ai.starlake.quack.edge.sql

import ai.starlake.acl.parser.Verb
import ai.starlake.quack.ondemand.state.RolePermission
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GrantMatchSpec extends AnyFlatSpec with Matchers:

  private def p(cat: String, sch: String, tab: String, verb: String) =
    RolePermission("p", "r", cat, sch, tab, verb)

  "verbCovers" should "map the canonical grant verbs onto the collapsed access verbs" in {
    val table = List(
      ("RO", Verb.Read, true),
      ("RO", Verb.Write, false),
      ("RO", Verb.Ddl, false),
      ("RW", Verb.Read, true),
      ("RW", Verb.Write, true),
      ("RW", Verb.Ddl, false),
      ("DDL", Verb.Read, false),
      ("DDL", Verb.Write, false),
      ("DDL", Verb.Ddl, true),
      ("ALL", Verb.Read, true),
      ("ALL", Verb.Write, true),
      ("ALL", Verb.Ddl, true),
      ("ro", Verb.Read, true),
      ("XX", Verb.Read, false)
    )
    table.foreach((g, v, expected) =>
      withClue(s"$g/$v")(GrantMatch.verbCovers(g, v) shouldBe expected)
    )
  }

  "catalogMatch" should "admit a wildcard only on the session's catalogs, a literal anywhere" in {
    GrantMatch.catalogMatch("*", "acme_lake", Set("acme_lake")) shouldBe true
    GrantMatch.catalogMatch("*", "ACME_LAKE", Set("acme_lake")) shouldBe true
    GrantMatch.catalogMatch("*", "globex_lake", Set("acme_lake")) shouldBe false
    GrantMatch.catalogMatch("*", "acme_lake", Set.empty) shouldBe false
    GrantMatch.catalogMatch("globex_lake", "GLOBEX_LAKE", Set.empty) shouldBe true
  }

  "covers" should "need the verb, the catalog, the schema and the table to match" in {
    val perms = List(p("acme_lake", "main", "orders", "RO"), p("acme_lake", "*", "*", "DDL"))
    val cats  = Set("acme_lake")
    GrantMatch.covers(perms, cats, "acme_lake", "main", "orders", Verb.Read) shouldBe true
    GrantMatch.covers(perms, cats, "acme_lake", "main", "orders", Verb.Write) shouldBe false
    GrantMatch.covers(perms, cats, "acme_lake", "main", "sales", Verb.Read) shouldBe false
    GrantMatch.covers(perms, cats, "acme_lake", "x", "y", Verb.Ddl) shouldBe true
    GrantMatch.covers(perms, cats, "other", "main", "orders", Verb.Read) shouldBe false
    GrantMatch.covers(Nil, cats, "acme_lake", "main", "orders", Verb.Read) shouldBe false
  }

  "coversSchema" should "need a grant whose table part is the wildcard" in {
    val cats = Set("acme_lake")
    GrantMatch.coversSchema(
      List(p("acme_lake", "staging", "*", "DDL")),
      cats,
      "acme_lake",
      "staging",
      Verb.Ddl
    ) shouldBe true
    GrantMatch.coversSchema(
      List(p("acme_lake", "staging", "t", "DDL")),
      cats,
      "acme_lake",
      "staging",
      Verb.Ddl
    ) shouldBe false
    GrantMatch.coversSchema(
      List(p("acme_lake", "staging", "*", "RO")),
      cats,
      "acme_lake",
      "staging",
      Verb.Ddl
    ) shouldBe false
    GrantMatch.coversSchema(
      List(p("*", "*", "*", "ALL")),
      cats,
      "acme_lake",
      "staging",
      Verb.Ddl
    ) shouldBe true
    GrantMatch.coversSchema(
      List(p("*", "*", "*", "ALL")),
      cats,
      "globex_lake",
      "staging",
      Verb.Ddl
    ) shouldBe false
  }

  "hasWildcardAll" should "hold only for an unrestricted *.*.* ALL" in {
    GrantMatch.hasWildcardAll(List(p("*", "*", "*", "ALL"))) shouldBe true
    GrantMatch.hasWildcardAll(List(p("*", "*", "*", "all"))) shouldBe true
    GrantMatch.hasWildcardAll(List(p("*", "*", "*", "RW"))) shouldBe false
    GrantMatch.hasWildcardAll(List(p("acme_lake", "*", "*", "ALL"))) shouldBe false
  }

  // DuckDB folds ASCII letters only: "Clés" and "clÉs" are two tables one schema can hold, and a
  // grant on one must not cover the other (security review C1 audit). ASCII case still folds.
  "covers" should "compare names as DuckDB does, folding ASCII letters only" in {
    val onCles = List(p("acme_lake", "main", "Clés", "RO"))
    def reads(cat: String, sch: String, tab: String, sessions: Set[String] = Set.empty) =
      GrantMatch.covers(onCles, sessions, cat, sch, tab, Verb.Read)
    reads("acme_lake", "main", "Clés") shouldBe true
    reads("ACME_LAKE", "MAIN", "CLéS") shouldBe true
    reads("acme_lake", "main", "clÉs") shouldBe false
    reads("acme_lake", "main", "CLÉS") shouldBe false
    // The Kelvin sign and the long s fold to ASCII letters in Java, never in DuckDB.
    val onSecret = List(p("acme_lake", "main", "secret", "RO"), p("*", "kpi", "*", "RO"))
    GrantMatch.covers(onSecret, Set.empty, "acme_lake", "main", "ſecret", Verb.Read) shouldBe
      false
    GrantMatch.covers(onSecret, Set("acme_lake"), "acme_lake", "Kpi", "t", Verb.Read) shouldBe
      false
    GrantMatch.covers(onSecret, Set("acme_lake"), "acme_lake", "KPI", "t", Verb.Read) shouldBe true
    GrantMatch.catalogMatch("*", "acme_lÁke", Set("acme_láke")) shouldBe false
    GrantMatch.catalogMatch("*", "ACME_láke", Set("acme_láke")) shouldBe true
  }
