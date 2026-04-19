# ADR-007: Event-Carried State Transfer (ECST) via compacted State Topics

**Status:** Accepted
**Bereich:** Data Mesh · Read-Model-Bootstrap

## Kontext

Consumer von Domain-Events müssen beim Cold Start ihren lokalen Read-Model aufbauen. Mit ausschliesslich Delta-Events (`person.v1.created`, `person.v1.updated`, `person.v1.deleted`) bedeutet das ein vollständiges Event-Replay seit dem frühesten Offset. Bei wachsendem Eventvolumen ist das:

* zeitintensiv (Minuten bis Stunden),
* fehleranfällig (Replay-Bugs),
* bei GDPR-Right-to-Erasure ineffektiv, da Delta-Events unbegrenzt retained werden.

## Entscheidung

Zusätzlich zu den Delta-Events publiziert jede Domäne mit zentralem Aggregat ein **compacted State Topic** nach dem Schema `{domain}.v{version}.state` (`cleanup.policy=compact`, geeignete Partition-Anzahl).

* **Key** = Aggregate-ID (z. B. `personId`, `policyId`).
* **Value** = vollständiger aktueller Zustand des Aggregats.
* **Tombstone** (`value=null`): Aggregate gelöscht – Compaction entfernt Eintrag.

Consumer bootstrappen ihr Read-Model **ausschliesslich** aus dem State-Topic und konsumieren parallel den Delta-Stream nur für Live-Updates und Audit-Zwecke. Delta-Events bleiben Source-of-Truth für den Audit-Trail (7 Jahre Retention).

**Implementierungsstand:**

* `person.v1.state` – produktiv (Partner-Service).
* `hr.v1.employee.state`, `hr.v1.org-unit.state` – produktiv (HR-Integration, Camel).
* Weitere Domänen folgen iterativ, wo Bootstrap-Latenz schmerzt.

## Konsequenzen

* Consumer-Cold-Start reduziert sich auf einmaliges Lesen des State-Topics (Sekunden statt Stunden).
* Zusätzlicher Outbox-Eintrag pro Mutation (1× Delta + 1× State).
* State-Topic ist als Iceberg-Sink abgelegt und dient als Bootstrap-Quelle für den Silver Layer (`raw.person_state` → `silver.partner.partner`).
* **GDPR-Einschränkung:** Tombstone im State-Topic löscht nur den State-Eintrag, **nicht** die PII in historischen Delta-Events → [ADR-009](adr-009-crypto-shredding-pii.md) (Crypto-Shredding).
