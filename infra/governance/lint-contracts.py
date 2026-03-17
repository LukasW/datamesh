#!/usr/bin/env python3
"""
lint-contracts.py – Validates all ODC YAML files for mandatory governance fields.

Supports:
  - ODC v1 format: dataProduct is a top-level key
  - ODC v2.2.2 format: dataProduct fields are distributed across info/servers/terms

Exit codes:
  0  all contracts pass
  1  one or more contracts fail
"""

import os
import sys
import glob
import yaml

# ---------------------------------------------------------------------------
# Path resolution: container vs. local dev
# ---------------------------------------------------------------------------

CONTAINER_BASE = "/contracts"
LOCAL_BASE = os.path.join(os.path.dirname(__file__), "../..")

DOMAINS = ["partner", "policy", "product"]


def contract_dirs():
    """Return the list of directories to scan for *.odcontract.yaml files."""
    if os.path.isdir(CONTAINER_BASE):
        return [os.path.join(CONTAINER_BASE, d) for d in DOMAINS]
    return [
        os.path.join(LOCAL_BASE, d, "src/main/resources/contracts")
        for d in DOMAINS
    ]


# ---------------------------------------------------------------------------
# ODC field extraction helpers
# ---------------------------------------------------------------------------

def extract_v1(doc: dict) -> dict:
    """Extract governance fields from ODC v1 format (top-level dataProduct key)."""
    dp = doc.get("dataProduct", {}) or {}
    sla = dp.get("sla", {}) or {}
    return {
        "owner":        dp.get("owner"),
        "domain":       dp.get("domain"),
        "outputPort":   dp.get("outputPort"),
        "freshness":    sla.get("freshness"),
        "availability": sla.get("availability"),
        "qualityScore": sla.get("qualityScore"),
        "tags":         dp.get("tags"),       # None means missing; [] means empty but present
    }


def extract_v2(doc: dict) -> dict:
    """Extract governance fields from ODC v2.2.2 format (info + servers blocks)."""
    info = doc.get("info", {}) or {}
    servers = doc.get("servers") or []
    sla = info.get("sla", {}) or {}

    # outputPort: present when at least one server entry exists
    output_port = servers[0].get("type") if servers else None

    return {
        "owner":        info.get("owner") or info.get("contact", {}).get("email"),
        "domain":       info.get("domain") or doc.get("id", "").split(".")[0] or None,
        "outputPort":   output_port,
        "freshness":    sla.get("freshness"),
        "availability": sla.get("availability"),
        "qualityScore": sla.get("qualityScore"),
        "tags":         info.get("tags"),
    }


def extract_fields(doc: dict) -> dict:
    """Dispatch to the correct extractor based on apiVersion."""
    api_version = str(doc.get("apiVersion", "")).strip()
    if api_version.startswith("v2"):
        return extract_v2(doc)
    return extract_v1(doc)


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------

RULES = [
    ("owner",        lambda f: bool(f["owner"]),        "missing owner (email)"),
    ("domain",       lambda f: bool(f["domain"]),        "missing domain"),
    ("outputPort",   lambda f: bool(f["outputPort"]),    "missing outputPort"),
    ("freshness",    lambda f: f["freshness"] is not None, "missing sla.freshness"),
    ("availability", lambda f: f["availability"] is not None, "missing sla.availability"),
    ("qualityScore", lambda f: f["qualityScore"] is not None, "missing sla.qualityScore"),
    ("tags",         lambda f: f["tags"] is not None,    "missing tags field"),
]


def validate(fields: dict) -> list[str]:
    """Return a list of violated rule messages (empty list = PASS)."""
    return [msg for _, check, msg in RULES if not check(fields)]


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def lint_file(path: str) -> bool:
    """Lint a single ODC YAML file. Returns True if the file passes."""
    filename = os.path.basename(path)
    try:
        with open(path, encoding="utf-8") as fh:
            doc = yaml.safe_load(fh)
    except yaml.YAMLError as exc:
        print(f"  FAIL  {filename}  → YAML parse error: {exc}")
        return False

    if not isinstance(doc, dict):
        print(f"  SKIP  {filename}  → not a YAML mapping, ignored")
        return True

    # Require DataContract kind
    if doc.get("kind") != "DataContract":
        print(f"  SKIP  {filename}  → kind != DataContract, ignored")
        return True

    fields = extract_fields(doc)
    violations = validate(fields)

    if violations:
        for v in violations:
            print(f"  FAIL  {filename}  → {v}")
        return False

    print(f"  PASS  {filename}")
    return True


def main() -> int:
    dirs = contract_dirs()
    all_files = []
    for d in dirs:
        pattern = os.path.join(d, "*.odcontract.yaml")
        all_files.extend(sorted(glob.glob(pattern)))

    if not all_files:
        print("No *.odcontract.yaml files found. Check mount paths.")
        return 1

    print(f"Linting {len(all_files)} contract(s)...\n")
    results = [lint_file(f) for f in all_files]

    total = len(results)
    passed = sum(results)
    failed = total - passed
    print(f"\n{'='*50}")
    print(f"Result: {passed}/{total} passed, {failed} failed")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
