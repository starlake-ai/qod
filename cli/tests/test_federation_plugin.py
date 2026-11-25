"""`qod federation create --type <module type> --plugin-config <json>`.

The body assertions read the bytes httpx put on the wire, as the other federation tests do.
"""

import json

import httpx
import pytest

BASE = "http://localhost:20900"
SOURCES = f"{BASE}/api/tenants/acme/tenant-dbs/db1/federated-sources"


@pytest.fixture(autouse=True)
def token(monkeypatch):
    monkeypatch.setenv("QOD_TOKEN", "k")


def _invoke(runner, *args):
    from qod_cli.main import app

    return runner.invoke(app, ["federation", "create", "acme", "db1", "--alias", "crm", *args])


def _create(respx_mock):
    return respx_mock.post(SOURCES).mock(return_value=httpx.Response(200, json={"alias": "crm"}))


def test_plugin_type_sends_its_config(runner, respx_mock):
    route = _create(respx_mock)
    result = _invoke(runner, "--type", "Acme_CRM",
                     "--plugin-config", '{"host": "crm.example.com", "token": "{{secret.TOK}}"}')
    assert result.exit_code == 0, result.output
    body = json.loads(route.calls[-1].request.content.decode())
    assert body["sourceType"] == "acme_crm"
    assert body["pluginConfig"] == {"host": "crm.example.com", "token": "{{secret.TOK}}"}
    assert "setupSql" not in body and "config" not in body and "connection" not in body


def test_plugin_type_requires_plugin_config(runner, respx_mock):
    route = _create(respx_mock)
    result = _invoke(runner, "--type", "acme_crm")
    assert result.exit_code != 0
    assert "--plugin-config is required" in result.output
    assert not route.called


@pytest.mark.parametrize("raw, message", [
    ("{not json", "not valid JSON"),
    ('["a"]', "must be a JSON object"),
])
def test_plugin_config_must_be_a_json_object(runner, respx_mock, raw, message):
    route = _create(respx_mock)
    result = _invoke(runner, "--type", "acme_crm", "--plugin-config", raw)
    assert result.exit_code != 0
    assert message in result.output
    assert not route.called


@pytest.mark.parametrize("extra", [
    ("--setup-sql", "ATTACH 'x' AS crm"),
    ("--config", "{}"),
    ("--host", "h"),
])
def test_plugin_type_refuses_other_shapes(runner, respx_mock, extra):
    route = _create(respx_mock)
    result = _invoke(runner, "--type", "acme_crm", "--plugin-config", "{}", *extra)
    assert result.exit_code != 0
    assert not route.called


@pytest.mark.parametrize("builtin", ["sql", "iceberg-rest", "postgres", "mysql"])
def test_plugin_config_refused_for_built_in_types(runner, respx_mock, builtin):
    route = _create(respx_mock)
    result = _invoke(runner, "--type", builtin, "--plugin-config", "{}")
    assert result.exit_code != 0
    assert "--plugin-config is for a module-provided --type" in result.output
    assert not route.called
