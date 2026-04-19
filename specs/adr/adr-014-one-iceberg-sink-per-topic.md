# ADR-014: One Iceberg Sink Connector pro Topic

**Status:** Accepted
**Bereich:** Lakehouse · Debezium-Betrieb
**Eingeführt in:** PR #14 / #20 (`fix(infra): split iceberg sinks one-per-topic`), bestätigt in PR #27 (`consolidate billing iceberg sink`)

## Kontext

Ursprünglich war ein einzelner Debezium-Iceberg-Sink-Connector pro Domäne (bzw. für mehrere Domänen gebündelt) konfiguriert. Ein solcher Multi-Topic-Connector hatte operative Nachteile:

* **Fehlerkopplung:** Ein fehlerhaftes Schema in einem Topic blockiert den gesamten Connector → Alle Topics dieser Domäne sind blockiert.
* **Restart-Scope:** Neuladen/Debugging eines Connectors betrifft mehrere unabhängige Datenflüsse.
* **Offset-Reset:** Ein Reset der Consumer-Group wirkt sich auf alle Topics aus, selbst wenn nur ein Topic betroffen ist.
* **Metrik-Granularität:** Durchsatz, Lag und Fehler pro Topic nicht sauber beobachtbar.
* **Schema-Evolution:** Inkompatible Änderungen in einem Topic erzwingen Koordination mit allen anderen im selben Sink.

## Entscheidung

Pro Kafka-Topic wird **ein eigener Iceberg-Sink-Connector** konfiguriert und via `iceberg-init` in Debezium-Connect registriert. Die Connector-Configs liegen als einzelne JSON-Dateien unter `{domain}/data-product/debezium/iceberg-sinks/` ([ADR-012](adr-012-domain-owned-data-products.md)).

Jeder Sink nutzt:

* **Dedizierter `name`**, `topics` mit nur einem Topic,
* **Dediziertes `offset.flush.interval.ms`** und `tasks.max`,
* **Dedicated control topic** (`<connector>-control`) – eingeführt in PR #8 – damit Connector-interne Koordination keine Kollision erzeugt.
* Eindeutige Iceberg-Target-Tabelle im `raw`-Namespace.

Ausnahmen (z. B. Billing) wurden bewusst nachträglich konsolidiert, wenn fachlich mehrere kleine Topics denselben Output-Table bilden sollen – solche Ausnahmen erfordern eigene Begründung im Sink-Config-Kommentar.

## Konsequenzen

* Fehler in einem Topic betreffen nur dessen eigenen Connector.
* Debug, Restart, Offset-Reset sind granular pro Topic möglich.
* Monitoring-Dashboards in Grafana erhalten sauber separierte Metriken.
* Anzahl Connectoren steigt (N Topics statt M Domänen) – Debezium-Connect skaliert dafür gut (Worker-Pool).
* `transform-init` und SodaCL-Checks referenzieren klar benannte `raw`-Tabellen pro Topic ([ADR-011](adr-011-medallion-silver-gold.md)).
* Beim Hinzufügen eines neuen Topics wird zwingend eine neue Sink-Config angelegt – Teil des Data-Product-Checklisten.
