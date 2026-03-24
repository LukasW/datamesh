# Fix-Plan: Datamesh K8s – Analytics, Governance & Lineage

> **Datum:** 2026-03-24
> **Basiert auf:** `analyse_data.md`
> **Ziel:** Alle drei Hauptziele erfüllen (Superset-Queries, OpenMetadata-Governance, Lineage-Sichtbarkeit)

---

## Übersicht: Reihenfolge und Abhängigkeiten

```
Phase 1: Lakehouse-Stabilität (Voraussetzung für alles)
  ├── Fix 1: Nessie persistenter Storage
  ├── Fix 2: Trino Vault UDF Plugin
  └── Fix 3: Trino X-Forwarded-For (BEREITS ERLEDIGT)

Phase 2: Daten-Pipeline (Superset-Ziel)
  ├── Fix 4: SQLMesh K8s Job + CronJob
  └── Fix 5: SQLMesh Image Build + Load

Phase 3: Governance (OpenMetadata-Ziel)
  ├── Fix 6: OpenMetadata Init-Job
  ├── Fix 7: Schema Registry Init-Job
  └── Fix 8: HR Topics in Ingestion-Filter

Phase 4: Lineage (Lineage-Ziel)
  └── Fix 9: OpenLineage in Kafka Connect aktivieren
```

---

## Phase 1: Lakehouse-Stabilität

### Fix 1: Nessie auf persistenten Storage umstellen

**Priorität:** KRITISCH
**Dateien:** `infra/k8s/lakehouse.yaml`
**Aufwand:** Klein

**Problem:** `NESSIE_VERSION_STORE_TYPE: IN_MEMORY` → Alle Iceberg-Metadaten gehen bei Pod-Restart verloren.

**Lösung:** Nessie auf JDBC-Backend umstellen mit eigener PostgreSQL-Datenbank.

**Schritte:**

1. **Nessie-DB zu `databases.yaml` hinzufügen:**
   ```yaml
   # nessie-db
   apiVersion: v1
   kind: Service
   metadata:
     name: nessie-db
     namespace: datamesh
   spec:
     clusterIP: None
     selector:
       app: nessie-db
     ports:
       - port: 5432
   ---
   apiVersion: apps/v1
   kind: StatefulSet
   metadata:
     name: nessie-db
     namespace: datamesh
   spec:
     serviceName: nessie-db
     replicas: 1
     selector:
       matchLabels:
         app: nessie-db
     template:
       metadata:
         labels:
           app: nessie-db
       spec:
         containers:
           - name: postgres
             image: postgres:16
             imagePullPolicy: IfNotPresent
             env:
               - name: POSTGRES_DB
                 value: nessie
               - name: POSTGRES_USER
                 value: nessie
               - name: POSTGRES_PASSWORD
                 value: nessie
             ports:
               - containerPort: 5432
             readinessProbe:
               exec:
                 command: ["pg_isready", "-U", "nessie", "-d", "nessie"]
               initialDelaySeconds: 5
               periodSeconds: 5
             resources:
               limits:
                 memory: 128Mi
             volumeMounts:
               - name: data
                 mountPath: /var/lib/postgresql/data
                 subPath: pgdata
     volumeClaimTemplates:
       - metadata:
           name: data
         spec:
           accessModes: ["ReadWriteOnce"]
           resources:
             requests:
               storage: 1Gi
   ```

2. **Nessie-Deployment in `lakehouse.yaml` anpassen:**
   ```yaml
   env:
     - name: NESSIE_VERSION_STORE_TYPE
       value: JDBC
     - name: QUARKUS_DATASOURCE_JDBC_URL
       value: jdbc:postgresql://nessie-db:5432/nessie
     - name: QUARKUS_DATASOURCE_USERNAME
       value: nessie
     - name: QUARKUS_DATASOURCE_PASSWORD
       value: nessie
   ```

3. **`deploy.sh` erweitern:** Nessie-DB in die Rollout-Wait-Liste aufnehmen.

---

### Fix 2: Trino Vault UDF Plugin mounten

