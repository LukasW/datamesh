# Review Plan: Sachversicherung Datamesh Platform

**Basiert auf:** `review.md` (2026-04-03)
**Ziel:** Alle identifizierten Verbesserungen systematisch abarbeiten.

---

## P0 -- Kritisch

### Hexagonal Isolation reparieren

- [x] `application/` Package in allen 5 Haupt-Domains anlegen (`partner`, `product`, `policy`, `claims`, `billing`)
- [x] Domain Services (`PersonCommandService`, `PersonQueryService`) aus `domain/service/` in `application/` verschieben
- [x] `PolicyCommandService` / `PolicyQueryService` nach `application/` verschieben
- [x] `ProductCommandService` / `ProductQueryService` nach `application/` verschieben
- [x] `ClaimApplicationService` nach `application/` verschieben
- [x] `InvoiceCommandService` / `InvoiceQueryService` nach `application/` verschieben
- [x] Framework-Imports (`@ApplicationScoped`, `@Inject`, `@Transactional`) aus `domain/service/` entfernen
- [x] Infrastructure-Imports (`OutboxEvent`, `*EventPayloadBuilder`) aus `domain/service/` entfernen
- [x] Outbound-Port-Interface `EventPublisher` in `domain/port/out/` definieren
- [x] `EventPublisher`-Adapter in `infrastructure/messaging/` implementieren (delegiert an Outbox)
- [x] Reine Domain Services (pure Java) in `domain/service/` belassen oder neu erstellen
- [x] Alle Tests nach Refactoring ausfuehren und fixen

---

## P1 -- Hoch

### Domain-Owned Data Products

- [x] Verzeichnisstruktur `{domain}/data-product/` pro Domain anlegen
- [x] SQLMesh Silver Models aus `infra/sqlmesh/models/` in die jeweilige Domain verschieben
- [x] SQLMesh Gold Models den verantwortlichen Domains zuordnen
- [x] Soda Checks aus `infra/soda/checks/` in die jeweilige Domain verschieben
- [x] Iceberg Sink Connector Configs aus `infra/debezium/` in die jeweilige Domain verschieben
- [x] Zentrale `infra/sqlmesh/config.yaml` anpassen (Pfade auf Domain-Verzeichnisse)
- [x] Soda Config anpassen
- [x] Dokumentation der neuen Struktur in CLAUDE.md aktualisieren

### Schema Consistency (Avro vs. JSON)

- [x] Entscheidung treffen: JSON beibehalten (ADR-011)
- [x] Avro-Dependencies aus Parent POM entfernen (`avro.version`, `confluent.version`, `avro-maven-plugin`, Confluent Repository)
- [x] Avro-Schemas (`.avsc`) aus allen Domain `contracts/` entfernen — JSON Schema ist Single Source of Truth
- [x] ~~Falls Avro: `StringConverter` durch `AvroConverter` in Debezium/Kafka ersetzen~~ N/A
- [x] ~~Falls Avro: Schema Registry Integration in allen Producern/Consumern aktivieren~~ N/A
- [x] ADR-011 geschrieben: `docs/adr-011-json-event-serialization.md`

### CI/CD Pipeline

- [ ] GitHub Actions Workflow fuer Build + Unit Tests erstellen (`.github/workflows/build.yml`)
- [ ] Integration Tests in CI einbinden (Testcontainers)
- [ ] Container Image Build in CI aufnehmen
- [ ] Contract-Check Workflow (`contract-check.yml`) mit Build-Workflow verknuepfen
- [ ] Branch-Protection-Rules fuer `main` konfigurieren

---

## P2 -- Mittel

### Inbound Ports

- [x] `domain/port/in/` Package in allen Domains anlegen
- [x] Use-Case-Interfaces definieren (z.B. `CreatePersonUseCase`, `ActivatePolicyUseCase`)
- [x] Application Services implementieren diese Interfaces
- [x] REST-Adapter und Kafka-Consumer auf Use-Case-Interfaces umstellen

### Incremental SQLMesh Models

