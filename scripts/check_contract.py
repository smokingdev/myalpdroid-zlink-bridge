#!/usr/bin/env python3
"""Check the partial AIDL against the pinned full interface; no network needed."""
from pathlib import Path
import hashlib
import re

root = Path(__file__).resolve().parents[1]
source = (root / "contract/IOsmAndAidlInterface.aidl").read_text()
source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
methods = re.findall(r"\b\w+(?:<[^>]+>)?\s+(\w+)\s*\([^;]*?\)\s*;", source)
index = methods.index("getAppInfo")
partial = (root / "app/src/main/aidl/net/osmand/aidlapi/IOsmAndAidlInterface.aidl").read_text()
assert re.search(rf"AppInfoParams\s+getAppInfo\(\)\s*=\s*{index}\s*;", partial)
for relative in ("AidlParams.java", "info/AppInfoParams.java", "map/ALatLon.java"):
    vendored = root / "app/src/main/java/net/osmand/aidlapi" / relative
    assert vendored.read_bytes() == (root / "contract" / vendored.name).read_bytes(), relative
checksums = (root / "contract/SHA256SUMS").read_text().splitlines()
for line in checksums:
    digest, filename = line.split()
    assert hashlib.sha256((root / "contract" / filename).read_bytes()).hexdigest() == digest, filename
print(f"Contract OK: getAppInfo index {index}, Binder transaction {index + 1}; parcelables unchanged.")
