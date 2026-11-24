package ai.starlake.quack.edge.rls

/** Read once at boot and shared by the handle writer and the gateway pipeline. */
object RowPolicyEnforcement:
  def onNode: Boolean =
    val config = com.typesafe.config.ConfigFactory.load().getConfig("quack-on-demand.rls")
    val mode   = config.getString("enforcement")
    require(Set("gateway", "node").contains(mode), "rls.enforcement must be gateway or node")
    config.getBoolean("enabled") && mode == "node"
