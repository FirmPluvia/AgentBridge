"""Post-sign an unsigned starx APK using four GitHub Secrets injected into this step only."""
import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import tempfile
from signing_policy import (RELEASE_ALIAS, RELEASE_PACKAGE, SECRET_NAMES, PolicyError,
                            metadata, invoke, require_unsigned, tools_path)
from verify_android_artifact import verify


ROOT = Path(__file__).resolve().parents[1]


def sign(apk, outdir, tools):
    if any(not os.environ.get(name) for name in SECRET_NAMES):
        raise PolicyError("Missing required GitHub signing secrets; no signed release will be produced")
    if os.environ["KEY_ALIAS"] != RELEASE_ALIAS:
        raise PolicyError("KEY_ALIAS must match the pinned starx alias")
    info = metadata(apk, tools)
    if info["package"] != RELEASE_PACKAGE:
        raise PolicyError("Release input must have the starx application ID; debug APKs cannot be signed here")
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
    temporary_root = Path(os.environ.get("RUNNER_TEMP") or tempfile.gettempdir()).resolve()
    if temporary_root == ROOT or ROOT in temporary_root.parents:
        raise PolicyError("Signing temporary directory must be outside the source repository")
    if not all(ch.isalnum() or ch in ".-_" for ch in info["versionName"]):
        raise PolicyError("Unsupported APK version name")
    output = outdir / f"AgentBridge-v{info['versionName']}-starx-release.apk"
    if output.exists():
        raise PolicyError("Release output already exists; use a clean artifact directory")
    with tempfile.TemporaryDirectory(prefix="agentbridge-starx-", dir=temporary_root) as temporary:
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
        checked = verify(staged, "release", tools)
        outdir.mkdir(parents=True, exist_ok=True)
        os.replace(staged, output)
        # Only verified public identity metadata is published; raw tool output stays private.
        (outdir / "RELEASE-VERIFICATION.json").write_text(json.dumps(checked, ensure_ascii=False, indent=2) + "\n")
        (outdir / "SHA256SUMS").write_text(hashlib.sha256(output.read_bytes()).hexdigest() + "  " + output.name + "\n")
    return output.name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "android/app/build/outputs/apk/release/app-release-unsigned.apk")
    parser.add_argument("--outdir", type=Path, default=ROOT / "release-artifacts")
    parser.add_argument("--build-tools")
    args = parser.parse_args()
    try:
        name = sign(args.input, args.outdir, tools_path(args.build_tools))
        print("Verified starx release created: " + name)
    except PolicyError as error:
        parser.exit(1, f"Release signing refused: {error}\n")
    except Exception:
        # Password errors and malformed keystores must never echo private tool diagnostics.
        parser.exit(1, "Release signing refused: signing step failed; no verified release published\n")


if __name__ == "__main__":
    main()