**Priorität:** KRITISCH
**Dateien:** `infra/k8s/lakehouse.yaml`, `build.sh`, `infra/k8s/deploy.sh`
**Aufwand:** Mittel

**Problem:** Vault UDF Plugin-JAR nicht im Trino-Container → `vault_decrypt()` Funktion fehlt.

**Lösung:** Custom Trino-Image bauen mit Plugin-JAR.

**Schritte:**

1. **Trino Dockerfile erstellen (`infra/trino/Dockerfile`):**
   ```dockerfile
   FROM trinodb/trino

   # Vault Transit Decrypt UDF Plugin
   COPY vault-udf/target/trino-vault-decrypt-1.0.0-SNAPSHOT/ /usr/lib/trino/plugin/vault/
   ```

2. **`build.sh` erweitern:**
   ```bash
   # Trino mit Vault UDF
   echo "▶ Building Trino Vault UDF Plugin..."
   (cd infra/trino/vault-udf && mvn package -q -DskipTests)

   echo "▶ Building custom Trino image..."
   $CONTAINER_CMD build -t yuno/trino:local infra/trino/
   ```

3. **`lakehouse.yaml` anpassen:**
   ```yaml
   image: yuno/trino:local  # statt trinodb/trino
   ```

4. **`deploy.sh`:** `yuno/trino:local` zur Image-Load-Liste hinzufügen.

---

### Fix 3: Trino X-Forwarded-For (ERLEDIGT)

**Status:** Bereits gefixt in `infra/trino/etc/config.properties`:
```properties
http-server.process-forwarded=true
```

**Verbleibend:** ConfigMap im Cluster aktualisieren und Trino-Pod neustarten.

---

## Phase 2: Daten-Pipeline (SQLMesh)

### Fix 4: SQLMesh Image bauen und in K8s deployen

**Priorität:** KRITISCH
**Dateien:** `build.sh`, `infra/k8s/deploy.sh`, neues `infra/k8s/transformation.yaml`
**Aufwand:** Mittel

**Problem:** SQLMesh hat ein Dockerfile und 24 Modelle, aber kein K8s-Deployment.

**Schritte:**

1. **`build.sh` erweitern:**
   ```bash
   echo "▶ Building SQLMesh image..."
   $CONTAINER_CMD build -t yuno/sqlmesh:local infra/sqlmesh/
   ```

2. **`deploy.sh` erweitern:**
   - `yuno/sqlmesh:local` zur Image-Load-Liste hinzufügen
   - ConfigMap für SQLMesh erstellen:
     ```bash
     create_cm sqlmesh-config \
       --from-file=config.yaml="$PROJECT_ROOT/infra/sqlmesh/config.yaml"
     create_cm sqlmesh-models \
       --from-file=... # alle Modelle
     ```

3. **`infra/k8s/transformation.yaml` erstellen:**
   ```yaml
   # ── SQLMesh Initial Plan + Apply ──────────────────────────
   apiVersion: batch/v1
   kind: Job
   metadata:
     name: sqlmesh-init
     namespace: datamesh
   spec:
     ttlSecondsAfterFinished: 600
     backoffLimit: 3
     template:
       spec:
         restartPolicy: OnFailure
         containers:
           - name: sqlmesh
             image: yuno/sqlmesh:local
             imagePullPolicy: IfNotPresent
             command: ["bash", "-c"]
             args:
               - |
                 # Wait for Trino
                 until curl -sf http://trino:8086/v1/info; do
                   echo 'Waiting for Trino...'; sleep 5
                 done
                 # Wait for at least one Iceberg table
                 until trino --server trino:8086 --catalog iceberg --execute "SHOW SCHEMAS" 2>/dev/null | grep -q raw; do
                   echo 'Waiting for Iceberg raw schemas...'; sleep 10
                 done
                 sqlmesh plan --auto-apply --no-prompts
             resources:
               limits:
                 memory: 512Mi
             volumeMounts:
               - name: config
                 mountPath: /sqlmesh/config.yaml
                 subPath: config.yaml
               - name: models
                 mountPath: /sqlmesh/models
         volumes:
           - name: config
             configMap:
               name: sqlmesh-config
           - name: models
             configMap:
               name: sqlmesh-models
   ---
   # ── SQLMesh Hourly CronJob ──────────────────────────────
   apiVersion: batch/v1
   kind: CronJob
   metadata:
     name: sqlmesh-run
     namespace: datamesh
   spec:
     schedule: "0 * * * *"  # @hourly
     concurrencyPolicy: Forbid
     jobTemplate:
       spec:
         template:
           spec:
             restartPolicy: OnFailure
             containers:
               - name: sqlmesh
                 image: yuno/sqlmesh:local
                 imagePullPolicy: IfNotPresent
                 command: ["sqlmesh", "run"]
                 resources:
                   limits:
                     memory: 512Mi
                 volumeMounts:
                   - name: config
                     mountPath: /sqlmesh/config.yaml
                     subPath: config.yaml
                   - name: models
                     mountPath: /sqlmesh/models
             volumes:
               - name: config
                 configMap:
                   name: sqlmesh-config
               - name: models
                 configMap:
                   name: sqlmesh-models
   ```

