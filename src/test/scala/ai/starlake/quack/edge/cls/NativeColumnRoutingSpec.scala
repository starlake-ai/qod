package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.policy.PolicyPipelineFixture
import ai.starlake.quack.ondemand.state.RoleColumnPolicy
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NativeColumnRoutingSpec extends AnyFlatSpec with Matchers:
  "native CLS routing" should "send caller SQL without a gateway mask or normalization" in {
    val catalog = new ColumnCatalog.MapCatalog(
      Map(("memory", "main", "docs") -> List("id", "secret"))
    )
    val harness = PolicyPipelineFixture.harness(
      PolicyPipelineFixture.Memory,
      columnCatalog = catalog,
      guarded = true,
      nativeCls = true
    )
    val effective = PolicyPipelineFixture.effWith(columnPolicies =
      List(
        RoleColumnPolicy(
          "cp",
          "r",
          "memory",
          "main",
          "docs",
          "secret",
          "mask",
          Some("'private-policy'")
        )
      )
    )
    val sql  = "SELECT secret, id // 2 FROM docs"
    val sent = harness.run(sql, effective).toOption.get
    sent shouldBe s"USE memory.main; $sql"
    sent should not include "private-policy"
  }
