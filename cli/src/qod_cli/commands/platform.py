"""Platform staff roles (issue #274): permission bundles for tenant-less staff users. Superuser-only."""

import typer

from ..registry import covers
from ._run import call

app = typer.Typer(help="Platform staff roles and staff users (superuser only).")
permission_app = typer.Typer(help="Permissions the loaded manager modules declare.")
role_app = typer.Typer(help="Platform roles: named bundles of permissions held by staff users.")
staff_app = typer.Typer(help="Staff users (tenant-less, kind staff).")
app.add_typer(permission_app, name="permission")
app.add_typer(role_app, name="role")
app.add_typer(staff_app, name="staff")


@permission_app.command("list")
@covers("GET", "/api/platform/permission/list")
def permission_list(ctx: typer.Context):
    """List declared permissions, and stored ones no loaded module declares."""
    call(ctx, "GET", "/api/platform/permission/list")


@role_app.command("list")
@covers("GET", "/api/platform/role/list")
def role_list(ctx: typer.Context):
    """List platform roles."""
    call(ctx, "GET", "/api/platform/role/list")


@role_app.command("create")
@covers(
    "POST",
    "/api/platform/role/create",
    {"name": "--name", "description": "--description", "permissions": "--permission"},
)
def role_create(
    ctx: typer.Context,
    name: str = typer.Option(..., "--name"),
    description: str = typer.Option(None, "--description"),
    permissions: list[str] = typer.Option(None, "--permission", help="Permission name (repeatable)."),
):
    """Create a platform role."""
    body: dict = {"name": name, "permissions": permissions or []}
    if description is not None:
        body["description"] = description
    call(ctx, "POST", "/api/platform/role/create", body=body)


@role_app.command("update")
@covers(
    "POST",
    "/api/platform/role/update",
    {"id": "ID", "name": "--name", "description": "--description", "permissions": "--permission"},
)
def role_update(
    ctx: typer.Context,
    role_id: str = typer.Argument(..., metavar="ID"),
    name: str = typer.Option(None, "--name"),
    description: str = typer.Option(None, "--description", help="Omit = unchanged; empty string clears."),
    permissions: list[str] = typer.Option(None, "--permission", help="Replaces the whole set (repeatable)."),
    clear_permissions: bool = typer.Option(False, "--clear-permissions", help="Remove every permission."),
):
    """Update a platform role; only the given fields change."""
    if permissions and clear_permissions:
        raise typer.BadParameter("--permission and --clear-permissions are mutually exclusive")
    body: dict = {"id": role_id}
    if name is not None:
        body["name"] = name
    if description is not None:
        body["description"] = description
    if permissions:
        body["permissions"] = permissions
    if clear_permissions:
        body["permissions"] = []
    call(ctx, "POST", "/api/platform/role/update", body=body)


@role_app.command("delete")
@covers("POST", "/api/platform/role/delete", {"id": "ID"})
def role_delete(ctx: typer.Context, role_id: str = typer.Argument(..., metavar="ID")):
    """Delete a platform role and its assignments."""
    call(ctx, "POST", "/api/platform/role/delete", body={"id": role_id})


@staff_app.command("list")
@covers("GET", "/api/platform/staff/list")
def staff_list(ctx: typer.Context):
    """List staff users and their platform roles."""
    call(ctx, "GET", "/api/platform/staff/list")
