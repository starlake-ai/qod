package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins the `telemetry.auditFile` block of `QOD_AUDIT_SINK=file` (issue #196). */
class AuditFileConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  private def load(env: Map[String, String] = Map.empty): ManagerConfig =
    val conf = ConfigFactory
      .parseString(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-on-demand").loadOrThrow[ManagerConfig]

  "telemetry.auditFile" should "ship the documented defaults" in {
    load().telemetry.auditFile shouldBe AuditFileConfig()
    AuditFileConfig() shouldBe AuditFileConfig("", 104857600L, 10, 1, false)
  }

  it should "read every setting from its variable" in {
    val t = load(
      Map(
        "QOD_AUDIT_SINK"                    -> "file",
        "QOD_AUDIT_FILE_PATH"               -> "/var/log/qod/audit.ndjson",
        "QOD_AUDIT_FILE_MAX_BYTES"          -> "2097152",
        "QOD_AUDIT_FILE_MAX_FILES"          -> "3",
        "QOD_AUDIT_FILE_FSYNC_INTERVAL_SEC" -> "0",
        "QOD_AUDIT_FILE_GROUP_READABLE"     -> "true"
      )
    ).telemetry
    t.auditSink shouldBe "file"
    t.auditFile shouldBe AuditFileConfig("/var/log/qod/audit.ndjson", 2097152L, 3, 0, true)
  }

  it should "register each setting under its variable, none sensitive" in {
    val entries = ConfigRegistry
      .collect(List("quack-on-demand" -> classOf[ManagerConfig]))
      .filter(_.path.startsWith("quack-on-demand.telemetry.auditFile."))
      .map(e => (e.path.stripPrefix("quack-on-demand.telemetry.auditFile."), e.envVar, e.sensitive))
    entries.toSet shouldBe Set(
      ("path", "QOD_AUDIT_FILE_PATH", false),
      ("maxFileBytes", "QOD_AUDIT_FILE_MAX_BYTES", false),
      ("maxFiles", "QOD_AUDIT_FILE_MAX_FILES", false),
      ("fsyncIntervalSec", "QOD_AUDIT_FILE_FSYNC_INTERVAL_SEC", false),
      ("groupReadable", "QOD_AUDIT_FILE_GROUP_READABLE", false)
    )
  }
