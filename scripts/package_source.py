"""Create a GitHub-uploadable source archive without local secrets/build outputs."""
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
target = root / "artifacts" / "AgentBridge-source.zip"
excluded = {".git", ".gradle", "build", ".venv", "__pycache__", ".pytest_cache", "artifacts", "release-artifacts", "relay-data"}
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if not path.is_file() or excluded.intersection(relative.parts):
            continue
        if path.name in {".env", "local.properties", "keystore.properties", "signing.properties", "connection.json"} or path.name.endswith(".agentbridge-connection.json") or (len(relative.parts) == 1 and path.name.startswith("connection-") and path.suffix == ".json") or any(part.endswith(".egg-info") for part in relative.parts):
            continue
        if path.suffix in {".jks", ".keystore", ".p12", ".pfx", ".b64", ".base64", ".pyc"}:
            continue
        archive.write(path, "AgentBridge/" + str(relative))
print(target)
