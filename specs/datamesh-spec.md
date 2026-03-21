
# Strategisches Gesamtkonzept: Föderiertes High-Performance Data Mesh

## 1. Die Schichten-Architektur (The Paved Road)

Um die produktiven Systeme (insbesondere das Claims-System mit mehreren Gigabyte) zu schützen, implementieren wir eine **asynchrone Spiegelung** in einen zentralen, aber logisch dezentral verwalteten Object Store.

### A. Operational Layer (Source of Truth)

* **Technologie:** Quarkus, PostgreSQL.
* **Prinzip:** Jede Domäne (Claims, Policy, etc.) arbeitet autark.
* **Datenextraktion:** **Debezium CDC** liest das Write-Ahead-Log (WAL). Dies verursacht **0% zusätzliche Query-Last** auf den Tabellen.

### B. Transport & Streaming Layer (The Backbone)

* **Technologie:** Apache Kafka (Strimzi/KRaft).
* **Prinzip:** Event-Carried State Transfer (ECST). PII-Daten werden hier bereits gemäss **ADR-009 (Crypto-Shredding)** verschlüsselt publiziert.

### C. Analytical Storage Layer (The Lakehouse)

* **Technologie:** **Apache Iceberg** auf **MinIO** oder **S3**.
* **Katalog:** **Project Nessie** (ermöglicht Git-ähnliches Versionieren von Daten).
* **Speicherort:** Die Gigabytes an Claims-Historie liegen als komprimierte **Parquet-Dateien** im Object Store. Dies entlastet die teuren Datenbank-Disks und ermöglicht extrem schnelle Scans.

### D. Query & Transformation Layer (The Engine)

* **Technologie:** **Trino** (Abfrage-Engine), **SQLMesh** (Transformationen) & **Apache Superset** (Dashboards & Self-Service BI).
* **Prinzip:** SQLMesh führt inkrementelle Modell-Updates auf Iceberg durch. Trino dient als Schnittstelle für BI-Tools und Data Science. **Superset** greift ausschliesslich auf Trino zu — Analysten und Management erstellen Dashboards auf den SQLMesh-Marts, ohne die operativen Systeme zu belasten. Row-Level-Security und Keycloak-SSO stellen sicher, dass nur berechtigte Rollen PII-Daten sehen.

---

## 2. Governance-Framework & Compliance (nDSG / AI Act)

In einem dezentralen Mesh muss die Governance "computational" (automatisiert) sein.

| Anforderung | Umsetzung im Konzept | Tooling |
| :--- | :--- | :--- |
| **Datenschutz (nDSG)** | **Crypto-Shredding:** Schlüssel im KMS löschen macht Parquet-Files auf S3 unlesbar. | HashiCorp Vault / KMS |
| **Auskunftsrecht** | **Active Lineage:** Visualisierung des Datenflusses vom Claims-Event bis zum Report. | OpenLineage & Marquez |
| **Data Quality** | **Contract Testing:** Validierung der Schemas und Inhalte vor dem Ingest in Iceberg. | Soda Core / ODCS |
| **AI Act (Art. 10)** | **Data Docs:** Automatisierte Dokumentation der Trainingsdaten-Qualität für KI-Modelle. | Great Expectations |

---

## 3. Datenfluss-Szenario: Das Claims-System (Gigabyte-Scale)

1. **Ingest:** Debezium erfasst eine neue Schadensmeldung in PostgreSQL.
2. **Streaming:** Das Event landet im Kafka-Topic `claims.v1.events` (AES-256 verschlüsselt).
3. **Persistierung:** Der Iceberg-Connector schreibt das Event in den **MinIO/S3-Bucket**. Iceberg partitioniert die Daten automatisch (z.B. nach Jahr/Monat).
4. **Analyse:** Ein Analyst fragt via **Trino** die Schadenssummen der letzten 5 Jahre ab. Trino liest nur die relevanten Parquet-Dateien vom S3 – die operative Claims-DB bleibt völlig unberührt.
5. **Transformation:** **SQLMesh** berechnet nächtlich die neuen "Claims-Marts" für das Management, nutzt dabei aber nur die inkrementellen Deltas seit dem letzten Lauf.
6. **Visualisierung:** Ein Manager öffnet das **Superset**-Dashboard «Schadensübersicht». Superset sendet eine SQL-Query an Trino, das die Iceberg-Parquet-Files auf MinIO liest. Die operative Claims-DB wird **nicht** berührt.

---

## 4. Rollen & Verantwortlichkeiten (Team Topologies)

* **Domänen-Teams (z.B. Claims):** Verantwortlich für den **Open Data Contract (ODCS)** und die Semantik ihrer Iceberg-Tabellen. Sie sind die "Produzenten".
* **Platform-Team:** Stellt MinIO/S3, Kafka, Trino, Superset und OpenMetadata als **Self-Service** bereit. Sie optimieren die Speicher-Kosten (S3 Lifecycle-Policies).
* **Governance-Gilde:** Definiert globale Tags (PII, Retention) und überwacht die Einhaltung via **OpenMetadata**.

---

## 5. Zusammenfassung der Vorteile

* **Resilienz:** Ein Totalausfall der Analyse-Infrastruktur stoppt niemals den Versicherungsbetrieb (SCS-Prinzip).
* **Performance:** Durch Apache Iceberg skalieren Sie problemlos auf Terabytes, ohne dass die Antwortzeiten von Trino explodieren.
* **Compliance:** Datenschutz ist kein manueller Prozess, sondern durch Verschlüsselung und Lineage technisch garantiert (**Privacy-by-Design**).
