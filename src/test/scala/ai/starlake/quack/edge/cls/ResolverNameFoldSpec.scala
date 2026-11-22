package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The column rewriter's resolver (jsqltranspiler) keys tables, CTEs and columns by Java's
  * `String.toLowerCase()`, which maps `İ` (U+0130) to `i` + U+0307, the Kelvin sign to `k` and a
  * final capital sigma to `ς`; DuckDB folds ASCII letters only, so it holds each pair as two names
  * (security review round 2, R2-I1). Whatever the resolver names, a column is judged on the
  * policies covering its own DuckDB binding too, and the backstop refuses a column replaced by a
  * weaker policy than the one covering it (owner ruling D21: a deny wins, a named policy beats a
  * wildcard one).
  */
class ResolverNameFoldSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val Catalog = "acme_lake"
  private val user    = RbacUser("u-1", Some("acme"), "alice", "user")
  private val ctx     = SchemaContext(Some(Catalog), Some("main"), attachedCatalogs = Set.empty)

  private val Dotted  = "İ" // İ, which Java lowercases to i + U+0307
  private val Kelvin  = "K" // the Kelvin sign, which Java lowercases to k
  private val Sigma   = "Σ" // capital sigma, final ς at a word's end in Java's lowercase
  private val LongS   = "ſ" // the long s, which Java uppercases to S
  private val Dotless = "ı" // dotless i, which Java uppercases to I

  /** (the protected table's name, a CTE name Java folds alike or nearly so, DuckDB never). */
  private val tablePairs = List(
    s"${Dotted}x"  -> "i̇x",
    s"${Kelvin}x"  -> "kx",
    s"X$Sigma"     -> "xς",
    s"${LongS}x"   -> "sx",
    s"${Dotless}x" -> "ix",
    "ßx"           -> "ssx"
  )

  private def rw(tables: Map[String, List[String]]) =
    new ColumnPolicyRewriter(
      new ColumnCatalog.MapCatalog(tables.map((t, cols) => (Catalog, "main", t) -> cols))
    )

  private def rewrite(
      rewriter: ColumnPolicyRewriter,
      sql: String,
      policies: List[RoleColumnPolicy]
  ): Outcome =
    rewriter
      .rewrite(sql, StatementKind.Select, EffectiveSet(user, Nil, Nil, Nil, Nil, policies), ctx)
      .unsafeRunSync()

  private def q(name: String) = "\"" + name + "\""

  private def wildcardMask =
    RoleColumnPolicy("cp-w", "r-1", "*", "main", "*", "secret", "mask", Some("'***'"))

  private def bothOrders[A](a: A, b: A) = List(List(a, b), List(b, a))

  for (table, cte) <- tablePairs do
    s"a deny on table $table, a CTE named $cte beside it" should "win over a wildcard mask" in {
      val r    = rw(Map(table -> List("id", "secret")))
      val deny = RoleColumnPolicy("cp-d", "r-2", "*", "main", table, "secret", "deny", None)
      val sql  = s"WITH ${q(cte)} AS (SELECT 'cte' AS secret) SELECT c.secret FROM ${q(table)} c"
      bothOrders(deny, wildcardMask).foreach { ps =>
        withClue(s"$sql with ${ps.map(_.id)}") {
          rewrite(r, sql, ps) match
            case Denied(reason) => reason should include("denied")
            case other          => fail(s"expected Denied, got $other")
        }
      }
    }

  for (table, cte) <- tablePairs do
    s"a mask naming table $table, a CTE named $cte beside it" should "win over a wildcard mask" in {
      val r     = rw(Map(table -> List("id", "secret")))
      val named =
        RoleColumnPolicy("cp-n", "r-2", "*", "main", table, "secret", "mask", Some("'named'"))
      val sql = s"WITH ${q(cte)} AS (SELECT 'cte' AS secret) SELECT c.secret FROM ${q(table)} c"
      bothOrders(named, wildcardMask).foreach { ps =>
        withClue(s"$sql with ${ps.map(_.id)}") {
          rewrite(r, sql, ps) match
            case Rewritten(out) =>
              out should include("'named'")
              out should not include "'***'"
            case Denied(_) => () // refused, never served under the weaker mask
            case other     => fail(s"expected the named mask or a refusal, got $other")
        }
      }
    }

  for (denied, other) <- tablePairs do
    s"a deny on column $denied beside a column $other" should "win over that column's mask" in {
      val r    = rw(Map("t" -> List("id", denied, other)))
      val deny = RoleColumnPolicy("cp-d", "r-2", "*", "main", "t", denied, "deny", None)
      val mask = RoleColumnPolicy("cp-m", "r-1", "*", "main", "t", other, "mask", Some("'***'"))
      List(s"SELECT ${q(denied)} FROM t", s"SELECT t.${q(denied)} FROM t").foreach { sql =>
        bothOrders(deny, mask).foreach { ps =>
          withClue(s"$sql with ${ps.map(_.id)}") {
            rewrite(r, sql, ps) match
              case Denied(reason) => reason should include("denied")
              case other          => fail(s"expected Denied, got $other")
          }
        }
      }
    }

  // Review round 3, R3-M1: an unqualified column over two FROM items. The visitor's own binding
  // (`resolveTable`) is empty there, so only the backstop finds the deny on the table; this pins
  // that layer.
  for (table, cte) <- tablePairs do
    s"an unqualified column of table $table beside another FROM item and a CTE named $cte" should
      "be refused under a deny and a wildcard mask" in {
        val r    = rw(Map(table -> List("id", "secret"), "other" -> List("id")))
        val deny = RoleColumnPolicy("cp-d", "r-2", "*", "main", table, "secret", "deny", None)
        val sql  =
          s"WITH ${q(cte)} AS (SELECT 'cte' AS secret) SELECT secret FROM ${q(table)}, other"
        bothOrders(deny, wildcardMask).foreach { ps =>
          withClue(s"$sql with ${ps.map(_.id)}") {
            rewrite(r, sql, ps) shouldBe a[Denied]
          }
        }
      }