- [x] Silver Models von `kind FULL` auf `INCREMENTAL_BY_UNIQUE_KEY` / `INCREMENTAL_BY_TIME_RANGE` umstellen
- [x] Timestamp-Spalte als Incremental Key definieren (`@start_date`/`@end_date` filtering)
- [x] Differenziertes SLA pro Model statt globaler `@hourly` Cron
- [x] Backfill-Strategie fuer bestehende Daten planen und testen

### Contract Testing

- [ ] Consumer-Driven Contract Tests fuer Kafka-Events evaluieren (Pact / Spring Cloud Contract)
- [ ] Contract Tests fuer kritische Event-Ketten implementieren (Partner -> Policy -> Claims -> Billing)
- [ ] `DataContractVerificationTest` (in CLAUDE.md referenziert) tatsaechlich implementieren
- [ ] Contract Tests in CI-Pipeline integrieren

### Policy Number Generation

- [ ] `ThreadLocalRandom`-basierte Generierung durch DB-Sequence ersetzen
- [ ] Analog zu `InsuredNumberSequenceAdapter` implementieren
- [ ] Kollisionstests schreiben

### Gold Layer JOINs

- [x] `policy_gold.policy_detail` von INNER JOIN auf LEFT JOIN umstellen
- [x] Sicherstellen, dass Policies ohne Partner/Product nicht verloren gehen
- [x] NULL-Handling fuer fehlende Partner/Product-Daten in Superset-Dashboards pruefen
- [x] Soda Check fuer Vollstaendigkeit der Gold-Tabelle ergaenzen

---

## P3 -- Niedrig

### docker-compose aufteilen

- [ ] `docker-compose.yaml` (~46KB) in Profile oder separate Files aufteilen
  - [ ] `docker-compose.core.yml` (Kafka, Postgres, Keycloak, Vault)
  - [ ] `docker-compose.services.yml` (Domain Services)
  - [ ] `docker-compose.analytics.yml` (Iceberg, Trino, SQLMesh, Soda, Superset)
  - [ ] `docker-compose.observability.yml` (Prometheus, Grafana, Jaeger)
- [ ] `deploy.sh` anpassen fuer selektives Starten

### Helm Charts fuer Kubernetes

- [ ] Helm Chart Struktur fuer Domain Services erstellen
- [ ] Parametrisierbare Values fuer Staging/Prod
- [ ] Bestehende Kustomize-Manifests als Referenz nutzen
- [ ] Ingress und Service-Discovery konfigurieren

### Data Lineage in OpenMetadata

- [ ] SQLMesh-Lineage Export nach OpenMetadata evaluieren
- [ ] Automatisierten Export implementieren (z.B. als Post-Transform-Hook)
- [ ] Lineage-Visualisierung in OpenMetadata verifizieren

### Superset auf Keycloak OIDC

- [ ] Superset Auth von DB-Auth auf Keycloak OIDC umstellen
- [ ] OIDC-Client in Keycloak fuer Superset anlegen
- [ ] Row-Level Security mit Keycloak-Rollen verknuepfen
- [ ] Superset-Init-Script anpassen

### Soda Freshness & Referential Integrity

- [ ] Freshness Checks fuer alle Silver Tables ergaenzen
- [ ] Cross-Domain Referential Integrity Checks definieren (z.B. Policy -> Partner existiert)
- [ ] Alerting bei Freshness-Verletzung einrichten
- [ ] Claims Silver Checks ergaenzen (fehlen aktuell)

---

## Bekannte Schwaechen (Nicht-Priorisiert)

- [ ] `Partner` BC-Name vs. `Person` Code-Realitaet klaeren (Naming-Inkonsistenz)
- [ ] `PageRequest`/`PageResult` Duplikation beseitigen (Shared Kernel oder Library)
- [ ] Read Models (`PartnerView`, `ProductView`) einheitliche Abstraktion schaffen
- [ ] Billing, Claims, HR: fehlende Integration Tests schreiben
- [ ] E2E Test ueber komplette Event-Kette implementieren
- [ ] AKHQ JWT-Secret Default-Wert durch sicheren Wert ersetzen
- [ ] DB-Passwoerter von Env-Vars auf Vault umstellen
