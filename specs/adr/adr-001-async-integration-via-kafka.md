# ADR-001: Asynchrone Integration via Apache Kafka

**Status:** Accepted
**Datum:** 2025-Q1 (initiale Festlegung)
**Bereich:** Integration · Domänen-Kommunikation

## Kontext

Die Plattform besteht aus autonomen Self-Contained Systems (SCS) pro Bounded Context (Partner, Product, Policy, Claims, Billing, HR). Domänen müssen Daten austauschen, ohne dass synchrone Laufzeit-Abhängigkeiten entstehen, die Autonomie und Ausfallsicherheit (QS-1, QS-2) untergraben. Direkte DB-Zugriffe zwischen Domänen würden implizite Kopplungen erzeugen und Data Mesh verletzen.

## Entscheidung

Apache Kafka ist der **einzige** Integrationskanal für Domänen-übergreifende Kommunikation. Direkte Cross-Domain-DB-Zugriffe sind verboten. Synchrone Protokolle sind nur in explizit ausgenommenen Fällen erlaubt (ADR-003 für IAM, ADR-010 für gRPC-Berechnungen).

Alle Business-Events werden via Kafka publiziert. Konsumenten bauen ihre lokalen Read-Models durch Event-Verarbeitung auf (ADR-007, ADR-008).

## Konsequenzen

* Eventual Consistency wird als explizites Architekturprinzip akzeptiert.
* Rollbacks werden über Kompensations-Events modelliert, nicht über verteilte Transaktionen.
* Kafka ist ein Single Point of Failure auf Plattformebene → Multi-AZ-Cluster mit Replikationsfaktor 3 (R-3).
* UI-Patterns müssen Async-Feedback unterstützen (optimistische Updates, Polling).
* Neue Consumer können jederzeit hinzugefügt werden, ohne Producer zu ändern.