4. **`kustomization.yaml` erweitern:**
   ```yaml
   resources:
     - transformation.yaml
   ```

**Hinweis:** Die SQLMesh-Modelle als ConfigMap zu mounten ist für dev OK, aber bei >24 Dateien wird die ConfigMap gross. Alternative: Modelle direkt ins Docker-Image einbacken.

---

## Phase 3: Governance (OpenMetadata)

### Fix 6: OpenMetadata Init-Job erstellen

**Priorität:** KRITISCH
**Dateien:** Neues `infra/k8s/governance-init-job.yaml`, `infra/k8s/kustomization.yaml`
**Aufwand:** Gross

**Problem:** `scripts/init-openmetadata.sh` wird im K8s-Deployment nie ausgeführt.

**Lösung:** Einen K8s Job erstellen, der die gleiche Logik wie `init-openmetadata.sh` ausführt.

**Schritte:**

1. **`infra/k8s/governance-init-job.yaml` erstellen:**
   ```yaml
   apiVersion: batch/v1
   kind: Job
   metadata:
     name: openmetadata-init
     namespace: datamesh
   spec:
     ttlSecondsAfterFinished: 600
     backoffLimit: 5
     template:
       spec:
         restartPolicy: OnFailure
         containers:
           - name: openmetadata-init
             image: curlimages/curl:latest
             imagePullPolicy: IfNotPresent
             command: ["/bin/sh", "-c"]
             args:
               - |
                 OM_URL="http://openmetadata-server:8585"

                 # Wait for OpenMetadata
                 until curl -sf "$OM_URL/api/v1/system/version" > /dev/null; do
                   echo 'Waiting for OpenMetadata...'; sleep 5
                 done

                 # Authenticate
                 PASS=$(printf 'admin' | base64)
                 TOKEN=$(curl -sf -X POST "$OM_URL/api/v1/users/login" \
                   -H "Content-Type: application/json" \
                   -d "{\"email\":\"admin@open-metadata.org\",\"password\":\"$PASS\"}" \
                   | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

                 if [ -z "$TOKEN" ]; then
                   echo "ERROR: Could not get token"; exit 1
                 fi
                 AUTH="Authorization: Bearer $TOKEN"

                 # 1. Register Kafka service
                 curl -sf -X PUT "$OM_URL/api/v1/services/messagingServices" \
                   -H "Content-Type: application/json" -H "$AUTH" \
                   -d '{
                     "name": "kafka",
                     "serviceType": "Kafka",
                     "connection": {
                       "config": {
                         "type": "Kafka",
                         "bootstrapServers": "kafka:29092",
                         "schemaRegistryURL": "http://schema-registry:8081"
                       }
                     }
                   }' && echo "Kafka service registered"

                 # 2. Register Trino service
                 curl -sf -X PUT "$OM_URL/api/v1/services/databaseServices" \
                   -H "Content-Type: application/json" -H "$AUTH" \
                   -d '{
                     "name": "trino-iceberg",
                     "serviceType": "Trino",
                     "connection": {
                       "config": {
                         "type": "Trino",
                         "hostPort": "trino:8086",
                         "username": "openmetadata",
                         "catalog": "iceberg"
                       }
                     }
                   }' && echo "Trino service registered"

                 # 3. Create PII classification + tags
                 curl -sf -X PUT "$OM_URL/api/v1/classifications" \
                   -H "Content-Type: application/json" -H "$AUTH" \
                   -d '{"name":"PII","description":"PII subject to nDSG/GDPR"}'

                 for tag in Sensitive PersonIdentifier Address; do
                   curl -sf -X PUT "$OM_URL/api/v1/tags" \
                     -H "Content-Type: application/json" -H "$AUTH" \
                     -d "{\"name\":\"$tag\",\"classification\":\"PII\"}"
                 done
                 echo "PII tags created"

                 # 4. Create + deploy ingestion pipelines
                 KAFKA_ID=$(curl -sf "$OM_URL/api/v1/services/messagingServices/name/kafka" \
                   -H "$AUTH" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
                 TRINO_ID=$(curl -sf "$OM_URL/api/v1/services/databaseServices/name/trino-iceberg" \
                   -H "$AUTH" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

                 if [ -n "$KAFKA_ID" ]; then
                   curl -sf -X POST "$OM_URL/api/v1/services/ingestionPipelines" \
                     -H "Content-Type: application/json" -H "$AUTH" \
                     -d "{
                       \"name\": \"kafka-metadata-ingestion\",
                       \"pipelineType\": \"metadata\",
                       \"service\": {\"id\": \"$KAFKA_ID\", \"type\": \"messagingService\"},
                       \"sourceConfig\": {\"config\":{\"type\":\"MessagingMetadata\",\"topicFilterPattern\":{\"includes\":[\"person\\\\.v1\\\\..*\",\"product\\\\.v1\\\\..*\",\"policy\\\\.v1\\\\..*\",\"claims\\\\.v1\\\\..*\",\"billing\\\\.v1\\\\..*\",\"hr\\\\.v1\\\\..*\"]},\"generateSampleData\":true}},
                       \"airflowConfig\": {\"scheduleInterval\": \"0 */6 * * *\"}
                     }" && echo "Kafka pipeline created"

                   # Deploy pipeline
                   PID=$(curl -sf "$OM_URL/api/v1/services/ingestionPipelines/name/kafka.kafka-metadata-ingestion" \
                     -H "$AUTH" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
                   [ -n "$PID" ] && curl -sf -X POST "$OM_URL/api/v1/services/ingestionPipelines/deploy/$PID" \
                     -H "Content-Type: application/json" -H "$AUTH" -d '{}' && echo "Kafka pipeline deployed"
                 fi

                 if [ -n "$TRINO_ID" ]; then
                   curl -sf -X POST "$OM_URL/api/v1/services/ingestionPipelines" \
                     -H "Content-Type: application/json" -H "$AUTH" \
                     -d "{
                       \"name\": \"trino-metadata-ingestion\",
                       \"pipelineType\": \"metadata\",
                       \"service\": {\"id\": \"$TRINO_ID\", \"type\": \"databaseService\"},
                       \"sourceConfig\": {\"config\":{\"type\":\"DatabaseMetadata\",\"schemaFilterPattern\":{\"includes\":[\"analytics\",\"partner_raw\",\"product_raw\",\"policy_raw\",\"billing_raw\",\"claims_raw\",\"hr_raw\"]},\"includeViews\":true,\"markDeletedTables\":true}},
                       \"airflowConfig\": {\"scheduleInterval\": \"0 */6 * * *\"}
                     }" && echo "Trino pipeline created"

                   PID=$(curl -sf "$OM_URL/api/v1/services/ingestionPipelines/name/trino-iceberg.trino-metadata-ingestion" \
                     -H "$AUTH" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
                   [ -n "$PID" ] && curl -sf -X POST "$OM_URL/api/v1/services/ingestionPipelines/deploy/$PID" \
                     -H "Content-Type: application/json" -H "$AUTH" -d '{}' && echo "Trino pipeline deployed"
                 fi

                 echo "OpenMetadata initialization complete."
             resources:
               limits:
                 memory: 64Mi
   ```

