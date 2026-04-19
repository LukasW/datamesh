# ADR-003: REST ausschliesslich für IAM-Authentifizierung

**Status:** Updated (ersetzt ursprüngliche Fassung)
**Bereich:** Integration
**Superseded parts:** Die ursprüngliche Fassung erlaubte REST auch für die synchrone Deckungsprüfung Claims → Policy. Diese Ausnahme wurde durch [ADR-008](adr-008-coverage-check-local-snapshot.md) aufgehoben.

## Kontext

Synchrone HTTP-Aufrufe zwischen Domänen erzeugen Laufzeit-Kopplung: Ein Ausfall des aufgerufenen Service blockiert den aufrufenden Service. Die ursprüngliche Fassung dieses ADR erlaubte REST für Claims → Policy (Deckungsprüfung); die praktische Erfahrung zeigte, dass dies die SCS-Autonomie (Qualitätsziel 1) verletzt.

Für Authentifizierung und Autorisierung via Keycloak (OIDC) ist eine synchrone Interaktion jedoch unumgänglich: OAuth2-Token-Validierung erfolgt per Definition HTTP-basiert.

## Entscheidung

REST ist **ausschliesslich** erlaubt für:

1. **IAM-Interaktion** mit Keycloak (OIDC Token Introspection, JWKS, Userinfo).
2. **Interne, einzeln dokumentierte Ausnahmen** auf UI-/Backend-Ebene innerhalb desselben SCS (kein Cross-Domain-Call).

Alle Cross-Domain-Kommunikation läuft via Kafka ([ADR-001](adr-001-async-integration-via-kafka.md)). Für Request-Reply-Berechnungen gilt [ADR-010](adr-010-grpc-for-synchronous-domain-calls.md) (gRPC, nicht REST).

## Konsequenzen

* Claims-Service ist vollständig autonom – kein Ausfall durch Policy-Service-Unavailability ([ADR-008](adr-008-coverage-check-local-snapshot.md)).
* Jeder Quarkus-Service konfiguriert `quarkus-oidc` gegen die Keycloak-Realm-Instanz.
* Die frühere REST-Schnittstelle des Policy-Service für Claims-Queries wurde entfernt.
