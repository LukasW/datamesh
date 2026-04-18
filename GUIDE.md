# GUIDE – Use Cases durchspielen & testen

Dieser Guide führt durch die wichtigsten Geschäftsfälle der Yuno Sachversicherungs-Plattform. Ziel: in 30 Minuten alle Domains (Partner → Product → Policy → Billing → Claims → HR → Analytics) einmal vollständig durchspielen und verifizieren.

> **Voraussetzung:** Stack läuft via `./build.sh -d` oder `podman compose up -d`. Prüfe mit `podman ps` dass alle Container `healthy` sind.

---

## 0. Login-Credentials (Keycloak)

Alle UIs sind per OIDC geschützt. Login-Dialog erscheint automatisch beim ersten Aufruf.

| User | Passwort | Rolle(n) |
|---|---|---|
| `admin` | `admin` | ADMIN, UNDERWRITER, CLAIMS_AGENT, BROKER |
| `underwriter` | `test` | UNDERWRITER |
| `claims-agent` | `test` | CLAIMS_AGENT |
| `broker` | `test` | BROKER |

> Empfehlung für den End-to-End-Durchlauf: `admin / admin` – hat Zugriff auf alle Services.

Keycloak-Admin-Console: http://localhost:8280 (User `admin`, Pw `admin`).

---

## 1. Happy Path: Neukunde → Produkt → Police → Rechnung

Dieses Szenario erzeugt einen vollständigen Datenfluss durch alle vier Kern-Domains.

### 1.1 Partner anlegen – Anna Müller
1. http://localhost:9080/persons öffnen, einloggen.
2. **«Neue Person»** → Formular ausfüllen:
   - Vorname: `Anna`, Nachname: `Müller`
   - Geburtsdatum: `1985-06-15`
   - AHV-Nummer: `756.1234.5678.90`
3. Speichern.
4. In der Liste die Person öffnen → **«Adresse hinzufügen»**:
   - Strasse: `Bahnhofstrasse 1`, PLZ: `8001`, Ort: `Zürich`
