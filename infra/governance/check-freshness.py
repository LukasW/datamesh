#!/usr/bin/env python3
"""
Freshness & Governance Reporter: queries Schema Registry and reports
on registered schemas, compatibility levels, and version history.

This is a reporting tool (exit 0 always). Use schema-compat-check.sh
as the hard gate in CI.
"""
import os
import sys
from datetime import datetime

import requests

SCHEMA_REGISTRY_URL = os.getenv("SCHEMA_REGISTRY_URL", "http://localhost:8081").rstrip("/")


def _get(path: str) -> dict | list | None:
    try:
        resp = requests.get(f"{SCHEMA_REGISTRY_URL}{path}", timeout=5)
        resp.raise_for_status()
        return resp.json()
    except requests.RequestException as exc:
        print(f"  WARNING: Schema Registry unreachable ({exc})")
        return None


def report() -> None:
    print(f"\n{'─' * 70}")
    print(f"  Governance Freshness Report  –  {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  Schema Registry: {SCHEMA_REGISTRY_URL}")
    print(f"{'─' * 70}")

    subjects = _get("/subjects")
    if subjects is None:
        print("  ERROR: Cannot connect to Schema Registry. Is it running?")
        print(f"{'─' * 70}\n")
        return

    if not subjects:
        print("  No schemas registered yet.")
        print(f"{'─' * 70}\n")
        return

    print(f"\n  {'Subject':<45} {'Versions':>8}  {'Compatibility':<20}  Status")
    print(f"  {'─' * 45}  {'─' * 8}  {'─' * 20}  {'─' * 10}")

    warnings = []
    for subject in sorted(subjects):
        versions = _get(f"/subjects/{subject}/versions") or []
        version_count = len(versions)

        config = _get(f"/config/{subject}")
        if config:
            compat = config.get("compatibilityLevel", "INHERITED")
        else:
            global_cfg = _get("/config") or {}
            compat = global_cfg.get("compatibilityLevel", "BACKWARD")

        # Determine status
        if compat in ("FULL_TRANSITIVE", "FULL"):
            status = "✅ OK"
        elif compat in ("BACKWARD", "BACKWARD_TRANSITIVE", "FORWARD", "FORWARD_TRANSITIVE"):
            status = "⚠️  PARTIAL"
            warnings.append(f"{subject}: compatibility={compat} (recommend FULL_TRANSITIVE)")
        elif compat in ("NONE", "INHERITED"):
            status = "❌ WEAK"
            warnings.append(f"{subject}: compatibility={compat} (no schema evolution protection)")
        else:
            status = "❓ UNKNOWN"

        print(f"  {subject:<45} {version_count:>8}  {compat:<20}  {status}")

    print(f"\n  Total subjects: {len(subjects)}")

    if warnings:
        print(f"\n  Governance Warnings ({len(warnings)}):")
        for w in warnings:
            print(f"    ⚠  {w}")
        print()
        print("  Recommendation: set FULL_TRANSITIVE compatibility on all subjects.")
        print("  Command: curl -X PUT http://schema-registry:8081/config -d")
        print('           \'{"compatibility": "FULL_TRANSITIVE"}\'')

    print(f"\n{'─' * 70}\n")


if __name__ == "__main__":
    report()
    sys.exit(0)
