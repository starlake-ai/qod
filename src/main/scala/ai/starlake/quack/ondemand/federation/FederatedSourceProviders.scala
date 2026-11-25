package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSourceType
import ai.starlake.quack.spi.FederatedSourceProvider

/** The loaded modules' federated source providers, installed once at boot right after module
  * discovery. A plugin source whose provider is absent renders nothing and is refused.
  */
object FederatedSourceProviders:
  private val WirePattern = "^[a-z0-9_]+$".r

  @volatile private var byWire: Map[String, FederatedSourceProvider] = Map.empty

  def validate(ps: List[FederatedSourceProvider]): Either[String, Unit] =
    val wires    = ps.map(_.wire)
    val builtIns = FederatedSourceType.builtIns.map(_.wire).toSet
    wires.find(w => WirePattern.findFirstIn(w).isEmpty) match
      case Some(bad) => Left(s"provider wire '$bad' is not lowercase [a-z0-9_]+")
      case None =>
        wires.find(builtIns.contains) match
          case Some(clash) => Left(s"provider wire '$clash' collides with a built-in source type")
          case None =>
            wires.diff(wires.distinct).headOption match
              case Some(dup) => Left(s"two providers declare source type '$dup'")
              case None      => Right(())

  def install(ps: List[FederatedSourceProvider]): Either[String, Unit] =
    validate(ps).map { _ =>
      FederatedSourceType.registerPluginWires(ps.map(_.wire).toSet)
      byWire = ps.map(p => p.wire -> p).toMap
    }

  def of(wire: String): Option[FederatedSourceProvider] = byWire.get(wire)

  def reservedSecretPrefixes: List[String] = byWire.values.toList.flatMap(_.reservedSecretPrefixes)