5. **Verifikation in AKHQ** (http://localhost:8085):
   - Topic `person.v1.created` → neuer Event mit `personId`.
   - Topic `person.v1.state` (compacted) → voller State von Anna, PII-Felder `encrypted: true` (ADR-009).

### 1.2 Produkt anlegen – Hausrat Basic
1. http://localhost:9081/products → **«Neues Produkt»**:
   - Code: `HAUSRAT_BASIC`
   - Name: `Hausrat Basic`
   - Kategorie: `HOUSEHOLD`
   - Basisprämie: `480.00`
2. Speichern.
3. **Verifikation:** Topic `product.v1.defined` + `product.v1.state` in AKHQ.

### 1.3 Police ausstellen
1. http://localhost:9082/policen → **«Neue Police»**.
2. Partner suchen: `Müller` → Anna Müller wählen (kommt aus dem lokalen Read-Model `partner_view`, das via `person.v1.state` gefüllt wurde).
3. Produkt: `HAUSRAT_BASIC`.
4. Start: heute, Prämie: `480.00`.
5. **«Speichern»** → Police ist im Status `DRAFT`.
6. **«Aktivieren»** → `ACTIVE`.
   - Im Hintergrund: gRPC-Call Policy → Product (`PremiumCalculation`, ADR-010) mit Circuit Breaker.
7. **Verifikation:** Topic `policy.v1.issued` in AKHQ enthält Policy-Nr., `partnerId`, `productCode`, `premium`.

### 1.4 Billing erzeugt automatisch die Rechnung
Kein Klick nötig – reiner Event-Flow:

1. `policy.v1.issued` wird von `billing-service` konsumiert.
2. http://localhost:9084/billing öffnen → neue Rechnung `BILL-2026-xxxxx`, Betrag `480.00`, Status `OPEN`.
3. **«Bezahlt markieren»** → Status wechselt zu `PAID`.
4. **Verifikation:** Topic `billing.v1.invoice-created` + `billing.v1.invoice-paid`.

### 1.5 Analytics verifizieren
1. **Trino** (http://localhost:8086 oder via CLI):
   ```sql
   SELECT * FROM iceberg.policy_silver.policy_current WHERE partner_id = '<annaId>';
   SELECT * FROM iceberg.billing_silver.invoice_current;
   ```
   → Die Police und die Rechnung müssen erscheinen (nach ~1–2 Min. SQLMesh-Run).
2. **Superset** (http://localhost:8088, SSO via Keycloak) → Dashboard **Policy Portfolio** zeigt Anna's Police im Gold-Layer.
3. **MinIO** (http://localhost:9001) → Bucket `lakehouse` → Parquet-Dateien unter `policy/policy_events/…`.

---

## 2. Partner-Mutation – Adresswechsel

Zeigt die Wiederverwendung des Read-Models über Kafka.

1. http://localhost:9080/persons → Anna Müller bearbeiten.
2. Zweite Adresse hinzufügen: `Limmatquai 5, 8001 Zürich`, gültig ab heute.
3. **Verifikation:**
   - Topic `person.v1.address-added` → neuer Event.
   - Topic `person.v1.state` → aktualisierter State (derselbe Key, neues Value – dank Compaction).
   - In Policy-UI: Partner-Detail zeigt neue Adresse (Read-Model `partner_view` in `policy_db` wurde via Consumer aktualisiert).

---

## 3. Schadenfall (FNOL) – Claims Lifecycle

1. http://localhost:9083/claims → **«Neuer Schaden»**.
2. Partner suchen `Müller` → Anna auswählen.
3. Police-Liste erscheint (lokales Read-Model aus `policy.v1.issued`) → Hausrat-Police wählen.
4. Schadendatum: heute, Schadensumme: `2500.00`, Beschreibung: `Wasserschaden Küche`.
5. Speichern → Status `OPEN`.
6. **«Prüfen starten»** → `IN_REVIEW`.
7. **«Auszahlen»** → `SETTLED`.
8. **Verifikation:**
   - `claims.v1.opened`, `claims.v1.settled` in AKHQ.
   - Die Deckungsprüfung lief rein lokal (ADR-008), d. h. ohne REST/gRPC zum Policy-Service.

### Alternative: Ablehnen
Statt «Auszahlen» → **«Ablehnen»**. Status `REJECTED`, Event `claims.v1.settled` mit `outcome=REJECTED`.

---

## 4. Mahnwesen (Dunning)

1. Lege wie in Abschnitt 1 eine weitere Police an → neue Rechnung erscheint im Billing.
2. http://localhost:9084/billing → Rechnung **nicht** bezahlen.
3. **«Mahnen»** klicken (oder warten bis Scheduler OVERDUE markiert).
4. Status: `OPEN` → `OVERDUE`.
5. **Verifikation:** Event `billing.v1.invoice-dunned` (Mahnstufe 1).

---

## 5. Produkt deprecieren

1. http://localhost:9081/products → `HAUSRAT_BASIC` öffnen → **«Deprecieren»**.
2. Status: `ACTIVE` → `DEPRECATED`.
3. **Verifikation:** Topic `product.v1.deprecated`.
4. Bestehende Policen bleiben gültig; neue Policen mit diesem Produkt werden abgelehnt (Policy prüft gegen lokalen `product_view`).

---

## 6. HR-System – Mitarbeiter-Sync (externer COTS-Stub)

Dieser Fall zeigt Integration über Camel + OData.

1. http://localhost:9085/mitarbeiter → **«Neuer Mitarbeiter»**:
   - Personalnummer: `E-1001`, Name: `Hans Meier`, Abteilung: `Underwriting`
2. Speichern (nur im HR-Stub-DB, kein Kafka).
3. **hr-integration** pollt den OData-Feed alle N Sekunden → http://localhost:9086/q/health prüft Status.
4. **Verifikation:** Topics `hr.v1.employee.changed` (Delta) + `hr.v1.employee.state` (ECST) in AKHQ.

---

## 7. Police kündigen

1. http://localhost:9082/policen → Police öffnen → **«Kündigen»**.
2. Status: `ACTIVE` → `CANCELLED`.
3. **Verifikation:**
   - `policy.v1.cancelled` Event.
   - Billing-Service stoppt weitere Rechnungen (Consumer reagiert auf `cancelled`).
   - Claims-Service ändert `policy_view.status` – neue Schäden für diese Police werden abgelehnt.

---

## 8. Datenfluss gezielt beobachten

| Was willst du sehen? | Wo? |
|---|---|
| Events live mitlesen | AKHQ http://localhost:8085 → Topic wählen → **Live tail** |
| Outbox-Zeilen in Postgres | `psql -h localhost -p 5432 -U partner_user partner_db -c 'SELECT * FROM outbox;'` |
| Debezium-Connector-Status | http://localhost:8083/connectors/partner-outbox-connector/status |
| Parquet-Files auf MinIO | http://localhost:9001 (Key `minio`, Secret `minio12345`) |
| Lakehouse-Query | Trino http://localhost:8086 oder `podman exec -it trino trino` |
| Gold-Modelle + Dashboards | Superset http://localhost:8088 |
| gRPC-Call Policy→Product | Jaeger http://localhost:16686 → Service `policy-service` → Span `PremiumCalculation` |
| PII-Verschlüsselung | Vault http://localhost:8200 (Token `root`) → Transit Engine, Key `person-pii` |

---

## 9. Automatisiert: Seed-Skript

Für schnelles Befüllen aller Domains mit realistischen Testdaten:

```bash
./scripts/seed-test-data.sh
```

Das Skript legt Partner, Produkte, Policen, Schäden und Rechnungen via REST an (OIDC-Token wird automatisch gezogen). Gut geeignet, um Dashboards mit Volumen zu befüllen.

---

## 10. Tests auf der Kommandozeile

```bash
# Alle Unit-Tests
mvn test

# Einzelne Domain
mvn test -pl partner

# Integration (Testcontainers)
mvn verify -Pintegration

# Datacontract-Tests (ODC-Compliance)
mvn test -Dtest=DataContractVerificationTest

# Playwright UI-Smoketests
mvn test -pl policy -Dgroups=playwright
```

---

## 11. Troubleshooting

| Symptom | Ursache / Fix |
|---|---|
| UI wirft 401 nach Login | Keycloak-Container noch nicht ready → `podman logs keycloak` abwarten, Cookie löschen, neu laden. |
| Event erscheint nicht in AKHQ | Debezium-Connector prüfen: `curl localhost:8083/connectors/<name>/status`. Bei `FAILED` neu starten. |
| Policy aktivieren schlägt fehl | gRPC Product unreachable → Circuit Breaker greift (ADR-010). `product-service` Logs prüfen. |
| Trino sieht keine Tabellen | Iceberg-Sink-Connector in Debezium down **oder** Nessie IN_MEMORY-Modus nach Restart → SQLMesh-Runner neu anstossen: `podman exec sqlmesh-runner sqlmesh run`. |
| Superset: Login-Loop | Keycloak-Client `superset` Secret mismatch → `infra/superset/superset_config.py` mit Realm vergleichen. |
| Consumer-Lag wächst | Nach Consumer-Fix Offsets zurücksetzen: Service stoppen, dann `kafka-consumer-groups --bootstrap-server localhost:29092 --group <group> --topic <topic> --reset-offsets --to-earliest --execute`. |

---

## 12. Reset für sauberen Neustart

```bash
# Alles runter inkl. Volumes (DB, Kafka, MinIO leer)
podman compose down -v

# Neu bauen + starten
./build.sh -d --delete-volumes
```

Danach mit **Abschnitt 1** wieder starten.
