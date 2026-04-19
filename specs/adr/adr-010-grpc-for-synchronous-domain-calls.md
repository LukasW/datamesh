# ADR-010: gRPC für synchrone Domänen-Calls in Spezialfällen

**Status:** Accepted
**Bereich:** Integration
**Aktueller Anwendungsfall:** Prämienberechnung (Policy → Product)

## Kontext

Die Prämienberechnung im Policy-Issuance-Prozess erfordert eine synchrone Antwort **vor** dem Speichern einer Police: Der Underwriter bzw. die UI muss die berechnete Prämie sehen, bevor er/sie die Police bestätigt. Ein asynchrones Request-Reply-Pattern (Event → Antwort-Event mit Correlation-ID) ist für diesen UX-kritischen Punkt überengineered.

REST wäre möglich, aber für reine Berechnungs-Services bietet gRPC Vorteile:

* Binärprotokoll (Protobuf) – schneller und bandbreiteneffizienter,
* Schema-First-Design mit stark typisierten Stubs,
* Erstklassiger Quarkus-Support (Reactive + Virtual Threads),
* Streaming-Fähigkeit für Batch-Berechnungen.

## Entscheidung

gRPC ist als synchrones Kommunikationsprotokoll für Spezialfälle erlaubt, wenn **alle** folgenden Bedingungen erfüllt sind:

1. **Request-Reply-Semantik zwingend** – der Aufrufer benötigt die Antwort vor dem nächsten Verarbeitungsschritt.
2. **Circuit Breaker, Timeout, Retry mandatory** – SmallRye Fault Tolerance (`@CircuitBreaker`, `@Timeout`, `@Retry`) auf jedem gRPC-Client.
3. **Graceful Degradation** – bei Nichterreichbarkeit wird der Use Case mit benutzerfreundlicher Fehlermeldung abgebrochen (kein stille Fallback-Wert).
4. **Kein Write auf dem Server** – der gRPC-Call ist eine reine Query/Berechnung. Schreibende Operationen laufen weiterhin über Kafka ([ADR-001](adr-001-async-integration-via-kafka.md)).
5. **Proto-Dateien in beiden Services** – kein Shared-Module-Artefakt, um SCS-Autonomie zu wahren.

### Anwendungsfälle

| Call | Client → Server | Protokoll | Bedingungen erfüllt |
|------|----------------|-----------|---------------------|
| Prämienberechnung | Policy → Product | gRPC (Port 9181) | ✅ Query, CB+Timeout, Graceful Degradation |
| IAM-Authentifizierung | Alle → Keycloak | REST/OIDC | ✅ (siehe [ADR-003](adr-003-rest-only-for-iam.md)) |

## Konsequenzen

* Product-Service exponiert gRPC-Server auf Port 9181 zusätzlich zum REST-Endpoint 9081.
* Policy-Service hat eine Laufzeit-Abhängigkeit zum Product-Service für Prämienberechnung.
* Bei Product-Ausfall können keine neuen Policen erstellt werden – akzeptierter Tradeoff, benutzerfreundliche Meldung («Prämienberechnung derzeit nicht verfügbar, bitte später erneut versuchen»).
* Risiko R-9 (arc42.md §11) wird durch Circuit Breaker und Product-Service-HA (>99.9 %) abgefedert.
* Jede neue gRPC-Ausnahme erfordert eine eigene ADR-Ergänzung – kein Wildwuchs synchroner Calls.
