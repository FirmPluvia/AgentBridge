"""Sensitive QR configuration import. No credentials are logged or written by this module."""
from __future__ import annotations
import hashlib
import ipaddress
import json
import re
import ssl
from pathlib import Path
from urllib.parse import urlsplit


def read_connection(path: str | Path) -> dict:
    file = Path(path)
    if file.stat().st_size > 65536:
        raise ValueError("Connection configuration exceeds 64 KiB")
    try:
        value = json.loads(file.read_text(encoding="utf-8"))
    except (ValueError, UnicodeError):
        raise ValueError("Invalid connection configuration JSON") from None
    if not isinstance(value, dict):
        raise ValueError("Connection configuration must be a JSON object")
    return value


def connection_options(config: dict, *, url: str | None = None, allow_http=False) -> dict:
    if not isinstance(config, dict) or type(config.get("version")) is not int or config["version"] not in (1, 2):
        raise ValueError("Unsupported connection QR format")
    legacy = config["version"] == 1
    if legacy and (config.get("type") != "agentbridge.connection" or config.get("apiPrefix") != "/api/v1"):
        raise ValueError("Unsupported legacy connection QR format")
    urls = config.get("urls")
    if not isinstance(urls, list) or not urls or not all(isinstance(v, str) for v in urls):
        raise ValueError("Missing connection URLs")
    selected = url or urls[0]
    if selected not in urls:
        raise ValueError("Selected URL is absent from connection configuration")
    try:
        target = urlsplit(selected)
        port = target.port
        valid = (target.scheme in ("https", "http") and target.hostname and not target.username and not target.password
                 and target.path in ("", "/") and not target.query and not target.fragment
                 and target.hostname not in ("0.0.0.0", "::", "127.0.0.1", "::1", "localhost")
                 and port is not None and 1024 <= port <= 65535)
    except ValueError:
        valid = False
    if valid:
        try:
            address = ipaddress.ip_address(target.hostname)
            valid = not (address.is_loopback or address.is_unspecified or address.is_multicast)
        except ValueError:
            valid = target.hostname != "localhost" and not target.hostname.endswith(".localhost")
    if not valid:
        raise ValueError("Invalid connection URL")
    token, device = config.get("token"), config.get("deviceId")
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,254}={0,2}", token):
        raise ValueError("Invalid connection credential")
    if not isinstance(device, str) or not device.strip() or len(device) > 128:
        raise ValueError("Invalid device identity")
    pem = None
    if target.scheme == "http":
        if not allow_http or (legacy and config.get("allowHttp") is not True):
            raise ValueError("Plain HTTP requires explicit allow_http=True or --allow-http")
    else:
        if legacy:
            tls = config.get("tls")
            if not isinstance(tls, dict) or tls.get("verifyHostname") is not True:
                raise ValueError("TLS configuration must retain hostname verification")
            pem, fingerprint = tls.get("certificatePem"), tls.get("sha256")
        else:
            pem, fingerprint = config.get("certificatePem"), None
        if not isinstance(pem, str) or not pem.strip():
            raise ValueError("Missing TLS certificate")
        try:
            der = ssl.PEM_cert_to_DER_cert(pem)
        except (ValueError, ssl.SSLError):
            raise ValueError("Invalid TLS certificate") from None
        if legacy:
            if not isinstance(fingerprint, str):
                raise ValueError("Missing TLS fingerprint")
            expected = fingerprint.replace(":", "").lower()
            if not re.fullmatch(r"[0-9a-f]{64}", expected) or hashlib.sha256(der).hexdigest() != expected:
                raise ValueError("TLS certificate fingerprint mismatch")
    return dict(url=selected, token=token, device_id=device, ca_pem=pem, allow_http=bool(allow_http))
