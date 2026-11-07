"""Package a verified local Android build. Requires ANDROID_HOME and a prior Gradle test/lint build."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from verify_android_artifact import verify

root = Path(__file__).resolve().parents[1]
out = root / "artifacts"
out.mkdir(exist_ok=True)
gradle = (root / "android/app/build.gradle.kts").read_text()
version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1]
version_code = re.search(r'versionCode\s*=\s*(\d+)', gradle)[1]
sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
apk = root / "android/app/build/outputs/apk/debug/app-debug.apk"

def run(*args):
    return subprocess.run([str(x) for x in args], capture_output=True, text=True, check=True).stdout

debug_identity = verify(apk, "debug", sdk)
signed = run(sdk / "apksigner", "verify", "--verbose", "--print-certs", apk)
badging = run(sdk / "aapt", "dump", "badging", apk)
assert f"versionName='{version}'" in badging and f"versionCode='{version_code}'" in badging
assert "application-label:'AgentBridge'" in badging
activity = next(line for line in badging.splitlines() if line.startswith("launchable-activity:"))
assert "label='AgentBridge'" in activity and "icon=''" not in activity
monochrome = run(sdk / "aapt", "dump", "xmltree", apk, "res/mipmap-anydpi-v33/ic_launcher.xml")
assert "monochrome" in monochrome
with zipfile.ZipFile(apk) as z:
    icon_path = re.search(r"icon='([^']+)'", activity)[1]
    assert icon_path in z.namelist()

prefix = f"AgentBridge-v{version}"
shutil.copyfile(apk, out / f"{prefix}-debug.apk")
shutil.copyfile(apk, out / "AgentBridge-debug.apk")
release = root / "android/app/build/outputs/apk/release/app-release-unsigned.apk"
if release.exists():
    release_identity = verify(release, "unsigned-release", sdk)
    shutil.copyfile(release, out / f"{prefix}-starx-release-UNSIGNED.apk")
    old_unsigned = out / f"{prefix}-release-UNSIGNED.apk"
    old_unsigned.unlink(missing_ok=True)
report = out / f"apk-verification-v{version.replace('.', '')}.txt"
report.write_text(signed + badging + "\nAdaptive icon tree:\n" + monochrome)
reports = out / "reports"
reports.mkdir(exist_ok=True)
(reports / "DEBUG-VERIFICATION.json").write_text(json.dumps(debug_identity, indent=2) + "\n")
if release.exists():
    (reports / "UNSIGNED-RELEASE-VERIFICATION.json").write_text(json.dumps(release_identity, indent=2) + "\n")
for old in reports.glob("TEST-*.xml"):
    old.unlink()
records = []
for p in (root / "android/app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
    d = ET.parse(p).getroot().attrib
    assert d["failures"] == "0" and d["errors"] == "0"
    records.append({k: d[k] for k in ("name", "tests", "failures", "errors")})
    shutil.copyfile(p, reports / p.name)
assert records
for name in ("lint-results-debug.txt", "lint-results-release.txt"):
    shutil.copyfile(root / "android/app/build/reports" / name, reports / name)
summary = out / f"test-summary-v{version.replace('.', '')}.json"
summary.write_text(json.dumps(dict(version=version, versionCode=int(version_code), jvmTests=sum(int(r["tests"]) for r in records), suites=records, actualAndroidDeviceTested=False), ensure_ascii=False, indent=2) + "\n")
with zipfile.ZipFile(out / f"{prefix}-APK.zip", "w", zipfile.ZIP_DEFLATED) as z:
    z.write(apk, f"{prefix}-debug.apk")
with zipfile.ZipFile(out / f"{prefix}-verification.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for p in reports.iterdir():
        if p.is_file(): z.write(p, "reports/" + p.name)
    for p in (report, summary, out / "python-tests.xml"):
        if p.exists(): z.write(p, p.name)
subprocess.run([sys.executable, str(root / "scripts/package_source.py")], check=True)
shutil.copyfile(out / "AgentBridge-source.zip", out / f"{prefix}-source.zip")
files = [p for p in out.glob(f"{prefix}-*") if p.suffix in (".apk", ".zip")]
(out / f"SHA256SUMS-v{version.replace('.', '')}").write_text("".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in sorted(files)))
print(json.dumps({"version": version, "jvmTests": sum(int(r["tests"]) for r in records), "activity": activity, "apkBytes": apk.stat().st_size}, ensure_ascii=False))
