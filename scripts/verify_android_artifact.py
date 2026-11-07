"""Verify public APK identity. Debug verification never reads signing secrets."""
import argparse
import json
from pathlib import Path
from signing_policy import (DEBUG_PACKAGE, RELEASE_PACKAGE, RELEASE_SHA256, PolicyError,
                            metadata, verification, digest_from_report, require_unsigned, tools_path)


def verify(apk, kind, tools):
    info = metadata(apk, tools)
    expected = DEBUG_PACKAGE if kind == "debug" else RELEASE_PACKAGE
    if info["package"] != expected:
        raise PolicyError("APK application ID does not match the requested build kind")
    if kind == "unsigned-release":
        require_unsigned(apk, tools)
        return {**info, "signing": "UNSIGNED (not installable)", "verified": True}
    result = verification(apk, tools)
    if result.returncode:
        raise PolicyError("APK signature verification failed")
    fingerprint = digest_from_report(result.stdout)
    if kind == "debug":
        if fingerprint == RELEASE_SHA256 or "CN=Android Debug" not in result.stdout:
            raise PolicyError("Debug APK must use the default Android debug identity")
    elif fingerprint != RELEASE_SHA256:
        raise PolicyError("Release certificate does not match the pinned starx fingerprint")
    return {**info, "signing": "Android debug" if kind == "debug" else "starx release",
            "certificateSha256": fingerprint, "verified": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--kind", choices=("debug", "unsigned-release", "release"), required=True)
    parser.add_argument("--build-tools")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        data = verify(args.apk, args.kind, tools_path(args.build_tools))
        text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(text)
        print(text, end="")
    except PolicyError as error:
        parser.exit(1, f"Verification failed: {error}\n")


if __name__ == "__main__":
    main()