2. **`kustomization.yaml` erweitern:** `governance-init-job.yaml` hinzufügen.

3. **`deploy.sh`:** Job in die Delete-Liste für idempotente Re-Deploys aufnehmen:
   ```bash
   for job in vault-init minio-init kafka-init debezium-init iceberg-init seed-data openmetadata-init schema-registry-init sqlmesh-init; do
   ```

---

### Fix 7: Schema Registry Init-Job

**Priorität:** MITTEL
**Dateien:** Neues `infra/k8s/schema-registry-init-job.yaml`
**Aufwand:** Klein

**Problem:** JSON-Schemas nicht in Schema Registry registriert.

**Lösung:** K8s Job erstellen, der `scripts/register-schemas.sh`-Logik nachbildet.

**Schritte:**

1. **Schema-Dateien als ConfigMap mounten:**
   ```bash
   # In deploy.sh
   create_cm schema-files \
     --from-file=... # alle *.schema.json Dateien
   ```

2. **Job erstellen, der für jede Schema-Datei die Schema Registry API aufruft.**

**Alternative (einfacher):** Schema-Registration in den `kafka-init` Job integrieren, da Kafka und Schema Registry zur gleichen Zeit bereit sind.

---

### Fix 8: HR Topics in Ingestion-Filter

**Priorität:** NIEDRIG
**Dateien:** `infra/openmetadata/kafka-ingestion.json`, `scripts/init-openmetadata.sh`
**Aufwand:** Minimal

