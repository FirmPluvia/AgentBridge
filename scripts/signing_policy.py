"""Public, pinned signing policy. No secret values belong in this module."""
import re
from pathlib import Path
import subprocess
import zipfile

DEBUG_PACKAGE = "dev.agentbridge"
RELEASE_PACKAGE = "starx." + DEBUG_PACKAGE
RELEASE_ALIAS = "starx"
RELEASE_SHA256 = "E7:D1:77:11:20:91:50:25:19:99:E5:DE:B2:2A:B3:65:C8:26:D7:5A:0D:23:0B:DC:1F:44:0D:8F:34:23:17:0E"
SECRET_NAMES = ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")


class PolicyError(Exception):
    """Contains only fixed, safe diagnostics, never subprocess stderr or credentials."""


def invoke(arguments, *, env=None):
    try:
        return subprocess.run([str(x) for x in arguments], env=env, capture_output=True, text=True, check=False)
    except (OSError, UnicodeError):
        raise PolicyError("Required Android build tool could not run") from None


def metadata(apk, tools):
    result = invoke([tools / "aapt", "dump", "badging", apk])
    match = re.search(r"^package: name='([^']+)'.*?versionCode='([^']+)'.*?versionName='([^']+)'", result.stdout, re.M)
    if result.returncode or not match:
        raise PolicyError("APK package metadata could not be verified")
    return dict(package=match[1], versionCode=match[2], versionName=match[3])


def verification(apk, tools):
    return invoke([tools / "apksigner", "verify", "--verbose", "--print-certs", apk])


def digest_from_report(report):
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$", report, re.M)
    if len(digests) != 1 or len(digests[0].replace(":", "")) != 64:
        raise PolicyError("Exactly one verified APK signer is required")
    raw = digests[0].replace(":", "").upper()
    return ":".join(raw[i:i + 2] for i in range(0, 64, 2))


def require_unsigned(apk, tools):
    result = verification(apk, tools)
    if result.returncode != 1:
        raise PolicyError("Release input must be an unsigned APK")
    try:
        with zipfile.ZipFile(apk) as archive:
            if any(re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC|SF)", n, re.I) for n in archive.namelist()):
                raise PolicyError("Release input contains a prior signing identity")
        if b"APK Sig Block 42" in Path(apk).read_bytes():
            raise PolicyError("Release input contains an APK signing block")
    except (OSError, zipfile.BadZipFile):
        raise PolicyError("Release input is not a readable unsigned APK") from None


def tools_path(value=None):
    if value:
        return Path(value)
    import os
    root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not root:
        raise PolicyError("ANDROID_HOME or --build-tools is required")
    return Path(root) / "build-tools" / "36.0.0"
