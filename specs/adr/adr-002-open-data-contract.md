# ADR-002: Open Data Contract als verbindlicher Vertrag

**Status:** Accepted
**Bereich:** Data Mesh · Governance

## Kontext

Ohne formalen Kontrakt entstehen implizite Abhängigkeiten zwischen produzierenden und konsumierenden Teams. Schema-Änderungen auf Producer-Seite brechen unbemerkt Consumer. Dies widerspricht den Data-Mesh-Prinzipien (insbesondere «Data as a Product» und «Federated Governance»).

## Entscheidung

Jedes Kafka-Topic benötigt einen **Open Data Contract (ODC)** im YAML-Format, abgelegt unter `{domain}/src/main/resources/contracts/`. Der ODC definiert:

* **Schema** (Avro-basiert, via Schema Registry durchgesetzt),
* **SLOs** (Freshness, Verfügbarkeit, Qualitätsscore),
* **Data-Quality-Checks** (SodaCL, gegen Iceberg/Trino ausgeführt),
* **PII-Tags** und **Ownership** (Team, Domäne).

**Topic-Naming:** `{domain}.v{majorVersion}.{event-type}`. Breaking Changes erfordern eine neue Major-Version (parallel betrieben bis zum Consumer-Migrations-Deadline). Kompatible Änderungen folgen Avro-Schema-Evolution (Subject Compatibility: `BACKWARD`).

## Konsequenzen

* Initialer Mehraufwand beim Definieren neuer Topics, langfristig weniger Integrationsprobleme.
* ODC-Validierung wird Teil der CI/CD-Pipeline (Linting + Schema-Kompatibilitäts-Check).
* Consumer-Driven Contract Testing via Testcontainers-basierter Integrationstests.
* OpenMetadata liest ODCs als Custom Properties und macht sie durchsuchbar.
