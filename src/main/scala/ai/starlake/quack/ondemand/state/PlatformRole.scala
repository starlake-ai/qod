package ai.starlake.quack.ondemand.state

/** A platform role (issue #274): a named bundle of permission names that manager modules declare,
  * held by tenant-less `staff` users. Never a tenant RBAC role; never read by the node decider.
  */
final case class PlatformRole(
    id: String,
    name: String,
    description: Option[String],
    permissions: Set[String]
)
