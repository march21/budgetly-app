#!/usr/bin/env python3
"""Manifeste de mise à jour Tauri (latest.json) à partir des installeurs signés d'une version.

    latest_json.py <dossier des paquets> <owner/repo> <tag>
"""
import datetime
import json
import pathlib
import sys

folder, repo, tag = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
targets = {"windows-x86_64": "*-setup.exe", "linux-x86_64": "*.deb", "darwin-aarch64": "*.app.tar.gz"}
platforms = {}
for key, pattern in targets.items():
    found = sorted(folder.glob(pattern))
    if not found:
        continue
    package = found[0]
    signature = package.with_name(package.name + ".sig")
    if not signature.exists():
        sys.exit(f"Signature absente pour {package.name}")
    platforms[key] = {
        "signature": signature.read_text().strip(),
        "url": f"https://github.com/{repo}/releases/download/{tag}/{package.name}",
    }
if "windows-x86_64" not in platforms:
    sys.exit("Installeur Windows absent")
print(json.dumps({
    "version": tag.removeprefix("v"),
    "notes": f"https://github.com/{repo}/releases/tag/{tag}",
    "pub_date": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "platforms": platforms,
}, indent=2))
