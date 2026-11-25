package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSourceType
import ai.starlake.quack.spi.FederatedSourceProvider

/** The loaded modules' federated source providers, installed once at boot right after module
  * discovery. A plugin source whose provider is absent renders nothing and is refused.
  */
object FederatedSourceProviders:
  private val WirePattern = "^[a-z0-9_]+$".r

  /** The width of `qodstate_federated_source.source_type` (VARCHAR(32), migration 0038). */
  val MaxWireLength = 32

  @volatile private var byWire: Map[String, FederatedSourceProvider] = Map.empty

  def validate(ps: List[FederatedSourceProvider]): Either[String, Unit] =
    val wires    = ps.map(_.wire)
    val builtIns = FederatedSourceType.builtIns.map(_.wire).toSet
    val problem  =
      wires.find(w => WirePattern.findFirstIn(w).isEmpty)
        .map(bad => s"provider wire '$bad' is not lowercase [a-z0-9_]+")
        .orElse(
          wires.find(_.length > MaxWireLength)
            .map(long => s"provider wire '$long' is longer than $MaxWireLength characters")
        )
        .orElse(
          wires.find(builtIns.contains)
            .map(clash => s"provider wire '$clash' collides with a built-in source type")
        )
        .orElse(
          wires.diff(wires.distinct).headOption.map(dup => s"two providers declare source type '$dup'")
        )
    problem.toLeft(())

  def install(ps: List[FederatedSourceProvider]): Either[String, Unit] =
    validate(ps).map { _ =>
      FederatedSourceType.registerPluginWires(ps.map(_.wire).toSet)
      byWire = ps.map(p => p.wire -> p).toMap
    }

  def of(wire: String): Option[FederatedSourceProvider] = byWire.get(wire)

  def reservedSecretPrefixes: List[String] = byWire.values.toList.flatMap(_.reservedSecretPrefixes)
