#!/usr/bin/env python3
"""Fail when X-NLP release metadata does not match the requested version."""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = sys.argv[1] if len(sys.argv) > 1 else "0.4.0"
MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
errors: list[str] = []


def require_equal(label: str, actual: str | None) -> None:
    if actual != EXPECTED:
        errors.append(f"{label}: expected {EXPECTED!r}, found {actual!r}")


def require_pattern(label: str, path: Path, pattern: str) -> None:
    if re.search(pattern, path.read_text(), flags=re.MULTILINE) is None:
        errors.append(f"{label}: {path.relative_to(ROOT)} does not declare {EXPECTED}")


parent_pom = ET.parse(ROOT / "pom.xml").getroot()
require_equal("Maven parent project", parent_pom.findtext("m:version", namespaces=MAVEN_NAMESPACE))

for module in ("xnlp-core", "xnlp-server", "xnlp-client", "xnlp-cli"):
    pom = ET.parse(ROOT / module / "pom.xml").getroot()
    require_equal(
        f"Maven {module} parent",
        pom.findtext("m:parent/m:version", namespaces=MAVEN_NAMESPACE),
    )

package = json.loads((ROOT / "xnlp-frontend/package.json").read_text())
package_lock = json.loads((ROOT / "xnlp-frontend/package-lock.json").read_text())
require_equal("npm package", package.get("version"))
require_equal("npm lock root", package_lock.get("version"))
require_equal("npm lock workspace", package_lock.get("packages", {}).get("", {}).get("version"))

chart_path = ROOT / "deploy/helm/xnlp/Chart.yaml"
values_path = ROOT / "deploy/helm/xnlp/values.yaml"
require_pattern("Helm chart version", chart_path, rf"^version:\s*{re.escape(EXPECTED)}\s*$")
require_pattern("Helm appVersion", chart_path, rf'^appVersion:\s*["\']?{re.escape(EXPECTED)}["\']?\s*$')
image_tags = re.findall(r'^\s*tag:\s*["\']?([^"\'\s]+)["\']?\s*$', values_path.read_text(), re.MULTILINE)
if image_tags != [EXPECTED, EXPECTED]:
    errors.append(f"Helm image tags: expected two {EXPECTED!r} tags, found {image_tags!r}")

for image, path in (
    ("server", ROOT / "Dockerfile"),
    ("frontend", ROOT / "xnlp-frontend/Dockerfile"),
):
    require_pattern(f"{image} image build version", path, rf"^ARG XNLP_VERSION={re.escape(EXPECTED)}$")
    require_pattern(f"{image} image OCI version", path, r"org\.opencontainers\.image\.version=\"\$\{XNLP_VERSION\}\"")

current_runtime_files = [
    ROOT / "README.md",
    ROOT / "AGENTS.md",
    ROOT / "SPEC.md",
    ROOT / "docs/codex/SOP.md",
    ROOT / "scripts/codex/env-status.sh",
    ROOT / "scripts/codex/smoke-api.sh",
]
stale_jar = re.compile(rf"xnlp-(?:server|cli)-(?!{re.escape(EXPECTED)}\.jar)[0-9]+\.[0-9]+\.[0-9]+\.jar")
for path in current_runtime_files:
    matches = sorted(set(stale_jar.findall(path.read_text())))
    if matches:
        errors.append(f"{path.relative_to(ROOT)} contains stale runtime artifacts: {matches}")

if errors:
    print("Release version alignment failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)

print(f"Release version alignment passed: {EXPECTED}")
