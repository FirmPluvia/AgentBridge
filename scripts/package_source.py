"""Create a GitHub-uploadable source archive without local secrets/build outputs."""
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
target = root / "artifacts" / "AgentBridge-source.zip"
excluded = {".git", ".gradle", "build", ".venv", "__pycache__", ".pytest_cache", "artifacts", "relay-data"}
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if not path.is_file() or excluded.intersection(relative.parts):
            continue
        if path.name in {".env", "local.properties"} or any(part.endswith(".egg-info") for part in relative.parts):
            continue
        if path.suffix in {".jks", ".keystore", ".pyc"}:
            continue
        archive.write(path, "AgentBridge/" + str(relative))
print(target)
