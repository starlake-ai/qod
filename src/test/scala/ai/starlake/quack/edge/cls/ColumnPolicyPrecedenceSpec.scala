package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Which column policy applies when several match one column (security review I1, owner ruling
  * D21): a deny wins; otherwise a policy naming the bound table beats a wildcard one; two different
  * masks left are refused. The order of the policies (a role's rows, the order the statement names
  * its tables, a decoy CTE the node never binds) must not decide it. And policies match their
  * tables and columns as DuckDB binds names, folding ASCII letters only (review M1, M2).
  */
class ColumnPolicyPrecedenceSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val Catalog = "acme_lake"
  private val user    = RbacUser("u-1", Some("acme"), "alice", "user")

  private val columns = new ColumnCatalog.MapCatalog(
    Map(
      (Catalog, "main", "users")    -> List("id", "email"),
      (Catalog, "main", "anything") -> List("x"),
      (Catalog, "s1", "Clés")       -> List("id", "secret"),
      (Catalog, "s2", "clÉs")       -> List("id", "secret"),
      (Catalog, "main", "Clés")     -> List("id", "secret"),
      (Catalog, "main", "clÉs")     -> List("id", "secret")
    )
  )
  private val rw  = new ColumnPolicyRewriter(columns)
  private val ctx = SchemaContext(Some(Catalog), Some("main"), attachedCatalogs = Set.empty)

  private def rewrite(sql: String, policies: List[RoleColumnPolicy]): Outcome =
    rw.rewrite(sql, StatementKind.Select, EffectiveSet(user, Nil, Nil, Nil, Nil, policies), ctx)
      .unsafeRunSync()

  private val wildcardMask =
    RoleColumnPolicy("cp-w", "r-1", "*", "main", "*", "email", "mask", Some("right(email, 4)"))
  private val usersDeny =
    RoleColumnPolicy("cp-d", "r-2", "*", "main", "users", "email", "deny", None)
  private val usersMask =
    RoleColumnPolicy("cp-m", "r-2", "*", "main", "users", "email", "mask", Some("'***'"))
  private val usersOtherMask =
    RoleColumnPolicy("cp-o", "r-3", "*", "main", "users", "email", "mask", Some("left(email, 2)"))

  private val Decoy   = "WITH w AS (SELECT 1 FROM main.anything) SELECT email FROM users"
  private val NoDecoy = "SELECT email FROM users"

  private def bothOrders(a: RoleColumnPolicy, b: RoleColumnPolicy) = List(List(a, b), List(b, a))

  "a deny and a wildcard mask on one column" should
    "refuse the column whatever the order and whatever CTE comes first" in
    List(Decoy, NoDecoy).foreach { sql =>
      bothOrders(wildcardMask, usersDeny).foreach { ps =>
        // The two policies come from two roles (r-1, r-2): an effective set holds both.
        withClue(s"$sql with ${ps.map(_.id)}") {
          rewrite(sql, ps) match
            case Denied(reason) => reason should include("users.email is denied")
            case other          => fail(s"expected Denied, got $other")
        }
      }
    }

  "a mask naming the table and a wildcard mask" should
    "apply the one naming the table whatever the order" in
    List(Decoy, NoDecoy).foreach { sql =>
      bothOrders(wildcardMask, usersMask).foreach { ps =>
        withClue(s"$sql with ${ps.map(_.id)}") {
          rewrite(sql, ps) match
            case Rewritten(out) =>
              out should include("'***'")
              out should not include "right(email, 4)"
            case other => fail(s"expected Rewritten, got $other")
        }
      }
    }

  "two different masks naming the table" should "refuse the statement as conflicting" in
    List(Decoy, NoDecoy).foreach { sql =>
      bothOrders(usersMask, usersOtherMask).foreach { ps =>
        withClue(s"$sql with ${ps.map(_.id)}")(
          rewrite(sql, ps) shouldBe Denied("conflicting column policies on users.email")
        )
      }
    }

  it should "refuse two different wildcard masks too, and a deny still wins over both" in {
    val otherWildcard = wildcardMask.copy(id = "cp-w2", transformSql = Some("'x'"))
    rewrite(NoDecoy, List(wildcardMask, otherWildcard)) shouldBe
      Denied("conflicting column policies on users.email")
    rewrite(NoDecoy, List(usersMask, usersOtherMask, usersDeny)) match
      case Denied(reason) => reason should include("users.email is denied")
      case other          => fail(s"expected Denied, got $other")
  }

  "the same mask from two roles" should "apply once, as before" in {
    rewrite(NoDecoy, List(usersMask, usersMask.copy(id = "cp-m2", roleId = "r-3"))) shouldBe
      Rewritten("SELECT '***' AS email FROM users")
  }

  "a policy on a derived table's column" should "follow the same precedence" in {
    val sql = "SELECT e FROM (SELECT email AS e FROM users) d"
    bothOrders(wildcardMask, usersDeny).foreach { ps =>
      rewrite(sql, ps) match
        case Denied(reason) => reason should include("is denied")
        case other          => fail(s"${ps.map(_.id)}: expected Denied, got $other")
    }
    bothOrders(usersMask, usersOtherMask).foreach { ps =>
      rewrite(sql, ps) match
        case Denied(reason) => reason should include("conflicting column policies")
        case other          => fail(s"${ps.map(_.id)}: expected Denied, got $other")
    }
  }

  // ---- policies match names as DuckDB binds them (review M1) --------------------------------

  "a policy on a table whose name differs from the read table's in a non-ASCII letter's case" should
    "not apply to it" in {
      val denyCles = RoleColumnPolicy("cp-a", "r-1", "*", "s1", "Clés", "secret", "deny", None)
      val maskClEs =
        RoleColumnPolicy(
          "cp-b",
          "r-1",
          "*",
          "s2",
          "clÉs",
          "secret",
          "mask",
          Some("left(secret, 4)")
        )
      val sql = "WITH w AS (SELECT 1 FROM s2.\"clÉs\") SELECT secret FROM s1.\"Clés\""
      bothOrders(denyCles, maskClEs).foreach { ps =>
        rewrite(sql, ps) match
          case Denied(reason) => reason should include("is denied")
          case other          => fail(s"${ps.map(_.id)}: expected Denied, got $other")
      }
      // Only the other table's mask: it does not reach "Clés".
      rewrite("SELECT secret FROM s1.\"Clés\"", List(maskClEs)) shouldBe Passthrough
      // A column policy spelled with another non-ASCII case of the column's letter does not match.
      val maskSecrÉt =
        RoleColumnPolicy("cp-c", "r-1", "*", "s1", "Clés", "SECRÉT", "mask", Some("'x'"))
      rewrite("SELECT secret FROM s1.\"Clés\"", List(maskSecrÉt)) shouldBe Passthrough
      // ASCII case still folds, as DuckDB does.
      val maskAscii = denyCles.copy(tableName = "CLéS", columnName = "SECRET")
      rewrite("SELECT secret FROM s1.\"Clés\"", List(maskAscii)) match
        case Denied(reason) => reason should include("is denied")
        case other          => fail(s"expected Denied, got $other")
    }

  // The resolver (jsqltranspiler) folds names with Java's case rules, so with both tables in one
  // statement it attributes a column of "clÉs" to "Clés": the other table's weaker policy must not
  // displace this one's deny.
  "a column of a table the resolver may confuse with another" should
    "never take the other table's weaker policy" in {
      val denyClEs = RoleColumnPolicy("cp-x", "r-1", "*", "main", "clÉs", "secret", "deny", None)
      val maskCles =
        RoleColumnPolicy("cp-y", "r-1", "*", "main", "Clés", "secret", "mask", Some("'***'"))
      val sql = "SELECT c.secret FROM \"clÉs\" c JOIN \"Clés\" d ON c.id = d.id"
      bothOrders(denyClEs, maskCles).foreach { ps =>
        rewrite(sql, ps) match
          case Denied(reason) => reason should include("is denied")
          case other          => fail(s"${ps.map(_.id)}: expected Denied, got $other")
      }
    }
