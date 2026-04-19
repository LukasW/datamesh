# ADR-008: Deckungsprüfung via lokalem Policy-Snapshot

**Status:** Accepted
**Bereich:** Autonomy · Claims-Prozess
**Ersetzt Teil von:** [ADR-003](adr-003-rest-only-for-iam.md) (alte Fassung erlaubte REST Claims → Policy)

## Kontext

Die ursprüngliche Architektur sah vor, dass der Claims-Service bei einer Schadenmeldung (FNOL) den Policy-Service via REST abfragt, um Deckung, Policy-Status und Selbstbehalt zu prüfen. Das erzeugt eine harte Laufzeit-Kopplung:

* Ein Ausfall des Policy-Service blockiert die komplette Schadenmeldung (Kunden-Auswirkung!).
* Claims-Team kann nicht unabhängig deployen, wenn sich das REST-Interface ändert.
* Verletzt Qualitätsziel 1 (Autonomie) und 4 (Ausfallsicherheit).

## Entscheidung

Der Claims-Service konsumiert `policy.v1.issued`, `policy.v1.cancelled`, `policy.v1.changed` und pflegt eine **lokale Policy-Snapshot-Tabelle** (`policy_snapshot`) in seiner eigenen Claims-DB.

Die Deckungsprüfung bei FNOL läuft ausschliesslich gegen dieses lokale Read-Model:

```text
PolicyIssued (Kafka) → Claims Kafka Consumer → policy_snapshot (Claims DB)
                                                       ↓
FNOL → ClaimsApplicationService → PolicySnapshotRepository → CoverageChecker
```

Kein REST-Aufruf und kein gRPC-Aufruf zum Policy-Service während des Claims-Lifecycle.

## Konsequenzen

* Claims-Service ist vollständig autonom; unempfindlich gegen Policy-Service-Ausfall.
* **Eventual Consistency:** Snapshot kann kurzzeitig veraltet sein – Delay liegt typisch bei < SLO-Freshness von `policy.v1.issued` (5 Minuten laut ODC).
* Schema-Änderungen am `policy.v1.issued`-Event erfordern Migration der `policy_snapshot`-Tabelle – gehandhabt via ODC-Versionierung ([ADR-002](adr-002-open-data-contract.md)).
* Pattern ist Vorlage für weitere Cross-Domain-Reads (z. B. `partner_sicht` im Policy-Service).
