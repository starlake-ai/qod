package ai.starlake.quack.ondemand.module

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.federation.FederatedSourceProviders
import ai.starlake.quack.ondemand.state.FederatedSourceOps
import ai.starlake.quack.spi.{ManagerModule, OwnSources}

/** A module's [[OwnSources]]: the stored sources whose type is one of `wires`, read from `store` on
  * every call (the alias as stored, matched exactly). Logs nothing.
  */
final class ModuleOwnSources(val wires: Set[String], store: FederatedSourceOps) extends OwnSources:

  def source(tenantDbId: String, alias: String): Option[FederatedSource] =
    store.getSource(tenantDbId, alias).filter { s =>
      s.sourceType match
        case FederatedSourceType.Plugin(w) => wires.contains(w)
        case _                             => false
    }

  def secretValue(tenantDbId: String, alias: String, name: String): Option[String] =
    source(tenantDbId, alias).flatMap(s => store.getSecret(s.id, name)).flatMap(_.value)

object ModuleOwnSources:

  /** `m`'s own sources: the wires of its providers that the registry installed at boot. */
  def of(m: ManagerModule, store: FederatedSourceOps): ModuleOwnSources =
    new ModuleOwnSources(
      m.federatedSourceProviders
        .map(_.wire)
        .filter(w => FederatedSourceProviders.of(w).isDefined)
        .toSet,
      store
    )

  /** Owns nothing: for a manager with no federation store. */
  val Empty: OwnSources = new OwnSources:
    def source(tenantDbId: String, alias: String): Option[FederatedSource]           = None
    def secretValue(tenantDbId: String, alias: String, name: String): Option[String] = None
