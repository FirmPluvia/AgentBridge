import copy
import hashlib
import json
import ssl
from pathlib import Path
import httpx
import pytest
from agentbridge import AgentBridge
from agentbridge.connection import connection_options, read_connection


def configuration():
    pem = (Path(__file__).parent / "fixtures/qr-public-cert.pem").read_text()
    return dict(type="agentbridge.connection", version=1, apiPrefix="/api/v1", deviceId="device",
                urls=["https://phone.example:8443"], token="T" * 43, allowHttp=False,
                tls=dict(certificatePem=pem, sha256=hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest(), verifyHostname=True))


@pytest.mark.parametrize("version", [1, 2])
async def test_imported_qr_preserves_auth_and_hostname_verification(monkeypatch, version):
    original, contexts = ssl.create_default_context, []
    def context(*args, **kwargs):
        value = original(*args, **kwargs); contexts.append(value); return value
    monkeypatch.setattr(ssl, "create_default_context", context)
    def handler(request):
        assert request.headers["authorization"] == "Bearer " + "T" * 43
        assert request.url.path == "/api/v1/status"
        return httpx.Response(200, json=dict(requestId=request.url.params["requestId"], deviceId="device", success=True, data={"ok": True}))
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        config = configuration()
        if version == 2:
            config = dict(version=2, deviceId=config["deviceId"], urls=config["urls"], token=config["token"],
                          certificatePem=config["tls"]["certificatePem"])
        bridge = AgentBridge.from_connection_config(config, http_client=http)
        assert await bridge.status() == {"ok": True}
    assert contexts and all(c.check_hostname and c.verify_mode == ssl.CERT_REQUIRED for c in contexts)


@pytest.mark.parametrize("change", ["fingerprint", "hostname", "version", "url", "token"])
def test_bad_config_never_disables_verification_or_leaks_token(change):
    cfg = configuration()
    if change == "fingerprint": cfg["tls"]["sha256"] = "0" * 64
    if change == "hostname": cfg["tls"]["verifyHostname"] = False
    if change == "version": cfg["version"] = 999
    if change == "url": cfg["urls"] = ["https://user:password@phone.example:8443"]
    if change == "token": cfg["token"] += "\r\nheader"
    with pytest.raises(ValueError) as exc: connection_options(cfg)
    assert cfg["token"] not in str(exc.value)


def test_plain_http_needs_controller_opt_in():
    cfg = configuration(); cfg.update(urls=["http://192.168.1.25:8443"], allowHttp=True, tls=None)
    with pytest.raises(ValueError): connection_options(cfg)
    assert connection_options(cfg, allow_http=True)["allow_http"]


async def test_config_file_limit_and_environment_import(tmp_path, monkeypatch):
    file = tmp_path / "connection.json"; file.write_text(json.dumps(configuration()))
    assert read_connection(file)["deviceId"] == "device"
    monkeypatch.setenv("AB_CONNECTION_CONFIG", str(file)); monkeypatch.delenv("AB_RELAY", raising=False); monkeypatch.delenv("AB_URL", raising=False)
    bridge = AgentBridge.from_env()
    assert bridge.url == "https://phone.example:8443"
    await bridge.close()
    file.write_text(" " * 65537)
    with pytest.raises(ValueError, match="64 KiB"): read_connection(file)


def minimal_configuration():
    old = configuration()
    return dict(version=2, deviceId=old["deviceId"], urls=old["urls"], token=old["token"],
                certificatePem=old["tls"]["certificatePem"])


@pytest.mark.parametrize("change", ["certificate", "url", "token", "bool_version", "loopback"])
def test_minimal_configuration_rejects_invalid_material(change):
    config = minimal_configuration()
    if change == "certificate": config["certificatePem"] = None
    if change == "url": config["urls"] = ["https://user:password@phone.example:8443"]
    if change == "token": config["token"] += "\r\nheader"
    if change == "bool_version": config["version"] = True
    if change == "loopback": config["urls"] = ["https://127.0.0.2:8443"]
    with pytest.raises(ValueError): connection_options(config)


def test_minimal_http_requires_separate_controller_opt_in():
    config = minimal_configuration()
    config.update(urls=["http://192.168.1.25:8443"], certificatePem=None)
    with pytest.raises(ValueError): connection_options(config)
    assert connection_options(config, allow_http=True)["allow_http"]
