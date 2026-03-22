# Data Mesh Migration Tasks

Derived from [datamesh-plan.md](datamesh-plan.md).

---

## Phase 1: Lakehouse Foundation

### MinIO (S3-compatible Object Store)

- [x] Add `minio` service to `docker-compose.yaml` (port 9000 API + 9001 Console)
- [x] Add MinIO init container to create `warehouse` bucket on startup
- [x] Add `minio-data` volume to `docker-compose.yaml`

### Nessie (Iceberg Catalog)

- [x] Add `nessie` service to `docker-compose.yaml` (port 19120)
- [x] Configure Nessie backend (in-memory or RocksDB for dev)

### Trino (Query Engine)

- [x] Create `infra/trino/` directory with catalog and config files
- [x] Configure Iceberg-Nessie-S3 catalog pointing to MinIO + Nessie
- [x] Add `trino` service to `docker-compose.yaml` (port 8086)
- [x] Verify: Run sample SQL query against Iceberg tables via Trino

### Apache Superset (BI)

- [x] Create `infra/superset/` directory with bootstrap config
- [x] Add `superset` service to `docker-compose.yaml` (port 8088)
- [x] Configure Trino datasource via `sqlalchemy-trino`
- [x] Configure Keycloak OIDC SSO integration
- [x] Configure Row-Level Security for PII fields
- [x] Verify: Superset running and healthy (port 8088), Trino datasource configured

### Iceberg Kafka Connector

- [x] Extend `debezium-connect` Dockerfile to include Iceberg Sink Connector plugin
- [x] Create Iceberg sink connector configs under `infra/debezium/` for each domain topic
- [x] Verify: Iceberg sink connectors RUNNING, Parquet files written to MinIO

---

## Phase 2: Transformation Layer (SQLMesh)

- [x] Create `infra/sqlmesh/` directory with config files
- [x] Configure SQLMesh to connect to Trino (Iceberg catalog)
- [x] Port dbt model `dim_partner` to SQLMesh SQL
- [x] Port dbt model `dim_product` to SQLMesh SQL
- [x] Port dbt model `fact_policies` to SQLMesh SQL
- [x] Port remaining dbt models to SQLMesh SQL
- [x] Add `sqlmesh` service to `docker-compose.yaml`
- [x] Verify: SQLMesh configured with Trino gateway and OpenLineage integration

---

## Phase 3: Governance & Compliance

### OpenMetadata (Metadata Catalog)

- [x] Add OpenMetadata PostgreSQL database service to `docker-compose.yaml`
- [x] Add OpenMetadata Elasticsearch service to `docker-compose.yaml`
- [x] Add `openmetadata-server` service to `docker-compose.yaml` (port 8585)
- [x] Add `openmetadata-ingestion` service to `docker-compose.yaml`
- [x] Configure ingestion connectors for Kafka topics
- [x] Configure ingestion connectors for Iceberg tables
- [x] Configure ingestion connectors for ODC contracts
- [x] Set up PII classification tags and retention policies
- [x] Verify: OpenMetadata server running (migrations in progress), ingestion configs deployed

### Marquez / OpenLineage (Lineage Tracking)

- [x] Add Marquez PostgreSQL database service to `docker-compose.yaml`
- [x] Add `marquez` API service to `docker-compose.yaml` (port 5050)
- [x] Add `marquez-web` UI service to `docker-compose.yaml` (port 3001)
- [x] Integrate OpenLineage emitters into SQLMesh
- [x] Integrate OpenLineage emitters into Iceberg Kafka Connector
- [x] Configure OpenMetadata to display Marquez lineage
- [x] Verify: Marquez API and Web UI running, OpenLineage integration configured

### HashiCorp Vault (Crypto-Shredding / ADR-009)

- [x] Add `vault` service to `docker-compose.yaml` (port 8200, dev mode)
- [x] Implement per-entity AES-256 key generation (keyed by `partner/{personId}`)
- [x] Modify domain event publishers to encrypt PII fields before writing to Kafka
- [x] Implement key deletion endpoint for GDPR/nDSG right-to-erasure
- [x] Verify: Crypto-shredding works — Vault key auto-created on person creation, destroyed on deletion

### Soda Core (Data Quality)

- [x] Create `infra/soda/` directory with Soda check definitions
- [x] Derive SodaCL checks from existing ODC contracts (null rates, duplicates, freshness)
- [x] Integrate Soda Core as a quality gate in the Iceberg ingest pipeline
- [x] Verify: Soda Core configured with SodaCL checks for all domains

---

## Phase 4: Remove Legacy Services

### Analytics Layer Removal

- [x] Remove `platform-db` service from `docker-compose.yaml`
- [x] Remove `platform-consumer` service from `docker-compose.yaml`
- [x] Remove `spark-streaming` service from `docker-compose.yaml`
- [x] Remove `dbt` service from `docker-compose.yaml`
- [x] Delete `infra/platform/` directory
- [x] Delete `infra/spark/` directory
- [x] Delete `infra/dbt/` directory

### Scheduling Removal

- [x] Remove `airflow-db` service from `docker-compose.yaml`
- [x] Remove `airflow-init` service from `docker-compose.yaml`
- [x] Remove `airflow-scheduler` service from `docker-compose.yaml`
- [x] Remove `airflow-webserver` service from `docker-compose.yaml`
- [x] Delete `infra/airflow/` directory

### Metadata & Governance Removal

- [x] Remove all 8 DataHub containers from `docker-compose.yaml`
- [x] Delete `infra/datahub/` directory

### Custom Portal & Governance Removal

- [x] Remove `governance` service from `docker-compose.yaml`
- [x] Remove `portal` service from `docker-compose.yaml`
- [x] Delete `infra/governance/` directory
- [x] Delete `infra/portal/` directory

### Volume Cleanup

- [x] Remove volumes: `platform-db-data`, `spark-warehouse`, `spark-ivy2`, `airflow-db-data`, `datahub-mysql-data`, `datahub-esdata`

### Verification

- [x] Run `podman compose up` — 29 services start cleanly with new lakehouse stack

---

## Phase 5: Documentation Updates

- [x] Update `specs/arc42.md` Section 5 (Building Block View)
- [x] Update `specs/arc42.md` Section 7 (Deployment View)
- [x] Update `docs/services-overview.md` with new service descriptions
- [x] Update `CLAUDE.md` tech stack table
- [x] Add ADR-009 (Crypto-Shredding) to the ADR list — already exists in arc42.md
