package ai.starlake.quack.module

import ai.starlake.quack.ondemand.module.ModuleMigrations
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

class ModuleMigrationsSpec extends AnyFlatSpec with Matchers:

  private def moduleWithChangelog(path: Option[String]): TestModule =
    new TestModule:
      override def changelogPath: Option[String] = path

  "ModuleMigrations.run" should "apply each module changelog and skip None" in {
    val applied = ListBuffer.empty[String]
    ModuleMigrations.run(
      List(
        moduleWithChangelog(Some("db/hosted/changelog.yaml")),
        moduleWithChangelog(None)
      ),
      Map("pgHost" -> "h"),
      runnerFor = (_, path) => () => { applied += path; () }
    )
    applied.toList shouldBe List("db/hosted/changelog.yaml")
  }

  it should "propagate a failing migration (fail-fast boot)" in {
    an[RuntimeException] should be thrownBy ModuleMigrations.run(
      List(moduleWithChangelog(Some("db/hosted/changelog.yaml"))),
      Map.empty,
      runnerFor = (_, _) => () => throw new RuntimeException("migration failed")
    )
  }

  "ModuleMigrations.prepare" should "hand every module the control plane and config, in order" in {
    val seen   = ListBuffer.empty[String]
    val ds     = new org.postgresql.ds.PGSimpleDataSource()
    val config = com.typesafe.config.ConfigFactory.parseString("a = 1")
    def module(n: String): TestModule =
      new TestModule:
        override def name: String = n
        override def prepare(
            controlPlane: javax.sql.DataSource,
            cfg: com.typesafe.config.Config
        ): cats.effect.IO[Unit] =
          cats.effect.IO { seen += s"$n:${controlPlane eq ds}:${cfg.getInt("a")}"; () }
    ModuleMigrations.prepare(List(module("one"), module("two")), ds, config)
    seen.toList shouldBe List("one:true:1", "two:true:1")
  }

  it should "stop the boot naming the module whose prepare fails" in {
    val failing = new TestModule:
      override def name: String = "broken"
      override def prepare(
          controlPlane: javax.sql.DataSource,
          cfg: com.typesafe.config.Config
      ): cats.effect.IO[Unit] = cats.effect.IO.raiseError(new RuntimeException("no table"))
    val e = intercept[RuntimeException](
      ModuleMigrations.prepare(
        List(failing),
        new org.postgresql.ds.PGSimpleDataSource(),
        com.typesafe.config.ConfigFactory.empty()
      )
    )
    e.getMessage should include("module broken: prepare failed")
  }
