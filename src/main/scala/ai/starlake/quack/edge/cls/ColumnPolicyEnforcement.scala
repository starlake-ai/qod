package ai.starlake.quack.edge.cls

/** One switch used by both signed policy transport and gateway rewrite wiring. */
object ColumnPolicyEnforcement:
  def nativeEnabled(): Boolean =
    val config = com.typesafe.config.ConfigFactory.load().getConfig("quack-on-demand.cls")
    nativeEnabled(config)

  private[quack] def nativeEnabled(config: com.typesafe.config.Config): Boolean =
    val native = config.getString("enforcement") match
      case "gateway" => false
      case "node"    => true
      case _ => throw new IllegalArgumentException("CLS enforcement must be gateway or node")
    config.getBoolean("enabled") && native
