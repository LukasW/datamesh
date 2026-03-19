#!/usr/bin/env python3
"""
Ingests ODC (Open Data Contract) metadata into DataHub as custom properties.

Reads all *.odcontract.yaml files from partner/policy/product contracts directories
and maps the dataProduct section to DataHub Dataset entities (Kafka platform).

Only "Data Outside" topics are ingested — operational DBs are not touched (ADR-004).

Usage:
    pip install -r infra/datahub/requirements.txt
    python infra/datahub/ingest_odc.py
"""
import os
import sys
from pathlib import Path

import yaml
from datahub.emitter.mce_builder import make_dataset_urn, make_tag_urn, make_user_urn
from datahub.emitter.mcp import MetadataChangeProposalWrapper
from datahub.emitter.rest_emitter import DatahubRestEmitter
from datahub.metadata.schema_classes import (
    DatasetPropertiesClass,
    GlobalTagsClass,
    OwnerClass,
    OwnershipClass,
    OwnershipTypeClass,
    TagAssociationClass,
)

DATAHUB_GMS_URL = os.getenv("DATAHUB_GMS_URL", "http://localhost:8080")
ENV = "PROD"

# When CONTRACTS_ROOT is set (e.g. in Docker: /contracts), expects subdirs partner/policy/product.
# Otherwise falls back to project-relative paths.
_CONTRACTS_ROOT = os.getenv("CONTRACTS_ROOT")
if _CONTRACTS_ROOT:
    _CONTRACTS_DIRS_RESOLVED = [Path(_CONTRACTS_ROOT) / d for d in ("partner", "policy", "product")]
else:
    _project_root = Path(__file__).parent.parent.parent
    _CONTRACTS_DIRS_RESOLVED = [
        _project_root / d
        for d in (
            "partner/src/main/resources/contracts",
            "policy/src/main/resources/contracts",
            "product/src/main/resources/contracts",
        )
    ]


def load_odc_files(project_root: Path):
    for contracts_dir in _CONTRACTS_DIRS_RESOLVED:
        for path in sorted(contracts_dir.glob("*.odcontract.yaml")):
            with open(path) as f:
                yield path.name, yaml.safe_load(f)


def build_custom_properties(dp: dict) -> dict:
    props = {
        "odc.owner": dp.get("owner", ""),
        "odc.domain": dp.get("domain", ""),
        "odc.outputPort": dp.get("outputPort", ""),
    }
    sla = dp.get("sla", {})
    if sla.get("freshness"):
        props["odc.sla.freshness"] = str(sla["freshness"])
    if sla.get("availability"):
        props["odc.sla.availability"] = str(sla["availability"])
    if sla.get("qualityScore") is not None:
        props["odc.sla.qualityScore"] = str(sla["qualityScore"])
    return {k: v for k, v in props.items() if v}


def main():
    project_root = Path(__file__).parent.parent.parent  # unused when CONTRACTS_ROOT is set

    emitter = DatahubRestEmitter(gms_server=DATAHUB_GMS_URL)
    success = 0
    errors = 0

    for filename, contract in load_odc_files(project_root):
        # Support multiple ODC schema versions:
        #   v1:     spec.topic  or  metadata.name
        #   v2.2.2: servers (list) → servers[0].topic
        #   v2.3.0: servers (dict) → servers.production.topic
        topic = (contract.get("spec") or {}).get("topic") or (
            contract.get("metadata") or {}
        ).get("name")
        if not topic:
            servers = contract.get("servers")
            if isinstance(servers, list) and servers:
                topic = (servers[0] or {}).get("topic")
            elif isinstance(servers, dict):
                topic = (servers.get("production") or servers.get("development") or {}).get("topic")
        if not topic:
            print(f"  SKIP {filename}: no topic found")
            continue

        # Support both explicit dataProduct section (v1) and info.* fields (v2.x)
        dp = contract.get("dataProduct") or {}
        if not dp:
            info = contract.get("info") or {}
            if info:
                dp = {
                    "owner": info.get("owner") or (info.get("contact") or {}).get("email", ""),
                    "domain": info.get("domain", ""),
                    "outputPort": info.get("outputPort", "kafka"),
                    "sla": info.get("sla", {}),
                    "tags": info.get("tags", []),
                }
        if not dp:
            print(f"  SKIP {filename}: no dataProduct section")
            continue

        dataset_urn = make_dataset_urn(platform="kafka", name=topic, env=ENV)

        try:
            description = (contract.get("metadata") or {}).get("description", "")
            custom_props = build_custom_properties(dp)

            # Dataset properties + custom properties
            emitter.emit(
                MetadataChangeProposalWrapper(
                    entityUrn=dataset_urn,
                    aspect=DatasetPropertiesClass(
                        description=description,
                        customProperties=custom_props,
                    ),
                )
            )

            # Tags (pii, gdpr-subject, …)
            tags = dp.get("tags") or []
            if tags:
                emitter.emit(
                    MetadataChangeProposalWrapper(
                        entityUrn=dataset_urn,
                        aspect=GlobalTagsClass(
                            tags=[TagAssociationClass(tag=make_tag_urn(t)) for t in tags]
                        ),
                    )
                )

            # Ownership
            owner_email = dp.get("owner", "")
            if owner_email:
                emitter.emit(
                    MetadataChangeProposalWrapper(
                        entityUrn=dataset_urn,
                        aspect=OwnershipClass(
                            owners=[
                                OwnerClass(
                                    owner=make_user_urn(owner_email),
                                    type=OwnershipTypeClass.TECHNICAL_OWNER,
                                )
                            ]
                        ),
                    )
                )

            print(f"  ✓ {topic}")
            success += 1

        except Exception as exc:
            print(f"  ✗ {topic}: {exc}")
            errors += 1

    print(f"\nDone: {success} ingested, {errors} errors")
    if errors:
        sys.exit(1)


if __name__ == "__main__":
    main()
