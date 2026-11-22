import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="User principals.")


@app.command("list")
@covers("GET", "/api/user/list", {"tenant": "--tenant"})
def list_(ctx: typer.Context, tenant: str = typer.Option(None, "--tenant", help="Omit = all users incl. superusers.")):
    call(ctx, "GET", "/api/user/list", params={"tenant": tenant})


@app.command()
@covers(
    "POST",
    "/api/user/create",
    {
        "tenant": "--tenant",
        "username": "--username",
        "password": "--password",
        "kind": "--kind",
        "mustChangePassword": "--must-change-password",
        "email": "--email",
        "roles": "--role",
        "groups": "--group",
        "platformRoles": "--platform-role",
    },
)
def create(
    ctx: typer.Context,
    username: str = typer.Option(..., "--username"),
    tenant: str = typer.Option(None, "--tenant"),
    superuser: bool = typer.Option(False, "--superuser", help="Create a superuser (no tenant, kind admin)."),
    password: str = typer.Option(None, "--password", help="Prompted when omitted."),
    kind: str = typer.Option(
        None,
        "--kind",
        help="Account kind: admin | user | staff. Default user, or admin with --superuser. "
        "staff is tenant-less: platform roles only.",
    ),
    must_change_password: bool = typer.Option(
        False,
        "--must-change-password",
        help="Force a password change at the user's next login.",
    ),
    email: str = typer.Option(
        None,
        "--email",
        help="Contact address for password-reset links. Omit to leave emailless. "
        "Derived from and locked to the username when the username is itself in email format.",
    ),
    roles: list[str] = typer.Option(
        None,
        "--role",
        help="RBAC role name in the tenant (repeatable). Omit for the default qod_all_tables.",
    ),
    groups: list[str] = typer.Option(
        None,
        "--group",
        help="Group name in the tenant (repeatable). Omit for the default qod_all_pools.",
    ),
    platform_roles: list[str] = typer.Option(
        None, "--platform-role", help="Platform role of a staff user (repeatable)."
    ),
):
    kind = kind.strip().lower() if kind is not None else None
    staff = kind == "staff"
    if superuser and staff:
        raise typer.BadParameter("--superuser and --kind staff are mutually exclusive")
    if superuser and kind not in (None, "admin"):
        raise typer.BadParameter("--superuser creates kind admin; drop --kind or pass --kind admin")
    if kind is None:
        kind = "admin" if superuser else "user"
    tenantless = superuser or staff
    if tenantless and tenant:
        raise typer.BadParameter("--superuser and --kind staff take no --tenant")
    if not tenantless and not tenant:
        raise typer.BadParameter("pass --tenant, or --superuser / --kind staff for a tenant-less account")
    if tenantless and (roles or groups):
        raise typer.BadParameter("--role / --group do not apply to a tenant-less account")
    if platform_roles and not staff:
        raise typer.BadParameter("--platform-role applies to --kind staff only")
    if password is None:
        password = typer.prompt("Password", hide_input=True, confirmation_prompt=True)
    body: dict = {
        "tenant": None if tenantless else tenant,
        "username": username,
        "password": password,
        "kind": kind,
        "mustChangePassword": must_change_password,
        "email": email,
    }
    # Omitted lists let the server apply its defaults (qod_all_tables / qod_all_pools).
    if roles:
        body["roles"] = roles
    if groups:
        body["groups"] = groups
    if platform_roles:
        body["platformRoles"] = platform_roles
    call(ctx, "POST", "/api/user/create", body=body)


@app.command()
@covers(
    "POST",
    "/api/user/update",
    {
        "id": "ID",
        "tenant": "--tenant",
        "password": "--password",
        "kind": "--kind",
        "mustChangePassword": "--must-change-password",
        "email": "--email",
        "enabled": "--enabled",
        "platformRoles": "--platform-role",
    },
)
def update(
    ctx: typer.Context,
    user_id: str = typer.Argument(..., metavar="ID"),
    tenant: str = typer.Option(None, "--tenant"),
    password: str = typer.Option(None, "--password", help="Omit = no rotation."),
    kind: str = typer.Option(None, "--kind"),
    must_change_password: bool = typer.Option(
        None,
        "--must-change-password/--no-must-change-password",
        help="With --password: flag (or explicitly unflag) the new password as temporary.",
    ),
    email: str = typer.Option(
        None,
        "--email",
        help="Omit = unchanged; empty string clears. "
        "Derived from and locked to the username when the username is itself in email format.",
    ),
    enabled: bool = typer.Option(
        None,
        "--enabled/--no-enabled",
        help="Omit = unchanged. --no-enabled locks the account (sign-in refused, "
        "tokens stop working); --enabled unlocks.",
    ),
    platform_roles: list[str] = typer.Option(
        None, "--platform-role", help="Replace the staff user's platform roles (repeatable)."
    ),
    clear_platform_roles: bool = typer.Option(
        False, "--clear-platform-roles", help="Remove every platform role of the staff user."
    ),
):
    if platform_roles and clear_platform_roles:
        raise typer.BadParameter("--platform-role and --clear-platform-roles are mutually exclusive")
    body: dict = {"id": user_id}
    if tenant is not None:
        body["tenant"] = tenant
    if password is not None:
        body["password"] = password
    if kind is not None:
        body["kind"] = kind
    if must_change_password is not None:
        body["mustChangePassword"] = must_change_password
    if email is not None:
        body["email"] = email
    if enabled is not None:
        body["enabled"] = enabled
    if platform_roles:
        body["platformRoles"] = platform_roles
    if clear_platform_roles:
        body["platformRoles"] = []
    call(ctx, "POST", "/api/user/update", body=body)


@app.command()
@covers("POST", "/api/user/delete", {"id": "ID"})
def delete(ctx: typer.Context, user_id: str = typer.Argument(..., metavar="ID")):
    call(ctx, "POST", "/api/user/delete", body={"id": user_id})


@app.command()
@covers("GET", "/api/user/{id}/effective", {"id": "ID"})
def effective(ctx: typer.Context, user_id: str = typer.Argument(..., metavar="ID")):
    """Closure of roles, groups, table permissions, and pool grants."""
    call(ctx, "GET", f"/api/user/{user_id}/effective")


s3_app = typer.Typer(help="A user's own S3 credentials, as an admin sees them: list and remove (only the user adds them).")
app.add_typer(s3_app, name="s3")


@s3_app.command("list")
@covers("POST", "/api/user/s3/list", {"userId": "USER_ID"})
def s3_list(ctx: typer.Context, user_id: str = typer.Argument(..., metavar="USER_ID")):
    """The user's S3 credentials (never a secret)."""
    call(ctx, "POST", "/api/user/s3/list", body={"userId": user_id})


@s3_app.command("remove")
@covers("POST", "/api/user/s3/remove", {"userId": "USER_ID", "id": "ID"})
def s3_remove(
    ctx: typer.Context,
    user_id: str = typer.Argument(..., metavar="USER_ID"),
    credential_id: str = typer.Argument(..., metavar="ID", help="The credential id (from `qod user s3 list`)."),
):
    """Remove one of the user's S3 credentials."""
    call(ctx, "POST", "/api/user/s3/remove", body={"userId": user_id, "id": credential_id})
