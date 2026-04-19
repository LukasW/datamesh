# ADR-012: Domain-owned Data Products im Repository-Layout

**Status:** Accepted
**Bereich:** Data Mesh · Repository-Struktur

## Kontext

Analytische Modelle (SQLMesh, SodaCL, Debezium-Sink-Konfigurationen) lebten ursprünglich ausschliesslich zentral unter `infra/sqlmesh/models/`, `infra/soda/`, `infra/debezium/`. Das ist bequem für Platform-Teams, widerspricht aber den Data-Mesh-Prinzipien:

* **Domain Ownership:** Analytische Transformationen gehören der produzierenden Domäne, nicht einem zentralen Plattform-Team.
* **Data as a Product:** Das Data Product (Contract + Model + Quality-Checks + Sink-Config) sollte als Einheit versioniert und deployed werden.
* **Self-Serve Platform:** Domain-Teams müssen ihre eigenen Silver/Gold-Modelle ohne Koordination mit dem Platform-Team weiterentwickeln können.

Gleichzeitig gibt es legitime **Cross-Domain-Analytics-Modelle**, die keiner einzelnen Domäne gehören (z. B. `mart_portfolio_summary` über Policy + Partner + Product).

## Entscheidung

Jede Domäne besitzt ein **Data Product** unter `{domain}/data-product/` mit folgender Struktur:

```text
{domain}/data-product/
├── sqlmesh/
│   ├── silver/                    <-- Silver-Layer-Modelle der Domäne
│   ├── gold/                      <-- Gold-Layer der Domäne (domain-spezifische Marts)
│   ├── audits/                    <-- SQLMesh-Audit-Assertions
│   └── tests/                     <-- SQLMesh-Tests
├── soda/
│   └── checks.yml                 <-- SodaCL-Quality-Checks gegen Trino
└── debezium/
    └── iceberg-sinks/             <-- Eine Iceberg-Sink-Config pro Kafka-Topic (ADR-014)
```

**Cross-Domain-Gold-Modelle** (KPIs, Analytics) verbleiben zentral unter `infra/sqlmesh/models/gold/analytics/` – sie haben keine natürliche Heimat in einer einzelnen Domäne.

SQLMesh bindet alle Domänen-Modelle per Volume-Mount in seine Model-Hierarchie ein (`/sqlmesh/models/silver/{domain}`, `/sqlmesh/models/gold/{domain}`). Debezium registriert die Sink-Configs via `iceberg-init`.

## Konsequenzen

* Domain-Teams entwickeln Silver/Gold und Quality-Checks im eigenen Repo-Teilbaum – kein Platform-Team-Bottleneck.
* Contracts, Code und Data Product sind im selben Commit reviewbar.
* Data Product und Service werden gemeinsam deployed (eine Commit-Einheit).
* Platform-Team pflegt **nur noch** Cross-Domain-Analytics + Infrastruktur-Verdrahtung.
* ODCs ([ADR-002](adr-002-open-data-contract.md)) bleiben unter `{domain}/src/main/resources/contracts/` nah am Producer-Code – jetzt komplementär zu den Transformations-Artefakten.
* Repository-Layout spiegelt damit den Data-Mesh-Prinzipientreue-Satz 1:1 wider.
