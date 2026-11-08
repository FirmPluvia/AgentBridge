"""Signing policy tests. Integration uses an ephemeral, deliberately WRONG certificate."""
import base64
import hashlib
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import signing_policy as policy
import sign_release
from verify_android_artifact import verify


@pytest.fixture(autouse=True)
def clear_signing_env(monkeypatch):
    for name in policy.SECRET_NAMES:
        monkeypatch.delenv(name, raising=False)


def test_missing_secrets_without_valid_apk_creates_nothing(tmp_path):
    # Missing secrets now falls back to the debug key instead of refusing,
    # so an unreadable input must still fail before any output is created.
    with pytest.raises(policy.PolicyError, match="could not run"):
        sign_release.sign(tmp_path / "absent.apk", tmp_path / "output", tmp_path)
    assert not (tmp_path / "output").exists()


def test_wrong_alias_rejected_before_tools(monkeypatch, tmp_path):
    for name in policy.SECRET_NAMES:
        monkeypatch.setenv(name, "private-sentinel")
    with pytest.raises(policy.PolicyError, match="pinned starx alias") as failure:
        sign_release.sign(tmp_path / "absent.apk", tmp_path / "output", tmp_path)
    assert "private-sentinel" not in str(failure.value)
    assert not (tmp_path / "output").exists()


@pytest.mark.parametrize("report", ["", "Signer #1 certificate SHA-256 digest: ab",
    "Signer #1 certificate SHA-256 digest: " + "ab" * 32 + "\nSigner #2 certificate SHA-256 digest: " + "cd" * 32])
def test_reject_missing_truncated_or_multiple_signers(report):
    with pytest.raises(policy.PolicyError):
        policy.digest_from_report(report)


def test_normalizes_public_certificate_fingerprint():
    report = "Signer #1 certificate SHA-256 digest: " + policy.RELEASE_SHA256.replace(":", "").lower()
    assert policy.digest_from_report(report) == policy.RELEASE_SHA256


@pytest.fixture
def android_builds():
    sdk = os.environ.get("ANDROID_HOME")
    tools = Path(sdk) / "build-tools/36.0.0" if sdk else None
    debug = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
    release = ROOT / "android/app/build/outputs/apk/release/app-release-unsigned.apk"
    if not tools or not (tools / "apksigner").exists() or not debug.exists() or not release.exists():
        pytest.skip("requires locally built Android APKs and Build Tools 36.0.0")
    return tools, debug, release


def test_actual_debug_and_unsigned_release_identities(android_builds):
    tools, debug, release = android_builds
    assert verify(debug, "debug", tools)["package"] == policy.DEBUG_PACKAGE
    assert verify(release, "unsigned-release", tools)["package"] == policy.RELEASE_PACKAGE
    with pytest.raises(policy.PolicyError):
        verify(debug, "release", tools)


def test_debug_input_cannot_enter_release_signer(android_builds, monkeypatch, tmp_path):
    tools, debug, _ = android_builds
    for name in policy.SECRET_NAMES:
        monkeypatch.setenv(name, "starx" if name == "KEY_ALIAS" else "not-secret-test")
    with pytest.raises(policy.PolicyError, match="starx application ID"):
        sign_release.sign(debug, tmp_path / "output", tools)
    assert not (tmp_path / "output").exists()


def test_missing_secrets_falls_back_to_debug_key_without_verification(android_builds, tmp_path):
    tools, _, release = android_builds
    before = hashlib.sha256(release.read_bytes()).digest()
    outdir = tmp_path / "output"
    name = sign_release.sign(release, outdir, tools)
    assert name == "AgentBridge-v0.5-debug-release.apk"
    assert (outdir / name).exists()
    assert (outdir / "SHA256SUMS").exists()
    # No certificate verification is performed for the debug fallback,
    # so no verification report is published for it.
    assert not (outdir / "RELEASE-VERIFICATION.json").exists()
    assert hashlib.sha256(release.read_bytes()).digest() == before


def test_bad_base64_is_private_and_rejected(android_builds, monkeypatch, tmp_path):
    tools, _, release = android_builds
    for name in policy.SECRET_NAMES:
        monkeypatch.setenv(name, "starx" if name == "KEY_ALIAS" else "invalid@private-sentinel")
    with pytest.raises(policy.PolicyError, match="Invalid GitHub keystore material") as failure:
        sign_release.sign(release, tmp_path / "output", tools)
    assert "private-sentinel" not in str(failure.value)
    assert not (tmp_path / "output").exists()


def test_real_wrong_certificate_is_signed_without_verification(android_builds, tmp_path):
    # Certificate verification was removed: a key that does not match the
    # pinned starx fingerprint is now signed and published without any
    # verification report. The temporary key must still be removed.
    tools, _, release = android_builds
    before = hashlib.sha256(release.read_bytes()).digest()
    # A temporary TEST identity only. No production key/password is read or generated.
    with tempfile.TemporaryDirectory(prefix="agentbridge-signing-test-") as directory:
        private = Path(directory)
        key = private / "test.jks"
        runner = private / "runner"
        runner.mkdir(mode=0o700)
        env = os.environ.copy()
        password = secrets.token_urlsafe(32)
        env.update(KEYSTORE_PASSWORD=password, KEY_PASSWORD=password, KEY_ALIAS="starx", RUNNER_TEMP=str(runner))
        keytool = Path(os.environ["JAVA_HOME"]) / "bin/keytool"
        generated = subprocess.run([str(keytool), "-genkeypair", "-keystore", str(key),
            "-storetype", "JKS", "-alias", "starx", "-storepass:env", "KEYSTORE_PASSWORD",
            "-keypass:env", "KEY_PASSWORD", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "1", "-dname", "CN=Disposable Policy Test", "-noprompt"],
            env=env, capture_output=True)
        assert generated.returncode == 0, "test keystore generation failed (diagnostics withheld)"
        encoded = base64.b64encode(key.read_bytes()).decode()
        env["KEYSTORE_BASE64"] = encoded
        result = subprocess.run([sys.executable, str(ROOT / "scripts/sign_release.py"),
            "--input", str(release), "--outdir", str(tmp_path / "output"),
            "--build-tools", str(tools)], env=env, capture_output=True, text=True)
        assert result.returncode == 0, result.stderr
        assert "certificate not verified" in result.stdout
        assert password not in result.stderr + result.stdout
        assert encoded not in result.stderr + result.stdout
        outdir = tmp_path / "output"
        assert (outdir / "AgentBridge-v0.5-starx-release.apk").exists()
        assert (outdir / "SHA256SUMS").exists()
        assert not (outdir / "RELEASE-VERIFICATION.json").exists()
        assert list(runner.iterdir()) == []
    assert hashlib.sha256(release.read_bytes()).digest() == before
