"""Post-sign an unsigned starx APK.

When all four GitHub signing secrets are configured, sign with the
configured key. When any of them is missing, fall back to the standard
Android debug key instead. No certificate verification is performed in
either mode: the signed APK is published with only a SHA256SUMS file, and
no RELEASE-VERIFICATION.json is produced.
"""
import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import shutil
import tempfile
from signing_policy import (RELEASE_ALIAS, RELEASE_PACKAGE, SECRET_NAMES, PolicyError,
                            metadata, invoke, require_unsigned, tools_path)


ROOT = Path(__file__).resolve().parents[1]
DEBUG_ALIAS = "androiddebugkey"
DEBUG_PASSWORD = "android"


def signing_mode():
    """'starx' only when every signing secret is configured, else 'debug'."""
    return "starx" if all(os.environ.get(name) for name in SECRET_NAMES) else "debug"


def _find_keytool():
    keytool = shutil.which("keytool")
    if keytool:
        return Path(keytool)
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / "keytool"
        if candidate.exists():
            return candidate
    raise PolicyError("keytool is required to generate the debug signing key")


def _debug_keystore(folder):
    """Generate the standard Android debug keystore (android/android)."""
    keyfile = folder / "debug.keystore"
    result = invoke([_find_keytool(), "-genkeypair", "-keystore", keyfile, "-storetype", "JKS",
                     "-alias", DEBUG_ALIAS, "-storepass", DEBUG_PASSWORD,
                     "-keypass", DEBUG_PASSWORD, "-keyalg", "RSA", "-keysize", "2048",
                     "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US", "-noprompt"])
    if result.returncode:
        raise PolicyError("Debug signing key could not be generated")
    os.chmod(keyfile, 0o600)
    return keyfile


def _temporary_root():
    temporary_root = Path(os.environ.get("RUNNER_TEMP") or tempfile.gettempdir()).resolve()
    if temporary_root == ROOT or ROOT in temporary_root.parents:
        raise PolicyError("Signing temporary directory must be outside the source repository")
    return temporary_root


def _sign_debug(apk, outdir, tools, info):
    """Sign with the debug key. No certificate verification is performed."""
    if not all(ch.isalnum() or ch in ".-_" for ch in info["versionName"]):
        raise PolicyError("Unsupported APK version name")
    output = outdir / f"AgentBridge-v{info['versionName']}-debug-release.apk"
    if output.exists():
        raise PolicyError("Release output already exists; use a clean artifact directory")
    with tempfile.TemporaryDirectory(prefix="agentbridge-debug-", dir=_temporary_root()) as temporary:
        folder = Path(temporary)
        os.chmod(folder, 0o700)
        keyfile = _debug_keystore(folder)
        staged = folder / "signed.apk"
        result = invoke([tools / "apksigner", "sign", "--ks", keyfile, "--ks-key-alias", DEBUG_ALIAS,
                         "--ks-pass", f"pass:{DEBUG_PASSWORD}", "--key-pass", f"pass:{DEBUG_PASSWORD}",
                         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
                         "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                         "--out", staged, apk])
        if result.returncode:
            raise PolicyError("Debug apksigner signing failed")
        outdir.mkdir(parents=True, exist_ok=True)
        os.replace(staged, output)
        (outdir / "SHA256SUMS").write_text(hashlib.sha256(output.read_bytes()).hexdigest() + "  " + output.name + "\n")
    return output.name


def _sign_starx(apk, outdir, tools, info):
    require_unsigned(apk, tools)
    encoded = "".join(os.environ["KEYSTORE_BASE64"].split())
    if len(encoded) > 8 * 1024 * 1024:
        raise PolicyError("Signing material exceeds the permitted size")
    try:
        material = base64.b64decode(encoded, validate=True)
        if not material:
            raise ValueError()
    except (ValueError, binascii.Error):
        raise PolicyError("Invalid GitHub keystore material") from None
    if not all(ch.isalnum() or ch in ".-_" for ch in info["versionName"]):
        raise PolicyError("Unsupported APK version name")
    output = outdir / f"AgentBridge-v{info['versionName']}-starx-release.apk"
    if output.exists():
        raise PolicyError("Release output already exists; use a clean artifact directory")
    with tempfile.TemporaryDirectory(prefix="agentbridge-starx-", dir=_temporary_root()) as temporary:
        folder = Path(temporary)
        os.chmod(folder, 0o700)
        keyfile = folder / "signing.jks"
        with os.fdopen(os.open(keyfile, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as target:
            target.write(material)
        staged = folder / "signed.apk"
        child_env = os.environ.copy()
        child_env.pop("KEYSTORE_BASE64", None)
        child_env.pop("KEY_ALIAS", None)
        result = invoke([tools / "apksigner", "sign", "--ks", keyfile, "--ks-key-alias", RELEASE_ALIAS,
                         "--ks-pass", "env:KEYSTORE_PASSWORD", "--key-pass", "env:KEY_PASSWORD",
                         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
                         "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                         "--out", staged, apk], env=child_env)
        if result.returncode:
            raise PolicyError("apksigner signing failed; check GitHub signing secrets privately")
        outdir.mkdir(parents=True, exist_ok=True)
        os.replace(staged, output)
        (outdir / "SHA256SUMS").write_text(hashlib.sha256(output.read_bytes()).hexdigest() + "  " + output.name + "\n")
    return output.name


def sign(apk, outdir, tools):
    # With secrets configured, reject a wrong alias before touching any tool,
    # exactly as before; without secrets there is no alias to check.
    if signing_mode() == "starx" and os.environ["KEY_ALIAS"] != RELEASE_ALIAS:
        raise PolicyError("KEY_ALIAS must match the pinned starx alias")
    info = metadata(apk, tools)
    if info["package"] != RELEASE_PACKAGE:
        raise PolicyError("Release input must have the starx application ID; debug APKs cannot be signed here")
    require_unsigned(apk, tools)
    if signing_mode() == "debug":
        return _sign_debug(apk, outdir, tools, info)
    return _sign_starx(apk, outdir, tools, info)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "android/app/build/outputs/apk/release/app-release-unsigned.apk")
    parser.add_argument("--outdir", type=Path, default=ROOT / "release-artifacts")
    parser.add_argument("--build-tools")
    args = parser.parse_args()
    try:
        name = sign(args.input, args.outdir, tools_path(args.build_tools))
        mode = signing_mode()
        output_file = os.environ.get("GITHUB_OUTPUT")
        if output_file:
            with open(output_file, "a", encoding="utf-8") as handle:
                handle.write(f"signing_mode={mode}\n")
        if mode == "debug":
            print("Debug-signed release created (not certificate-verified): " + name)
        else:
            print("Starx release created (certificate not verified): " + name)
    except PolicyError as error:
        parser.exit(1, f"Release signing refused: {error}\n")
    except Exception:
        # Password errors and malformed keystores must never echo private tool diagnostics.
        parser.exit(1, "Release signing refused: signing step failed; no release published\n")


if __name__ == "__main__":
    main()
