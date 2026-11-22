import json

import httpx

from qod_cli.main import app

BASE = "http://localhost:20900"


def test_role_create_sends_permissions(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/platform/role/create").mock(
        return_value=httpx.Response(200, json={"id": "r1", "name": "support", "permissions": []})
    )
    r = runner.invoke(app, ["platform", "role", "create", "--name", "support",
                            "--permission", "hosted.tenant.read", "--permission", "hosted.quota.extra"])
    assert r.exit_code == 0, r.output
    body = json.loads(route.calls.last.request.content)
    assert body == {"name": "support", "permissions": ["hosted.tenant.read", "hosted.quota.extra"]}


def test_role_update_only_sends_given_fields(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/platform/role/update").mock(
        return_value=httpx.Response(200, json={"id": "r1", "name": "support", "permissions": []})
    )
    r = runner.invoke(app, ["platform", "role", "update", "r1", "--clear-permissions"])
    assert r.exit_code == 0, r.output
    assert json.loads(route.calls.last.request.content) == {"id": "r1", "permissions": []}


def test_user_create_staff_without_tenant(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/user/create").mock(return_value=httpx.Response(200, json={"id": "u1"}))
    r = runner.invoke(app, ["user", "create", "--kind", "staff", "--username", "sam", "--password", "pw",
                            "--platform-role", "support"])
    assert r.exit_code == 0, r.output
    body = json.loads(route.calls.last.request.content)
    assert body["tenant"] is None and body["kind"] == "staff" and body["platformRoles"] == ["support"]


def test_user_create_staff_with_tenant_is_refused(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/user/create")
    r = runner.invoke(app, ["user", "create", "--kind", "staff", "--tenant", "acme", "--username", "sam", "--password", "pw"])
    assert r.exit_code == 2
    assert not route.called


def test_user_update_clear_platform_roles(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/user/update").mock(return_value=httpx.Response(200, json={"id": "u1"}))
    r = runner.invoke(app, ["user", "update", "u1", "--clear-platform-roles"])
    assert r.exit_code == 0, r.output
    assert json.loads(route.calls.last.request.content) == {"id": "u1", "platformRoles": []}


def _refused(runner, respx_mock, path, args):
    route = respx_mock.post(f"{BASE}{path}")
    r = runner.invoke(app, args)
    assert r.exit_code == 2, r.output
    assert not route.called


def test_superuser_defaults_to_kind_admin(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/user/create").mock(return_value=httpx.Response(200, json={"id": "u1"}))
    r = runner.invoke(app, ["user", "create", "--superuser", "--username", "root", "--password", "pw"])
    assert r.exit_code == 0, r.output
    body = json.loads(route.calls.last.request.content)
    assert body["tenant"] is None and body["kind"] == "admin"


def test_superuser_with_other_kind_is_refused(runner, respx_mock):
    _refused(runner, respx_mock, "/api/user/create",
             ["user", "create", "--superuser", "--kind", "user", "--username", "r", "--password", "pw"])


def test_superuser_with_staff_is_refused(runner, respx_mock):
    _refused(runner, respx_mock, "/api/user/create",
             ["user", "create", "--superuser", "--kind", " Staff ", "--username", "r", "--password", "pw"])


def test_kind_is_normalized(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/user/create").mock(return_value=httpx.Response(200, json={"id": "u1"}))
    r = runner.invoke(app, ["user", "create", "--kind", " STAFF ", "--username", "s", "--password", "pw"])
    assert r.exit_code == 0, r.output
    assert json.loads(route.calls.last.request.content)["kind"] == "staff"


def test_platform_role_without_staff_is_refused(runner, respx_mock):
    _refused(runner, respx_mock, "/api/user/create",
             ["user", "create", "--tenant", "acme", "--username", "u", "--password", "pw", "--platform-role", "x"])


def test_permission_with_clear_permissions_is_refused(runner, respx_mock):
    _refused(runner, respx_mock, "/api/platform/role/update",
             ["platform", "role", "update", "r1", "--permission", "a", "--clear-permissions"])


def test_platform_role_with_clear_is_refused(runner, respx_mock):
    _refused(runner, respx_mock, "/api/user/update",
             ["user", "update", "u1", "--platform-role", "a", "--clear-platform-roles"])


def test_role_update_name_only(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/platform/role/update").mock(return_value=httpx.Response(200, json={}))
    r = runner.invoke(app, ["platform", "role", "update", "r1", "--name", "n"])
    assert r.exit_code == 0, r.output
    assert json.loads(route.calls.last.request.content) == {"id": "r1", "name": "n"}


def test_role_delete(runner, respx_mock):
    route = respx_mock.post(f"{BASE}/api/platform/role/delete").mock(return_value=httpx.Response(200, json={}))
    r = runner.invoke(app, ["platform", "role", "delete", "r1"])
    assert r.exit_code == 0, r.output
    assert json.loads(route.calls.last.request.content) == {"id": "r1"}


def test_list_commands_hit_get_routes(runner, respx_mock):
    for args, path in [
        (["platform", "permission", "list"], "/api/platform/permission/list"),
        (["platform", "role", "list"], "/api/platform/role/list"),
        (["platform", "staff", "list"], "/api/platform/staff/list"),
    ]:
        route = respx_mock.get(f"{BASE}{path}").mock(return_value=httpx.Response(200, json=[]))
        r = runner.invoke(app, args)
        assert r.exit_code == 0, r.output
        assert route.called
