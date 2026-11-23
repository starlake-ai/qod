package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A stored mask is applied only when the parser reads all of it, at rewrite time too. A manifest
  * import stores a mask as written, unchecked, and JSqlParser answers the expression before a token
  * it cannot read (`email => 1` reads as `email`) and takes `//` for a comment, so a stored mask
  * read in part applied as the bare column. Such a mask now refuses every read of its column.
  */
class StoredMaskWholeSpec extends AnyFlatSpec with Matchers:

  private val user = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
  private val cat  = new ColumnCatalog.MapCatalog(
    Map(("acme_tpch", "tpch1", "t") -> List("id", "email"))
  )
  private val rw  = new ColumnPolicyRewriter(cat, enabled = true)
  private val ctx = SchemaContext(
    defaultDatabase = Some("acme_tpch"),
    defaultSchema = Some("tpch1"),
    attachedCatalogs = Set.empty
  )

  private def eff(mask: String): EffectiveSet =
    EffectiveSet(
      user,
      Nil,
      Nil,
      Nil,
      Nil,
      List(RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "t", "email", "mask", Some(mask)))
    )

  private def rewrite(mask: String): ColumnPolicyRewriter.Outcome =
    rw.rewrite("SELECT id, email FROM t", StatementKind.Select, eff(mask), ctx).unsafeRunSync()

  /** Stored masks the parser reads only in part (imported, never validated). */
  private val ReadInPart = List(
    "email // 1000",
    "email => 1",
    "email := 1",
    "md5(email::VARCHAR) || '' email",
    "email # 1",
    "email x"
  )

  "a stored mask read only in part" should "refuse the read of its column" in
    ReadInPart.foreach { mask =>
      withClue(mask) {
        rewrite(mask) match
          case ColumnPolicyRewriter.Denied(reason) =>
            reason should include("does not read whole")
            reason should not include mask
          case other => fail(s"applied as $other")
      }
    }

  "a stored mask read whole" should "still apply" in {
    rewrite("md5(CAST(email AS VARCHAR))") match
      case ColumnPolicyRewriter.Rewritten(sql) =>
        sql should include("md5(CAST(")
        sql should not include "email AS email"
      case other => fail(s"got $other")
  }