**Änderung in `kafka-ingestion.json`:**
```json
"includes": [
  "person\\.v1\\..*",
  "product\\.v1\\..*",
  "policy\\.v1\\..*",
  "claims\\.v1\\..*",
  "billing\\.v1\\..*",
  "hr\\.v1\\..*"
]
```

Gleiches Pattern im OpenMetadata Init-Job (Fix 6) und in `scripts/init-openmetadata.sh`.

---

## Phase 4: Lineage

### Fix 9: OpenLineage in Kafka Connect aktivieren

**Priorität:** MITTEL
**Dateien:** `infra/k8s/messaging.yaml` (Debezium Connect env), `infra/debezium/Dockerfile`
**Aufwand:** Mittel

**Problem:** OpenLineage JARs sind im Image, aber der Kafka Connect Worker nutzt sie nicht.

**Schritte:**

1. **Debezium Connect env erweitern in `messaging.yaml`:**
   ```yaml
   # OpenLineage Transport Configuration
   - name: OPENLINEAGE_TRANSPORT_TYPE
     value: http
   - name: OPENLINEAGE_TRANSPORT_URL
     value: http://marquez:5050
   - name: OPENLINEAGE_NAMESPACE
     value: kafka-connect
   # Kafka Connect Listener für OpenLineage
   - name: CONNECT_CONNECTOR_CLIENT_CONFIG_OVERRIDE_POLICY
     value: All
   ```

2. **Falls nötig: OpenLineage Kafka Connect Listener Plugin im Dockerfile installieren:**
   ```dockerfile
   # In infra/debezium/Dockerfile
   RUN curl -sL -o /usr/share/java/openlineage/openlineage-kafka-connect-0.30.0.jar \
     https://repo1.maven.org/maven2/io/openlineage/openlineage-kafka-connect/0.30.0/openlineage-kafka-connect-0.30.0.jar
   ```

3. **Prüfen ob die SQLMesh OpenLineage-Integration automatisch Lineage an Marquez sendet** (sollte via `config.yaml` bereits konfiguriert sein).

---

## Implementierungs-Reihenfolge

