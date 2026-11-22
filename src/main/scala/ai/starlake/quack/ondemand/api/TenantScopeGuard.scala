package ai.starlake.quack.ondemand.api

/** Extracts a tenant id from a management-plane request URL/query so the apiKeyGuard can reject
  * `tenant_forbidden` for non-superuser sessions.
  *
  * Only the URL-path / query forms are recognized here. Body-tenant endpoints (e.g. POST
  * /api/pool/create with `{ tenant: "..." }`) are NOT covered by this helper. Those handlers
  * enforce the scope themselves via [[TenantScopeCheck.reject]].
  *
  * The path is read as the request's decoded segments, the shape the router matches on (the leading
  * `/` and any trailing empty segment dropped, each segment percent-decoded), never as the joined
  * path: `%2F` inside a segment decodes to `/` but stays one segment for the router, so a joined
  * path could read `catalog%2Ftenant` as the catalog arm, or `audit%2Flist` as an exempt route, for
  * a request no such route serves. A tenant is its segment as it is: one holding `/` is no tenant
  * id, so a non-superuser is refused.
  *
  * CONVENTION: a route that carries the tenant in its URL PATH MUST name the capture
  * `path[String]("tenant")`. TenantScopeCompletenessSpec enumerates every endpoint and fails the
  * build if a `{tenant}` path route is not covered by `extractTenant` here, or if a tenant-ish
  * capture is named anything other than `tenant`. Query-tenant routes (`?tenant=`) are covered
  * generically by the `queryTenant` fallback below and need no per-route arm.
  */
object TenantScopeGuard:

  // Paths where ?tenant= is a filter hint handled by the endpoint's own scoping
  // logic, not an authz scope that the perimeter guard should enforce. Adding a
  // path here means the guard will NOT fire 403 for a cross-tenant ?tenant= value
  // on these routes; instead the handler silently falls back to the caller's
  // manageable-tenant set. Whole segments, exactly.
  private val QueryTenantExempt: Set[Vector[String]] = Set(
    Vector("api", "audit", "list"),
    Vector("api", "history", "trends"),
    Vector("api", "history", "statements"),
    Vector("api", "usage")
  )

  /** The tenant a path names, by its arm. Each arm requires what its route shape always did: a
    * non-empty tenant segment, a non-empty segment wherever a capture stands, and, where the shape
    * goes on past the tenant, at least one more segment.
    */
  private def pathTenant(segments: Vector[String]): Option[String] =
    segments match
      // /api/pool/{tenant}/{tenantDb}/{pool}/status...
      case Vector("api", "pool", t, db, pool, status, _*)
          if t.nonEmpty && db.nonEmpty && pool.nonEmpty && status.startsWith("status") =>
        Some(t)
      // /api/catalog/tenant/{tenant}/...
      case Vector("api", "catalog", "tenant", t, _, _*) if t.nonEmpty => Some(t)
      // /api/branch/tenant/{tenant}/...
      case Vector("api", "branch", "tenant", t, _, _*) if t.nonEmpty => Some(t)
      // /api/tenants/{tenant}/tenant-dbs/...
      case Vector("api", "tenants", t, "tenant-dbs", _, _*) if t.nonEmpty => Some(t)
      // /api/scim/v2/{tenant}[/...]
      case Vector("api", "scim", "v2", t, _*) if t.nonEmpty => Some(t)
      case _                                                => None

  /** Returns the request's tenant id (path or query), or `None` if no tenant is encoded in the URL
    * form. `segments` are the request's decoded path segments (see the scaladoc above). Public-API
    * URLs (`/api/auth/login`, `/api/config/client`) always return `None` here because the guard
    * never runs on them.
    */
  def extractTenant(segments: Vector[String], queryTenant: Option[String]): Option[String] =
    pathTenant(segments).orElse(
      if QueryTenantExempt.contains(segments) then None
      else queryTenant.map(_.trim).filter(_.nonEmpty)
    )
