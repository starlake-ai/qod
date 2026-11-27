package ai.starlake.quack.spi

import ai.starlake.quack.model.FederatedSource

/** The stored federated sources of this module's own source types, for its routes. Every call reads
  * the control-plane database: call it inside `IO.blocking`, after the route has authorized its
  * caller for the tenant. Nothing here is logged; the module never logs a value either, and sends
  * one only to the stored config's own endpoint.
  */
trait OwnSources:
  /** `alias` of tenant-db `tenantDbId` when its sourceType is one of this module's provider wires;
    * None otherwise (another type, unknown alias).
    */
  def source(tenantDbId: String, alias: String): Option[FederatedSource]

  /** The value of that source's secret `name` when held as a value; None for an externalRef secret,
    * a missing one, or a source `source` would not return.
    */
  def secretValue(tenantDbId: String, alias: String, name: String): Option[String]
