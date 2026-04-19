# ADR-009: Crypto-Shredding für PII-Felder in Kafka-Events

**Status:** Proposed
**Bereich:** Privacy · DSGVO-Compliance
**Relatierte Risiken:** R-8 (arc42.md §11)

## Kontext

Partner-Events (`person.v1.created`, `person.v1.updated`) enthalten personenbezogene Daten:

* `name`, `firstName`
* `dateOfBirth`
* `socialSecurityNumber` (AHV-Nummer)
* Adressfelder

Die Delta-Events haben eine regulatorisch begründete Retention von **7 Jahren**. Ein Tombstone im compacted State-Topic ([ADR-007](adr-007-ecst-person-state.md)) löscht **nicht** die historischen PII-Daten im Delta-Log. Das ist ein DSGVO-Verstoss (Art. 17 Right to Erasure): Auf Wunsch der betroffenen Person müssen alle ihre PII unbrauchbar gemacht werden.

Physisches Löschen aus Kafka-Partitionen ist technisch/operativ nicht sinnvoll (Segmentgranularität, Replay-Bedarf für Recovery).

## Entscheidung

**Crypto-Shredding** auf Feldebene für alle PII-Felder:

1. **Key-Management:** HashiCorp Vault (Transit Engine) verwaltet einen partner-individuellen Data Encryption Key (DEK) pro `partnerId`.
2. **Producer-Seite:** Vor dem Outbox-Write werden PII-Felder mit `vault transit encrypt/dek-{partnerId}` verschlüsselt (AES-256-GCM). Der Event-Payload enthält nur Chiffrat + Flag `"encrypted": true`.
3. **Consumer-Seite:** Jeder Consumer, der PII liest, ruft vor der Verarbeitung `vault transit decrypt/dek-{partnerId}` auf. Consumer ohne Decrypt-Berechtigung sehen nur Chiffrat (Zero-Trust-Lesepfad).
4. **Nicht-PII-Felder bleiben Klartext:** `partnerId`, `insuredNumber`, Timestamps, Event-Metadaten – kein Performance-Overhead und weiterhin als Join-Keys nutzbar.
5. **GDPR Erasure:** `vault transit keys/dek-{partnerId} delete` macht alle historischen Events für diese Person dauerhaft unlesbar. Der Delta-Log bleibt physisch bestehen, aber PII ist kryptografisch vernichtet.

```text
Producer: plaintext PII → AES-256-GCM(DEK[partnerId]) → encrypted payload → Kafka
Consumer: Kafka → if "encrypted": true → Vault Decrypt → domain processing
Erasure:  Vault.deleteKey(partnerId) → alle historischen Events unlesbar
```

## Konsequenzen

* Vault wird kritische Infrastrukturabhängigkeit → HA-Setup (Raft-Cluster, Auto-Unseal in Produktion).
* Performance-Overhead durch Vault-Round-Trip (< 1 ms pro Event bei Caching von DEKs im Consumer-Prozess).
* **Breaking Change** für alle bestehenden Consumer bei Einführung → koordiniertes Rollout mit zwei-stufigem Migrationsplan (parallel verschlüsselte + unverschlüsselte Events, dann Switchover).
* CLAUDE.md dokumentiert bereits die Pflicht, das `"encrypted": true`-Flag zu prüfen.
* Consumer dürfen **nie** decrypted PII in analytische Layer schreiben ohne erneute Access-Control-Prüfung.
* Operational Runbook für Vault-Key-Recovery und Audit-Logging der Decrypt-Calls erforderlich.