| Schritt | Fix | Abhängig von | Geschätzter Aufwand |
|---|---|---|---|
| 1 | Fix 1: Nessie JDBC Backend | - | 30 min |
| 2 | Fix 2: Trino Vault UDF Image | - | 30 min |
| 3 | Fix 4+5: SQLMesh Job/CronJob | Fix 1, Fix 2 | 1h |
| 4 | Fix 6: OpenMetadata Init-Job | Fix 4 (Trino muss Tabellen haben) | 1h |
| 5 | Fix 7: Schema Registry Init | - | 30 min |
| 6 | Fix 8: HR Filter | Fix 6 | 5 min |
| 7 | Fix 9: OpenLineage Connect | - | 1h |

**Gesamtaufwand:** ~5h

---

## Validierung nach Fix

### Checkliste

- [ ] `trino.localhost` erreichbar (kein HTTP 406)
- [ ] `SHOW SCHEMAS FROM iceberg` in Trino zeigt: `partner_raw`, `product_raw`, `policy_raw`, `claims_raw`, `billing_raw`, `hr_raw`, `analytics`
- [ ] `SELECT * FROM iceberg.partner_raw.person_events LIMIT 5` liefert Daten
- [ ] `SELECT * FROM iceberg.analytics.dim_partner LIMIT 5` liefert entschlüsselte Partner-Daten
- [ ] Superset SQL Lab: Query auf `analytics.mart_portfolio_summary` funktioniert
- [ ] OpenMetadata: Kafka-Topics sichtbar unter "Messaging Services"
- [ ] OpenMetadata: Iceberg-Tabellen sichtbar unter "Database Services"
- [ ] OpenMetadata: PII-Tags auf `person_events` Spalten vorhanden
- [ ] Marquez Web UI: Lineage-Graph zeigt Kafka → Iceberg → SQLMesh Transformationen
- [ ] Nessie-Pod neustarten → Tabellen sind noch vorhanden (JDBC-Persistenz)

### Smoke-Test-Befehle

```bash
# Trino erreichbar?
curl -sf http://trino.localhost/v1/info

# Iceberg Schemas vorhanden?
kubectl -n datamesh exec deploy/trino -- trino --execute "SHOW SCHEMAS FROM iceberg"

# Raw-Daten vorhanden?
kubectl -n datamesh exec deploy/trino -- trino --execute "SELECT count(*) FROM iceberg.partner_raw.person_events"

# Analytics-Daten vorhanden?
kubectl -n datamesh exec deploy/trino -- trino --execute "SELECT * FROM iceberg.analytics.dim_partner LIMIT 3"

# OpenMetadata Topics?
curl -sf http://openmetadata.localhost/api/v1/topics?limit=5

# Marquez Lineage?
curl -sf http://marquez.localhost/api/v1/namespaces
```

---

## Dateien-Übersicht (zu erstellen/ändern)

| Aktion | Datei | Fix |
|---|---|---|
| **NEU** | `infra/trino/Dockerfile` | Fix 2 |
| **NEU** | `infra/k8s/transformation.yaml` | Fix 4 |
| **NEU** | `infra/k8s/governance-init-job.yaml` | Fix 6 |
| ÄNDERN | `infra/k8s/databases.yaml` | Fix 1 (nessie-db hinzufügen) |
| ÄNDERN | `infra/k8s/lakehouse.yaml` | Fix 1 (Nessie JDBC), Fix 2 (Trino Image) |
| ÄNDERN | `infra/k8s/messaging.yaml` | Fix 9 (OpenLineage env) |
| ÄNDERN | `infra/k8s/kustomization.yaml` | Fix 4, Fix 6 (neue Resourcen) |
| ÄNDERN | `infra/k8s/deploy.sh` | Fix 2, 4, 5, 6, 7 (Image-Load, ConfigMaps, Jobs) |
| ÄNDERN | `build.sh` | Fix 2, 5 (Trino + SQLMesh Image Build) |
| ÄNDERN | `infra/openmetadata/kafka-ingestion.json` | Fix 8 (HR Filter) |
| ÄNDERN | `infra/debezium/Dockerfile` | Fix 9 (OpenLineage Listener) |
| BEREITS GEÄNDERT | `infra/trino/etc/config.properties` | Fix 3 (process-forwarded) |
