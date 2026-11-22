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
