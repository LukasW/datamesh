# Umsetzungsplan: Versichertennummer (`insuredNumber`) im Partner-Service

> **Version:** 1.0.0 · **Datum:** 2026-03-22  
> **Scope:** Partner-Service – neues Feld `insuredNumber`, getriggert durch `policy.v1.issued`  
> **Betroffene Services:** Partner (Hauptarbeit), Policy (kein Change), Claims/Billing (abwärtskompatibel)

---

## Architekturüberblick

```
Policy Service                          Partner Service
─────────────────                       ──────────────────────────────────────────
Police aktiviert                        
  → Outbox → Debezium                  
    → policy.v1.issued ──(Kafka)──────► PolicyIssuedConsumer (NEU)
                                          │
                                          ▼
                                        PersonCommandService.assignInsuredNumber(partnerId)
                                          │
                                          ├─ Partner hat KEINE Nummer → generieren (DB-Sequence)
                                          │   → person.save()
                                          │   → Outbox: person.v1.updated + person.v1.state
                                          │
                                          └─ Partner HAT bereits Nummer → skip (idempotent)
```

**Wichtig:** Der Partner-Service war bisher ein reiner **Producer** (keine Kafka-Consumer). Mit diesem Feature wird er erstmals zu einem **Consumer** des `policy.v1.issued`-Topics. Das erfordert eine neue SmallRye-Kafka-Dependency und Channel-Konfiguration.

---

## Phasenübersicht

| Phase | Beschreibung | Aufwand | Abhängigkeiten |
|---|---|---|---|
| **1** | Domain Model: `InsuredNumber` Value Object + `Person`-Erweiterung | ~1.5h | – |
| **2** | Persistenz: Flyway-Migration + JPA Entity + Adapter | ~1h | Phase 1 |
| **3** | Kafka Consumer: `policy.v1.issued` → Nummernzuweisung | ~2h | Phase 1, 2 |
| **4** | Event-Publikation: Outbox-Payloads erweitern | ~1.5h | Phase 1 |
| **5** | UI: Versichertennummer in Qute-Templates anzeigen | ~1h | Phase 1, 2 |
| **6** | ODC-Contracts & Schema-Evolution | ~1h | Phase 4 |
| **7** | Tests (Unit + Integration) | ~3h | Phase 1–4 |
| | **Subtotal Partner-Service** | **~11h** | |
| **8** | Claims: VN-Suche, Anzeige in Partnerliste & Schadenliste, ACL, Tests | ~4h | Phase 4, [plan-claims-partner-search](plan-claims-partner-search.md) |
| **9** | Policy: PartnerView erweitern, ACL, UI (Badge + VN-Suche), Tests | ~3h | Phase 4 |
| **10** | Billing: PolicyholderView erweitern, Consumer, UI (Badge + VN-Suche), Tests | ~3h | Phase 4 |
| **11** | Analytics: Iceberg Auto-Evolve, SQLMesh-Modelle, Trino View, Superset | ~2h | Phase 4 |
| | **Total (alle Services)** | **~23h** | |

---

## Phase 1: Domain Model

### 1.1 Value Object: `InsuredNumber`

**Datei:** `partner/src/main/java/ch/yuno/partner/domain/model/InsuredNumber.java`

```java
package ch.yuno.partner.domain.model;

/**
 * Value Object: Unique insured number assigned to a person when their first policy is activated.
 * Format: VN-XXXXXXXX (VN prefix + 8-digit zero-padded sequence number).
 * Immutable, validated at construction.
 */
public record InsuredNumber(String value) {

    public static final String PREFIX = "VN-";

    public InsuredNumber {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Insured number must not be blank");
        if (!value.matches("VN-\\d{8}"))
            throw new IllegalArgumentException("Insured number must match VN-XXXXXXXX: " + value);
    }

    /** Creates an InsuredNumber from a raw sequence number (e.g. 42 → VN-00000042). */
    public static InsuredNumber fromSequence(long sequenceValue) {
        return new InsuredNumber(PREFIX + String.format("%08d", sequenceValue));
    }

    /** Returns the formatted display value (e.g. "VN-00000042"). */
    public String formatted() { return value; }

    @Override public String toString() { return value; }
}
```

**Design-Entscheide:**

| Entscheid | Begründung |
|---|---|
| Record (nicht nur String) | Kapselt Validierung; typsicher; verhindert Verwechslung mit anderen String-IDs |
| Format `VN-XXXXXXXX` | Lesbares Präfix + 8-stellig → 99'999'999 mögliche Nummern (>10 Mio. Partner) |
| Pure Java, keine Frameworks | Hexagonale Architektur: `domain/model/` darf kein `@Entity`, kein `@Inject` |
| `fromSequence()` Factory | Erzeugt aus DB-Sequence-Wert; isoliert Format-Logik im Value Object |

---

### 1.2 `Person`-Aggregat erweitern

**Datei:** `partner/src/main/java/ch/yuno/partner/domain/model/Person.java`

Änderungen:

```java
public class Person {
    // ...existing fields...
    private InsuredNumber insuredNumber; // nullable – null bis erste Police aktiviert

    // Beide Konstruktoren erweitern:

    /** Constructor for creating a new Person. */
    public Person(String name, String firstName, Gender gender,
                  LocalDate dateOfBirth, SocialSecurityNumber socialSecurityNumber) {
        // ...existing code...
        this.insuredNumber = null; // Initial: kein Versicherter
    }

    /** Constructor for reconstructing from persistence. */
    public Person(String personId, String name, String firstName, Gender gender,
                  LocalDate dateOfBirth, SocialSecurityNumber socialSecurityNumber,
                  InsuredNumber insuredNumber) {
        // ...existing code...
        this.insuredNumber = insuredNumber;
    }

    // ── Neue Business Method ──────────────────────────────────────────────

    /**
     * Assigns an insured number to this person.
     * Idempotent: does nothing if already assigned.
     *
     * @return true if a new number was assigned, false if already had one
     */
    public boolean assignInsuredNumber(InsuredNumber number) {
        if (number == null) throw new IllegalArgumentException("Insured number must not be null");
        if (this.insuredNumber != null) return false; // already insured → idempotent
        this.insuredNumber = number;
        return true;
    }

    /** Returns true if this person has an active insurance relationship. */
    public boolean isInsured() {
        return insuredNumber != null;
    }

    // Getter
    public InsuredNumber getInsuredNumber() { return insuredNumber; }
}
```

**Kritischer Punkt – Konstruktor-Kompatibilität:**
Der bestehende Reconstruction-Konstruktor (6 Parameter) wird durch einen 7-Parameter-Konstruktor ersetzt. Der `PersonJpaAdapter.toDomain()` muss angepasst werden (Phase 2).

---

## Phase 2: Persistenz

### 2.1 Flyway-Migration

**Datei:** `partner/src/main/resources/db/migration/V9__Add_Insured_Number.sql`

```sql
-- Add insured number column to person table.
-- Nullable: a person only gets an insured number when their first policy is activated.
-- UNIQUE: no two persons can share the same insured number.
ALTER TABLE person ADD COLUMN insured_number VARCHAR(11) UNIQUE;

-- Sequence for generating insured numbers (VN-XXXXXXXX).
-- START WITH 1 ensures the first number is VN-00000001.
CREATE SEQUENCE insured_number_seq START WITH 1 INCREMENT BY 1 NO CYCLE;

-- Audit table mirror (Hibernate Envers).
ALTER TABLE person_aud ADD COLUMN insured_number VARCHAR(11);
```

**Warum VARCHAR(11)?** `VN-` (3) + `00000042` (8) = 11 Zeichen.

### 2.2 Outbound Port: `InsuredNumberGenerator`

**Datei:** `partner/src/main/java/ch/yuno/partner/domain/port/out/InsuredNumberGenerator.java`

```java
package ch.yuno.partner.domain.port.out;

import ch.yuno.partner.domain.model.InsuredNumber;

/**
 * Port for generating unique insured numbers.
 * Implementation backed by a PostgreSQL sequence.
 */
public interface InsuredNumberGenerator {
    InsuredNumber nextInsuredNumber();
}
```

### 2.3 Driven Adapter: `InsuredNumberSequenceAdapter`

**Datei:** `partner/src/main/java/ch/yuno/partner/infrastructure/persistence/InsuredNumberSequenceAdapter.java`

```java
@ApplicationScoped
public class InsuredNumberSequenceAdapter implements InsuredNumberGenerator {
    @Inject EntityManager em;

    @Override
    public InsuredNumber nextInsuredNumber() {
        Long seq = (Long) em.createNativeQuery("SELECT nextval('insured_number_seq')")
                            .getSingleResult();
        return InsuredNumber.fromSequence(seq);
    }
}
```

### 2.4 JPA Entity erweitern

**Datei:** `partner/src/main/java/ch/yuno/partner/infrastructure/persistence/PersonEntity.java`

```java
// Neues Feld:
@Column(name = "insured_number", unique = true, length = 11)
private String insuredNumber;

// Getter + Setter:
public String getInsuredNumber() { return insuredNumber; }
public void setInsuredNumber(String insuredNumber) { this.insuredNumber = insuredNumber; }
```

### 2.5 JPA Adapter anpassen

**Datei:** `partner/src/main/java/ch/yuno/partner/infrastructure/persistence/PersonJpaAdapter.java`

| Methode | Änderung |
|---|---|
| `toEntity()` | `e.setInsuredNumber(person.getInsuredNumber() != null ? person.getInsuredNumber().value() : null);` |
| `updateEntity()` | Gleich wie `toEntity` für `insuredNumber` |
| `toDomain()` | Neuer Konstruktor mit 7. Parameter: `e.getInsuredNumber() != null ? new InsuredNumber(e.getInsuredNumber()) : null` |

---

## Phase 3: Kafka Consumer (`policy.v1.issued`)

### 3.1 Architektonischer Wendepunkt

Der Partner-Service war bisher ein **reiner Producer**. Das `application.yml` enthält **keinen** SmallRye-Kafka-Incoming-Channel. Die pom.xml braucht ggf. `quarkus-messaging-kafka` als Dependency (prüfen, ob bereits durch Debezium-Outbox vorhanden).

### 3.2 Kafka-Channel Konfiguration

**Datei:** `partner/src/main/resources/application.yml` (neuer Block)

```yaml
# ── Incoming: policy.v1.issued → assign insured number on first policy ────────
mp:
  messaging:
    incoming:
      policy-issued-in:
        connector: smallrye-kafka
        topic: policy.v1.issued
        value:
          deserializer: org.apache.kafka.common.serialization.StringDeserializer
        group:
          id: partner-service-policy
        auto:
          offset:
            reset: earliest
        failure-strategy: dead-letter-queue
        dead-letter-queue:
          topic: partner-policy-issued-dlq
```

**Datei:** `partner/src/test/resources/application.yml` (ergänzen)

```yaml
# Disable Kafka channel in unit tests
"%test":
  mp:
    messaging:
      incoming:
        policy-issued-in:
          enabled: false
  quarkus:
    kafka:
      devservices:
        enabled: false
```

### 3.3 Consumer: `PolicyIssuedConsumer`

**Datei:** `partner/src/main/java/ch/yuno/partner/infrastructure/messaging/PolicyIssuedConsumer.java`

```java
/**
 * Kafka consumer for policy.v1.issued events from the Policy domain.
 * Assigns an insured number to the referenced partner if they don't have one yet.
 * Idempotent: re-delivery of the same event has no effect if the partner is already insured.
 *
 * This is the first Kafka consumer in the Partner Service – the service was previously
 * a pure event producer.
 */
@ApplicationScoped
public class PolicyIssuedConsumer {

    @Inject PersonCommandService personCommandService;

    @Incoming("policy-issued-in")
    @Transactional
    public void onPolicyIssued(String payload) {
        // 1. Extract partnerId from JSON
        // 2. Call personCommandService.assignInsuredNumberIfAbsent(partnerId)
        // 3. Log result
    }
}
```

### 3.4 Application Service Methode

**Datei:** `partner/src/main/java/ch/yuno/partner/domain/service/PersonCommandService.java`

Neue Methode:

```java
/**
 * Assigns an insured number to the person identified by personId,
 * if they don't already have one.
 * Triggered by policy.v1.issued events.
 * Idempotent: safe to call multiple times for the same person.
 *
 * @return true if a new number was assigned, false if already insured
 */
@Transactional
public boolean assignInsuredNumberIfAbsent(String personId) {
    Person person = findOrThrow(personId);

    if (person.isInsured()) {
        LOG.infof("Person %s already has insured number %s – skipping",
                  personId, person.getInsuredNumber().formatted());
        return false;
    }

    InsuredNumber number = insuredNumberGenerator.nextInsuredNumber();
    person.assignInsuredNumber(number);
    personRepository.save(person);

    // Publish PersonUpdated + PersonState via Outbox (same TX)
    outboxRepository.save(new OutboxEvent(
            UUID.randomUUID(), "person", personId, "PersonUpdated",
            PersonEventPayloadBuilder.TOPIC_PERSON_UPDATED,
            PersonEventPayloadBuilder.buildPersonUpdated(
                    personId, person.getName(), person.getFirstName(), piiEncryptor)));
    outboxRepository.save(new OutboxEvent(
            UUID.randomUUID(), "person", personId, "PersonState",
            PersonEventPayloadBuilder.TOPIC_PERSON_STATE,
            PersonEventPayloadBuilder.buildPersonState(person, piiEncryptor)));

    LOG.infof("Assigned insured number %s to person %s", number.formatted(), personId);
    return true;
}
```

**Neue Dependency im Service:**

```java
@Inject
InsuredNumberGenerator insuredNumberGenerator;
```

---

## Phase 4: Event-Publikation erweitern

### 4.1 `PersonEventPayloadBuilder` anpassen

**Datei:** `partner/src/main/java/ch/yuno/partner/infrastructure/messaging/PersonEventPayloadBuilder.java`

Das Feld `insuredNumber` muss in **alle relevanten Event-Payloads** aufgenommen werden:

| Methode | Änderung |
|---|---|
| `buildPersonState(Person)` | Neues JSON-Feld: `"insuredNumber": "VN-00000042"` (oder `null`) |
| `buildPersonState(Person, PiiEncryptor)` | Gleich – `insuredNumber` ist **kein PII**, wird **nicht** verschlüsselt (es ist eine technische Geschäftsnummer) |
| `buildPersonCreated(...)` | Neues Feld `"insuredNumber": null` (bei Erstellung immer null) |
| `buildPersonUpdated(...)` | Neues Feld `"insuredNumber": "VN-00000042"` (wenn zugewiesen) |

**Kritischer Punkt – `insuredNumber` ist kein PII:**
Die Versichertennummer ist eine vom System generierte Geschäftsnummer (wie `policyNumber`). Sie identifiziert keine Person direkt und unterliegt daher **nicht** dem Crypto-Shredding (ADR-009). Sie wird in Klartext in die Events geschrieben.

### 4.2 PersonUpdated-Event erweitern

Die `buildPersonUpdated`-Methode muss `insuredNumber` enthalten, damit Downstream-Consumer (Policy, Claims, Billing) die Nummer erhalten. Die Signatur muss erweitert werden:

```java
public static String buildPersonUpdated(String personId, String name, String firstName,
                                        InsuredNumber insuredNumber, PiiEncryptor encryptor) {
    String insuredNumberStr = insuredNumber != null
            ? "\"" + insuredNumber.value() + "\"" : "null";
    // ...existing format... + ",\"insuredNumber\":" + insuredNumberStr
}
```

**Alle Aufrufer** von `buildPersonUpdated` in `PersonCommandService` müssen angepasst werden, um `person.getInsuredNumber()` mitzugeben.

---

## Phase 5: UI

### 5.1 Edit-Seite: Versichertennummer anzeigen

**Datei:** `partner/src/main/resources/templates/personen/edit.html`

Neuer Block nach der Personalien-Card (oder innerhalb):

```html
<!-- Versichertennummer -->
<div class="card mt-3">
  <div class="card-header fw-bold">Versicherungsstatus</div>
  <div class="card-body">
    {#if person.insuredNumber}
      <div class="d-flex align-items-center">
        <span class="badge bg-success me-2">Versichert</span>
        <span class="fw-bold font-monospace">{person.insuredNumber.formatted()}</span>
      </div>
    {#else}
      <span class="text-muted">Keine aktive Police (Status: Interessent/Partner)</span>
    {/if}
  </div>
</div>
```

### 5.2 Personen-Liste: Optionale Spalte

**Datei:** `partner/src/main/resources/templates/personen/fragments/personen-row.html`

Neue Spalte nach AHV-Nr.:

```html
<td>
  {#if person.insuredNumber}
    <span class="badge bg-success">VN</span> {person.insuredNumber.formatted()}
  {#else}
    <span class="text-muted">–</span>
  {/if}
</td>
```

**Anpassung `list.html`:** Neue Spaltenüberschrift `<th>Versichertennr.</th>` in der Tabelle.

### 5.3 Personalien-Formular: Readonly-Anzeige

**Datei:** `partner/src/main/resources/templates/personen/fragments/personalien-form.html`

Neuer Block nach der AHV-Nummer (readonly, nicht editierbar):

```html
{#if person.insuredNumber}
<div class="mb-2">
  <label class="form-label">Versichertennummer</label>
  <input type="text" class="form-control" value="{person.insuredNumber.formatted()}" readonly>
  <small class="text-muted">Automatisch vergeben bei Policenaktivierung.</small>
</div>
{/if}
```

---

## Phase 6: ODC-Contracts & Schema-Evolution

### 6.1 `person.v1.state` Avro + ODC

**Datei:** `partner/src/main/resources/contracts/person.v1.state.avsc`

Neues Feld (abwärtskompatibel, da `nullable: true` + `default: null`):

```json
{
  "name": "insuredNumber",
  "type": ["null", "string"],
  "default": null,
  "doc": "Insured number (VN-XXXXXXXX), assigned when first policy is activated. Null if not insured."
}
```

**Datei:** `partner/src/main/resources/contracts/person.v1.state.odcontract.yaml`

```yaml
    - name: insuredNumber
      type: string
      required: false
      description: >
        Unique insured number (format: VN-XXXXXXXX), assigned when the person's first 
        policy is activated. Null for persons without an active insurance relationship.
```

### 6.2 `person.v1.updated` ODC

**Datei:** `partner/src/main/resources/contracts/person.v1.updated.odcontract.yaml`

```yaml
    - name: insuredNumber
      type: string
      nullable: true
      description: "Insured number (VN-XXXXXXXX) or null if not yet insured."
```

### 6.3 `person.v1.created` ODC + Avro

Neues Feld `insuredNumber` mit `nullable: true`, `default: null`. Bei Erstellung immer `null` – die Nummer wird erst bei `policy.v1.issued` vergeben.

### 6.4 Abwärtskompatibilität

| Aspekt | Bewertung |
|---|---|
| Neues nullable Feld in JSON | ✅ Kein Breaking Change – bestehende Consumer ignorieren unbekannte Felder |
| Avro-Schema | ✅ `default: null` → vorwärts- und rückwärtskompatibel |
| Policy-Service `PartnerView` | ⚠️ Speichert aktuell nur `partnerId` + `name` → ignoriert `insuredNumber` (kein Handlungsbedarf) |
| Claims-Service `PartnerSearchView` | ⚠️ Speichert `partnerId`, `lastName`, `firstName`, `dateOfBirth`, `socialSecurityNumber` → ignoriert `insuredNumber` (kein Handlungsbedarf, ausser Anzeige gewünscht) |
| Billing-Service `PolicyholderView` | ⚠️ Speichert `personId` + `fullName` → ignoriert `insuredNumber` (könnte für Rechnungsstellung interessant sein, aber separates Feature) |

---

## Phase 7: Tests

### 7.1 Unit Tests

| Test | Datei | Prüft |
|---|---|---|
| `InsuredNumberTest` | `test/domain/model/InsuredNumberTest.java` | Validierung (Format, Null, Blank); `fromSequence()` → korrekte Formatierung; Gleichheit |
| `PersonAssignInsuredNumberTest` | `test/domain/model/PersonTest.java` (neu oder erweitern) | `assignInsuredNumber()`: Erstaufruf = true + Nummer gesetzt; Zweitaufruf = false + Nummer unverändert (Idempotenz); Null-Argument → Exception |
| `PersonCommandServiceTest` (erweitern) | `test/domain/PersonCommandServiceTest.java` | `assignInsuredNumberIfAbsent()`: Mock `InsuredNumberGenerator`, Mock `PersonRepository`; Test: neue Zuweisung → 2 Outbox-Events; Test: bereits vorhanden → 0 Outbox-Events |

### 7.2 Integration Tests

| Test | Datei | Setup | Prüft |
|---|---|---|---|
| `InsuredNumberSequenceAdapterIT` | `test/infrastructure/persistence/InsuredNumberSequenceAdapterIT.java` | `@QuarkusTest` + Testcontainers PG | Sequence erzeugt fortlaufende Nummern; Format `VN-XXXXXXXX` korrekt |
| `PersonInsuredNumberPersistenceIT` | `test/integration/PersonInsuredNumberPersistenceIT.java` | `@QuarkusTest` + Testcontainers PG | Person erstellen → `insuredNumber` ist null; `assignInsuredNumber` → Persist + Reload → Nummer vorhanden; UNIQUE Constraint: zwei Personen mit gleicher Nummer → Exception |
| `PolicyIssuedConsumerIT` | `test/infrastructure/messaging/PolicyIssuedConsumerIT.java` | `@QuarkusTest` + InMemory Kafka | Policy-Issued-Event senden → Person hat danach `insuredNumber`; Erneut senden → idempotent, keine Änderung |
| `PersonOutboxRoundtripIT` (erweitern) | `test/integration/PersonOutboxRoundtripIT.java` | `@QuarkusTest` + Testcontainers PG | `assignInsuredNumberIfAbsent()` → 2 Outbox-Events (PersonUpdated + PersonState); Payload enthält `"insuredNumber":"VN-..."` |

---

## Dateiübersicht (Partner-Service, Phase 1–7)

> Dateiübersicht für Downstream-Services siehe **Aktualisierte Dateiübersicht (Phase 8–11)** weiter unten.

### Neue Dateien (10)

| # | Datei | Phase |
|---|---|---|
| 1 | `partner/src/main/java/ch/yuno/partner/domain/model/InsuredNumber.java` | 1 |
| 2 | `partner/src/main/java/ch/yuno/partner/domain/port/out/InsuredNumberGenerator.java` | 2 |
| 3 | `partner/src/main/java/ch/yuno/partner/infrastructure/persistence/InsuredNumberSequenceAdapter.java` | 2 |
| 4 | `partner/src/main/resources/db/migration/V9__Add_Insured_Number.sql` | 2 |
| 5 | `partner/src/main/java/ch/yuno/partner/infrastructure/messaging/PolicyIssuedConsumer.java` | 3 |
| 6 | `partner/src/test/java/ch/yuno/partner/domain/model/InsuredNumberTest.java` | 7 |
| 7 | `partner/src/test/java/ch/yuno/partner/domain/model/PersonAssignInsuredNumberTest.java` | 7 |
| 8 | `partner/src/test/java/ch/yuno/partner/infrastructure/persistence/InsuredNumberSequenceAdapterIT.java` | 7 |
| 9 | `partner/src/test/java/ch/yuno/partner/integration/PersonInsuredNumberPersistenceIT.java` | 7 |
| 10 | `partner/src/test/java/ch/yuno/partner/infrastructure/messaging/PolicyIssuedConsumerIT.java` | 7 |

### Geänderte Dateien (14)

| # | Datei | Änderung | Phase |
|---|---|---|---|
| 1 | `partner/.../domain/model/Person.java` | `insuredNumber`-Feld, `assignInsuredNumber()`, `isInsured()`, Getter, Reconstruction-Konstruktor erweitern | 1 |
| 2 | `partner/.../infrastructure/persistence/PersonEntity.java` | `insured_number`-Spalte + Getter/Setter | 2 |
| 3 | `partner/.../infrastructure/persistence/PersonJpaAdapter.java` | `toEntity()`, `updateEntity()`, `toDomain()` anpassen | 2 |
| 4 | `partner/.../domain/service/PersonCommandService.java` | `assignInsuredNumberIfAbsent()` + neue Dependency `InsuredNumberGenerator` | 3 |
| 5 | `partner/src/main/resources/application.yml` | SmallRye Kafka `policy-issued-in` Channel-Konfiguration | 3 |
| 6 | `partner/src/test/resources/application.yml` | `policy-issued-in.enabled: false` | 3 |
| 7 | `partner/.../messaging/PersonEventPayloadBuilder.java` | `insuredNumber` in `buildPersonState`, `buildPersonUpdated`, `buildPersonCreated` | 4 |
| 8 | `partner/src/main/resources/templates/personen/edit.html` | Versicherungsstatus-Card | 5 |
| 9 | `partner/src/main/resources/templates/personen/fragments/personen-row.html` | Neue Spalte Versichertennr. | 5 |
| 10 | `partner/src/main/resources/templates/personen/fragments/personalien-form.html` | Readonly Versichertennummer-Feld | 5 |
| 11 | `partner/src/main/resources/contracts/person.v1.state.avsc` | `insuredNumber`-Feld (nullable) | 6 |
| 12 | `partner/src/main/resources/contracts/person.v1.state.odcontract.yaml` | `insuredNumber`-Feld (nullable) | 6 |
| 13 | `partner/src/main/resources/contracts/person.v1.updated.odcontract.yaml` | `insuredNumber`-Feld (nullable) | 6 |
| 14 | `partner/src/main/resources/contracts/person.v1.created.odcontract.yaml` | `insuredNumber`-Feld (nullable, always null) | 6 |

---

## Abhängigkeitsgraph (Partner-Service, Phase 1–7)

```
Phase 1: Domain Model
  ├── InsuredNumber (Value Object)
  └── Person.assignInsuredNumber()
           │
           ├────────────────────────────────┐
           ▼                                ▼
Phase 2: Persistenz                 Phase 4: Event-Payloads
  ├── V9 Migration (Spalte + Seq)     └── PersonEventPayloadBuilder
  ├── InsuredNumberGenerator (Port)       + insuredNumber in State/Updated/Created
  ├── InsuredNumberSequenceAdapter             │
  ├── PersonEntity + Adapter                   ├──► Phase 8  (Claims)
           │                                   ├──► Phase 9  (Policy)
           ▼                                   ├──► Phase 10 (Billing)
Phase 3: Kafka Consumer                        └──► Phase 11 (Analytics)
  ├── application.yml (Channel)
  ├── PolicyIssuedConsumer
  └── PersonCommandService.assignInsuredNumberIfAbsent()
           │
           ├──────────────────┐
           ▼                  ▼
Phase 5: UI               Phase 6: ODC Contracts
  ├── edit.html              ├── person.v1.state.avsc
  ├── personen-row.html      ├── person.v1.state.odcontract.yaml
  └── personalien-form.html  ├── person.v1.updated.odcontract.yaml
                             └── person.v1.created.odcontract.yaml
           │
           ▼
Phase 7: Tests
  ├── InsuredNumberTest (Unit)
  ├── PersonAssignInsuredNumberTest (Unit)
  ├── PersonCommandServiceTest (erweitern)
  ├── InsuredNumberSequenceAdapterIT
  ├── PersonInsuredNumberPersistenceIT
  ├── PolicyIssuedConsumerIT
  └── PersonOutboxRoundtripIT (erweitern)
```

---

## Risiken & Mitigationen (Partner-Service, Phase 1–7)

> Weitere Risiken für die Downstream-Services siehe **Erweiterte Risiken & Mitigationen (Phase 8–11)** weiter unten.

| Risiko | Wahrscheinlichkeit | Auswirkung | Mitigation |
|---|---|---|---|
| **Erster Kafka-Consumer im Partner-Service** – unbekanntes Territory, mögliche Config-Fehler | Mittel | Consumer startet nicht | Billing-Service als Referenz nutzen (identisches Pattern: `partner-state-in` Channel); Integration Test mit InMemory-Channel |
| **Reconstruction-Konstruktor-Break** – neuer 7. Parameter bricht alle bestehenden Aufrufe | Hoch | Compilerfehler | Bewusst in Phase 1 → sofort in Phase 2 (JPA Adapter) und Tests fixen; alternativ Builder-Pattern erwägen |
| **`quarkus-messaging-kafka` Dependency fehlt** | Mittel | Consumer wird nicht erkannt | `pom.xml` prüfen; Debezium-Outbox-Dependency bringt evtl. nicht den Consumer mit → ggf. `io.quarkus:quarkus-messaging-kafka` explizit hinzufügen |
| **Sequence-Lücken** bei Rollbacks | Niedrig | Nummern nicht lückenlos | Akzeptabel – PostgreSQL-Sequences garantieren Eindeutigkeit, nicht Lückenlosigkeit. Falls Business-Anforderung: alternative Strategie (z.B. Tabellen-basierter Counter) |
| **Doppelte Nummernvergabe bei Concurrent Events** | Niedrig | UNIQUE Constraint-Verletzung | Unmöglich: `insured_number_seq` ist atomar; `UNIQUE` Constraint als Safety Net; `assignInsuredNumber()` prüft erst in-memory |
| **Eventual Consistency** – Sachbearbeiter sieht Nummer nicht sofort | Niedrig | UX-Irritation | UI-Hinweis "Versichertennummer wird automatisch vergeben" + optionaler htmx-Polling-Trigger auf der Detailseite |

---

---

## Phase 8: Claims-Service – Versichertennummer in Partner-Suche & Schadenfall-Anzeige

> **Voraussetzung:** Phase 4 (Event-Payloads enthalten `insuredNumber`) + [plan-claims-partner-search.md](plan-claims-partner-search.md) (PartnerSearchView existiert).  
> Falls der Claims-Partner-Search-Plan noch nicht umgesetzt ist, werden die Änderungen hier als Delta auf dessen Architektur beschrieben.

### 8.1 Architektonischer Überblick

```
Partner Service                              Claims Service
────────────────                             ──────────────────────────────────────
person.v1.state (Kafka, compacted)           
  { ..., "insuredNumber": "VN-00000042" }    
    ──────────────────────────────────────►  PersonStateConsumer
                                               → ACL: PersonStateEventTranslator
                                               → PartnerSearchView + insuredNumber
                                               → partner_search_view Tabelle

                                             Sachbearbeiter sucht per VN-Nummer:
                                             ┌──────────────────────────────────┐
                                             │ [VN-00000042      ] [Suchen]    │
                                             └──────────────────────────────────┘
                                               → Exakter Treffer → Policen anzeigen
```

**Use Cases:**
1. **Suche per Versichertennummer:** Sachbearbeiter gibt `VN-00000042` ein → exakter Treffer → direkt Policen des Partners anzeigen.
2. **Anzeige in Ergebnisliste:** Bei Namenssuche wird die Versichertennummer als zusätzliches Identifikationsmerkmal angezeigt (neben Geburtsdatum und maskierter AHV-Nr.).
3. **Anzeige in Schadenliste:** Die Versichertennummer erscheint in der Schadenfall-Übersichtstabelle neben dem Partnernamen.

---

### 8.2 Domain Model erweitern: `PartnerSearchView`

**Datei:** `claims/src/main/java/ch/yuno/claims/domain/model/PartnerSearchView.java`

Neues Feld im Record:

```java
public record PartnerSearchView(
    String partnerId,
    String lastName,
    String firstName,
    LocalDate dateOfBirth,
    String socialSecurityNumber,
    String insuredNumber              // NEU – nullable, Format "VN-XXXXXXXX"
) {
    // ...existing validation...
    // Neue Convenience-Methode:

    /** Returns true if this partner has an active insurance relationship. */
    public boolean isInsured() {
        return insuredNumber != null && !insuredNumber.isBlank();
    }
}
```

---

### 8.3 Flyway-Migration

**Datei:** `claims/src/main/resources/db/migration/V7__add_insured_number_to_partner_search_view.sql`

> Falls `V6__create_partner_search_view.sql` noch nicht angewandt wurde, kann `insured_number` direkt in V6 integriert werden. Andernfalls als separate Migration:

```sql
-- Add insured number to partner_search_view for VN-based search.
-- Nullable: not all partners are insured yet.
-- UNIQUE is intentionally NOT set here – uniqueness is owned by the Partner domain.
ALTER TABLE partner_search_view ADD COLUMN insured_number VARCHAR(11);

-- B-Tree index for exact insured-number lookup (VN-XXXXXXXX).
CREATE INDEX idx_partner_search_insured_number
    ON partner_search_view (insured_number)
    WHERE insured_number IS NOT NULL;
```

**Warum kein UNIQUE Constraint?** Die Eindeutigkeit wird vom Partner-Service garantiert (DB-Sequence + UNIQUE Constraint dort). Der Claims-Service als Consumer spiegelt nur die Daten und soll bei eventuellen Inkonsistenzen nicht mit Constraint-Verletzungen brechen.

---

### 8.4 JPA Entity erweitern: `PartnerSearchViewEntity`

**Datei:** `claims/src/main/java/ch/yuno/claims/infrastructure/persistence/PartnerSearchViewEntity.java`

```java
// Neues Feld:
@Column(name = "insured_number", length = 11)
private String insuredNumber;

// Getter + Setter:
public String getInsuredNumber() { return insuredNumber; }
public void setInsuredNumber(String insuredNumber) { this.insuredNumber = insuredNumber; }
```

---

### 8.5 JPA Adapter erweitern: `PartnerSearchViewJpaAdapter`

**Datei:** `claims/src/main/java/ch/yuno/claims/infrastructure/persistence/PartnerSearchViewJpaAdapter.java`

| Methode | Änderung |
|---|---|
| `upsert()` | `entity.setInsuredNumber(view.insuredNumber());` |
| `toDomain()` | 6. Parameter: `entity.getInsuredNumber()` |
| **Neue Methode** `findByInsuredNumber(String)` | Exakter Lookup auf `idx_partner_search_insured_number` |

```java
@Override
public Optional<PartnerSearchView> findByInsuredNumber(String insuredNumber) {
    List<PartnerSearchViewEntity> results = em.createQuery(
            "SELECT e FROM PartnerSearchViewEntity e WHERE e.insuredNumber = :vn",
            PartnerSearchViewEntity.class)
        .setParameter("vn", insuredNumber)
        .setMaxResults(1)
        .getResultList();
    return results.stream().findFirst().map(this::toDomain);
}
```

---

### 8.6 Outbound Port erweitern: `PartnerSearchViewRepository`

**Datei:** `claims/src/main/java/ch/yuno/claims/domain/port/out/PartnerSearchViewRepository.java`

Neue Methode:

```java
/** Exact lookup by insured number (VN-XXXXXXXX). */
Optional<PartnerSearchView> findByInsuredNumber(String insuredNumber);
```

---

### 8.7 ACL erweitern: `PersonStateEventTranslator`

**Datei:** `claims/src/main/java/ch/yuno/claims/infrastructure/messaging/acl/PersonStateEventTranslator.java`

Neues Feld aus JSON extrahieren:

```java
// In translate():
String insuredNumber = json.path("insuredNumber").asText(null);
// → an PartnerSearchView übergeben (6. Parameter)
```

**Abwärtskompatibilität:** Wenn alte Events ohne `insuredNumber` verarbeitet werden, liefert `json.path("insuredNumber").asText(null)` → `null`. Kein Breaking Change.

---

### 8.8 Application Service erweitern

**Datei:** `claims/src/main/java/ch/yuno/claims/domain/service/ClaimApplicationService.java`

Neue Methode:

```java
/**
 * Finds a partner by their insured number (VN-XXXXXXXX).
 * Used by the FNOL search UI as a third search mode alongside name and AHV search.
 */
@RolesAllowed({"CLAIMS_AGENT", "UNDERWRITER"})
public Optional<PartnerSearchView> findPartnerByInsuredNumber(String insuredNumber) {
    if (insuredNumber == null || insuredNumber.isBlank()) return Optional.empty();
    return partnerSearchViewRepository.findByInsuredNumber(insuredNumber.trim().toUpperCase());
}
```

---

### 8.9 UI: FNOL Partner-Suche um Versichertennummer erweitern

#### 8.9.1 Neuer Such-Tab im FNOL-Formular

**Datei:** `claims/src/main/resources/templates/schaeden/form.html`

Erweiterung der Partner-Suchmaske um einen dritten Tab:

```
┌─ Tab: Name ─┬─ Tab: AHV-Nr. ─┬─ Tab: Versichertennr. ──┐
│                                                           │
│  ┌─ Tab: Versichertennr. ───────────────────────────┐    │
│  │ [VN-________ ]  [Suchen]                          │    │
│  └───────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────┘
```

```html
<!-- Tab: Versichertennummer -->
<div class="tab-pane fade" id="tab-vn" role="tabpanel">
  <div class="input-group">
    <span class="input-group-text">VN-</span>
    <input type="text" class="form-control font-monospace" id="search-vn"
           placeholder="00000042" maxlength="11"
           hx-get="/claims/fragments/partner-search"
           hx-target="#partner-results"
           hx-trigger="keyup changed delay:500ms"
           hx-params="vn"
           name="vn">
  </div>
  <small class="text-muted">Versichertennummer eingeben (z.B. VN-00000042)</small>
</div>
```

#### 8.9.2 Controller-Endpunkt erweitern

**Datei:** `claims/src/main/java/ch/yuno/claims/infrastructure/web/ClaimUiController.java`

Der bestehende `GET /claims/fragments/partner-search` Endpunkt wird um den `vn`-Parameter erweitert:

```java
@GET
@Path("/fragments/partner-search")
@Produces(MediaType.TEXT_HTML)
public TemplateInstance partnerSearch(
        @QueryParam("q") String nameQuery,
        @QueryParam("ahv") String ahvQuery,
        @QueryParam("vn") String vnQuery) {          // NEU
    
    List<PartnerSearchView> partners;
    if (vnQuery != null && !vnQuery.isBlank()) {
        // Exakte Suche per Versichertennummer → 0 oder 1 Treffer
        partners = claimApplicationService
                .findPartnerByInsuredNumber(vnQuery)
                .map(List::of).orElse(List.of());
    } else if (ahvQuery != null && !ahvQuery.isBlank()) {
        partners = claimApplicationService
                .findPartnerBySocialSecurityNumber(ahvQuery)
                .map(List::of).orElse(List.of());
    } else {
        partners = claimApplicationService.searchPartners(nameQuery);
    }
    return partnerSearchResults.data("partners", partners);
}
```

#### 8.9.3 Suchergebnis-Fragment erweitern

**Datei:** `claims/src/main/resources/templates/schaeden/fragments/partner-search-results.html`

Neue Spalte in der Ergebnistabelle:

```html
<thead>
  <tr>
    <th>Name</th>
    <th>Vorname</th>
    <th>Versichertennr.</th>    <!-- NEU -->
    <th>Geburtsdatum</th>
    <th>AHV-Nr.</th>
    <th></th>
  </tr>
</thead>
<tbody>
  {#for partner in partners}
  <tr>
    <td><strong>{partner.lastName}</strong></td>
    <td>{partner.firstName}</td>
    <td>                           <!-- NEU -->
      {#if partner.insuredNumber}
        <span class="badge bg-success font-monospace">{partner.insuredNumber}</span>
      {#else}
        <span class="text-muted">–</span>
      {/if}
    </td>
    <td>{partner.formattedDateOfBirth ?: '–'}</td>
    <td class="text-monospace">{partner.maskedSocialSecurityNumber ?: '–'}</td>
    <td>
      <button class="btn btn-sm btn-outline-primary"
              hx-get="/claims/fragments/partner/{partner.partnerId}/policies"
              hx-target="#policy-picker">
        Policen anzeigen
      </button>
    </td>
  </tr>
  {/for}
</tbody>
```

#### 8.9.4 Schadenliste: Versichertennummer anzeigen

**Datei:** `claims/src/main/resources/templates/schaeden/list.html`

Erweiterung: Die Schadenliste zeigt neben der Police-ID auch den Partnernamen und die Versichertennummer an. Dafür muss der `ClaimUiController.listClaims()` die `PartnerSearchView`-Daten mitliefern (via `PolicySnapshot.partnerId` → `PartnerSearchViewRepository.findByPartnerId()`).

```html
<thead class="table-dark">
  <tr>
    <th>Schadennummer</th>
    <th>Versicherter</th>           <!-- NEU: statt nur "Police-ID" -->
    <th>Police</th>
    <th>Beschreibung</th>
    <th>Schadendatum</th>
    <th>Status</th>
    <th>Aktionen</th>
  </tr>
</thead>
<tbody>
  {#for schaden in schaeden}
  <tr id="claim-{schaden.claimId}">
    <td><strong>{schaden.claimNumber}</strong></td>
    <td>                             <!-- NEU -->
      {#if partnerMap.get(schaden.policyId)}
        {#let partner = partnerMap.get(schaden.policyId)}
          {partner.firstName} {partner.lastName}
          {#if partner.insuredNumber}
            <br><span class="badge bg-success badge-sm font-monospace">{partner.insuredNumber}</span>
          {/if}
        {/let}
      {#else}
        <span class="text-muted">–</span>
      {/if}
    </td>
    <td><small class="text-muted">{schaden.policyId}</small></td>
    <!-- ...existing columns... -->
  </tr>
  {/for}
</tbody>
```

**Datenfluss für `partnerMap`:** Der `ClaimUiController` baut eine `Map<String, PartnerSearchView>` auf, indem er für jeden Schadenfall die `partnerId` aus dem `PolicySnapshot` holt und die zugehörige `PartnerSearchView` lädt. Caching via `@CacheResult` empfohlen bei >100 Schadenfällen pro Seite.

---

### 8.10 Tests

| Test | Datei | Prüft |
|---|---|---|
| `PartnerSearchViewTest` (erweitern) | `test/domain/model/PartnerSearchViewTest.java` | `isInsured()` = true wenn `insuredNumber` gesetzt; false wenn null |
| `PersonStateEventTranslatorTest` (erweitern) | `test/infrastructure/messaging/acl/PersonStateEventTranslatorTest.java` | JSON mit `insuredNumber` → korrekt extrahiert; JSON ohne `insuredNumber` → null |
| `PartnerSearchViewJpaAdapterIT` (erweitern) | `test/infrastructure/persistence/PartnerSearchViewJpaAdapterIT.java` | `findByInsuredNumber("VN-00000042")` → exakter Treffer; nicht existierende Nummer → `Optional.empty()` |
| `ClaimApplicationServiceTest` (erweitern) | `test/domain/service/ClaimApplicationServiceTest.java` | `findPartnerByInsuredNumber()` mit Mock |

---

## Phase 9: Policy-Service – Versichertennummer in PartnerView & UI

> **Voraussetzung:** Phase 4 (Events enthalten `insuredNumber`).

### 9.1 Architektonischer Überblick

Der Policy-Service konsumiert `person.v1.created` und `person.v1.updated`. Beide Events enthalten nach Phase 4 das Feld `insuredNumber`. Das lokale `PartnerView`-Read-Model wird erweitert, um die Versichertennummer zu speichern und in der UI anzuzeigen.

**Use Cases:**
1. **Anzeige in Policenliste:** Partner-Spalte zeigt Versichertennummer als Badge neben dem Namen.
2. **Anzeige auf Policen-Detailseite:** Versichertennummer als Teil der Partnerinformationen.
3. **Suche per Versichertennummer:** Neuer Suchfilter in der Policenliste.

---

### 9.2 Domain Model erweitern: `PartnerView`

**Datei:** `policy/src/main/java/ch/yuno/policy/domain/model/PartnerView.java`

```java
/**
 * Read Model: PartnerView – local materialization of Partner/Person data.
 * Populated by consuming person.v1.created and person.v1.updated Kafka events.
 */
public record PartnerView(String partnerId, String name, String insuredNumber) {

    public PartnerView {
        if (partnerId == null || partnerId.isBlank()) throw new IllegalArgumentException("partnerId required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        // insuredNumber is nullable – null until first policy activation
    }

    /** Backward-compatible constructor for existing callers. */
    public PartnerView(String partnerId, String name) {
        this(partnerId, name, null);
    }

    public boolean isInsured() { return insuredNumber != null; }

    public String getPartnerId() { return partnerId; }
    public String getName() { return name; }
    public String getInsuredNumber() { return insuredNumber; }
}
```

**Abwärtskompatibilität:** Der 2-Parameter-Konstruktor bleibt erhalten → keine Breaking Changes in existierenden Tests.

---

### 9.3 Flyway-Migration

**Datei:** `policy/src/main/resources/db/migration/V8__add_insured_number_to_partner_sicht.sql`

```sql
-- Add insured number to partner_sicht (PartnerView read model).
-- Nullable: not all partners have an insured number yet.
ALTER TABLE partner_sicht ADD COLUMN insured_number VARCHAR(11);

-- Index for search by insured number.
CREATE INDEX idx_partner_sicht_insured_number
    ON partner_sicht (insured_number)
    WHERE insured_number IS NOT NULL;
```

---

### 9.4 JPA Entity erweitern: `PartnerViewEntity`

**Datei:** `policy/src/main/java/ch/yuno/policy/infrastructure/persistence/PartnerViewEntity.java`

```java
// Neues Feld:
@Column(name = "insured_number", length = 11)
private String insuredNumber;

public String getInsuredNumber() { return insuredNumber; }
public void setInsuredNumber(String insuredNumber) { this.insuredNumber = insuredNumber; }
```

---

### 9.5 JPA Adapter anpassen: `PartnerViewJpaAdapter`

**Datei:** `policy/src/main/java/ch/yuno/policy/infrastructure/persistence/PartnerViewJpaAdapter.java`

| Methode | Änderung |
|---|---|
| `upsert()` | `entity.setInsuredNumber(partner.getInsuredNumber());` |
| `toDomain()` | 3. Parameter: `e.getInsuredNumber()` |
| `search()` | Erweitern: wenn Query `VN-` Präfix hat → exakter Lookup auf `insured_number` |

```java
@Override
public List<PartnerView> search(String nameQuery) {
    if (nameQuery == null || nameQuery.isBlank()) {
        return em.createQuery(
                "SELECT p FROM PartnerViewEntity p ORDER BY p.name", PartnerViewEntity.class)
                .setMaxResults(20)
                .getResultList().stream().map(this::toDomain).toList();
    }
    // NEU: VN-Suche bei passendem Präfix
    if (nameQuery.toUpperCase().startsWith("VN-")) {
        return em.createQuery(
                "SELECT p FROM PartnerViewEntity p WHERE p.insuredNumber = :vn",
                PartnerViewEntity.class)
                .setParameter("vn", nameQuery.toUpperCase())
                .setMaxResults(1)
                .getResultList().stream().map(this::toDomain).toList();
    }
    return em.createQuery(
            "SELECT p FROM PartnerViewEntity p WHERE LOWER(p.name) LIKE :q ORDER BY p.name",
            PartnerViewEntity.class)
            .setParameter("q", "%" + nameQuery.toLowerCase() + "%")
            .setMaxResults(20)
            .getResultList().stream().map(this::toDomain).toList();
}

private PartnerView toDomain(PartnerViewEntity e) {
    return new PartnerView(e.getPartnerId(), e.getName(), e.getInsuredNumber());
}
```

---

### 9.6 ACL anpassen: `PartnerEventTranslator`

**Datei:** `policy/src/main/java/ch/yuno/policy/infrastructure/messaging/acl/PartnerEventTranslator.java`

```java
public PartnerView translate(String payload) throws Exception {
    JsonNode json = objectMapper.readTree(payload);
    String partnerId = json.path("personId").asText(null);
    String firstName = json.path("firstName").asText("");
    String name = json.path("name").asText("");
    String fullName = (firstName + " " + name).trim();
    String insuredNumber = json.path("insuredNumber").asText(null);   // NEU

    if (partnerId == null || partnerId.isEmpty() || fullName.isEmpty()) {
        throw new IllegalArgumentException(
                "Partner event missing required fields: personId=" + partnerId + " name=" + fullName);
    }

    return new PartnerView(partnerId, fullName, insuredNumber);       // NEU: 3. Parameter
}
```

---

### 9.7 UI: Policenliste – Versichertennummer anzeigen

**Datei:** `policy/src/main/resources/templates/policen/list.html`

Erweiterung der Partner-Spalte:

```html
<td>
  {#if partnerSichten.get(policy.partnerId)}
    {partnerSichten.get(policy.partnerId).name}
    {#if partnerSichten.get(policy.partnerId).insuredNumber}
      <br><span class="badge bg-success badge-sm font-monospace">
        {partnerSichten.get(policy.partnerId).insuredNumber}
      </span>
    {/if}
    <br><small class="text-muted">{policy.partnerId}</small>
  {#else}
    <span class="text-muted small">{policy.partnerId}</span>
  {/if}
</td>
```

**Datei:** `policy/src/main/resources/templates/policen/list.html` – Suchfeld erweitern:

Das bestehende `Partner-ID`-Suchfeld wird zu einem kombinierten Feld, das sowohl UUID als auch VN-Nummer akzeptiert:

```html
<div class="col-md-3">
  <input type="text" id="s-partner" name="partnerId" class="form-control"
         placeholder="Partner-ID oder VN-Nummer"
         hx-get="/policies/fragments/list"
         hx-include="#s-nummer,#s-partner,#s-status"
         hx-target="#policen-tabelle" hx-swap="innerHTML"
         hx-trigger="keyup changed delay:300ms">
</div>
```

Der `PolicyUiController` erkennt am `VN-`-Präfix, ob eine Versichertennummer gesucht wird, und nutzt `partnerViewRepository.search()` (welches seit 9.5 VN-Suche unterstützt), um die passende `partnerId` aufzulösen → filtert dann die Policen nach dieser `partnerId`.

---

### 9.8 UI: Partner-Such-Widget – Versichertennummer anzeigen

**Datei:** `policy/src/main/resources/templates/policen/fragments/partner-search-widget.html`

Erweiterung der Ergebniszeilen:

```html
{#for p in partnerSichten}
<div class="list-group-item list-group-item-action partner-result" ...>
  <div class="d-flex justify-content-between align-items-center">
    <div>
      <strong>{p.name}</strong>
      {#if p.insuredNumber}
        <span class="badge bg-success ms-2 font-monospace">{p.insuredNumber}</span>
      {/if}
    </div>
    <span class="badge bg-secondary">{p.partnerId}</span>
  </div>
</div>
{/for}
```

---

### 9.9 Tests

| Test | Datei | Prüft |
|---|---|---|
| `PartnerViewTest` (neu) | `test/domain/model/PartnerViewTest.java` | 3-Param Konstruktor; 2-Param Backward-Compat → `insuredNumber == null`; `isInsured()` |
| `PartnerEventTranslatorTest` (erweitern) | `test/infrastructure/messaging/acl/PartnerEventTranslatorTest.java` | JSON mit `insuredNumber` → korrekt extrahiert; JSON ohne → null (Backward-Compat) |
| `PartnerViewJpaAdapterIT` (erweitern) | `test/infrastructure/persistence/PartnerViewJpaAdapterIT.java` | Upsert mit insuredNumber; `search("VN-00000042")` → exakter Treffer; `toDomain()` mit 3 Feldern |

---

## Phase 10: Billing-Service – Versichertennummer in Rechnungsanzeige

> **Voraussetzung:** Phase 4 (Events enthalten `insuredNumber`).

### 10.1 Architektonischer Überblick

Der Billing-Service konsumiert `person.v1.state` (compacted) und baut ein `PolicyholderView`-Read-Model. Die Versichertennummer wird als identifizierendes Merkmal auf Rechnungen angezeigt – ein häufiger Bedarf in der Rechnungsstellung, da Kunden oft ihre VN-Nummer als Referenz angeben.

**Use Cases:**
1. **Anzeige auf Rechnungsliste:** Versichertennummer neben dem Versicherungsnehmer-Namen.
2. **Suche per Versichertennummer:** Rechnungen eines Versicherten finden, wenn nur die VN-Nummer bekannt ist.
3. **Rechnungs-PDF (Zukunft):** Die Versichertennummer kann später auf dem gedruckten Rechnungsdokument erscheinen.

---

### 10.2 Domain Model erweitern: `PolicyholderView`

**Datei:** `billing/src/main/java/ch/yuno/billing/domain/model/PolicyholderView.java`

```java
/**
 * Read model: local materialization of Partner domain data.
 * Built from person.v1.state events (Event-Carried State Transfer).
 */
public record PolicyholderView(String partnerId, String name, String insuredNumber) {

    public PolicyholderView {
        if (partnerId == null || partnerId.isBlank()) throw new IllegalArgumentException("partnerId is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        // insuredNumber nullable
    }

    /** Backward-compatible constructor. */
    public PolicyholderView(String partnerId, String name) {
        this(partnerId, name, null);
    }

    public boolean isInsured() { return insuredNumber != null; }
}
```

---

### 10.3 Flyway-Migration

**Datei:** `billing/src/main/resources/db/migration/V5__add_insured_number_to_policyholder_view.sql`

```sql
-- Add insured number to policyholder_view for display on invoices.
ALTER TABLE policyholder_view ADD COLUMN insured_number VARCHAR(11);

-- Index for search by insured number.
CREATE INDEX idx_policyholder_view_insured_number
    ON policyholder_view (insured_number)
    WHERE insured_number IS NOT NULL;
```

---

### 10.4 JPA Entity erweitern: `PolicyholderViewEntity`

**Datei:** `billing/src/main/java/ch/yuno/billing/infrastructure/persistence/PolicyholderViewEntity.java`

```java
// Neues Feld:
@Column(name = "insured_number", length = 11)
private String insuredNumber;

public String getInsuredNumber() { return insuredNumber; }
public void setInsuredNumber(String insuredNumber) { this.insuredNumber = insuredNumber; }
```

---

### 10.5 JPA Adapter anpassen: `PolicyholderViewJpaAdapter`

**Datei:** `billing/src/main/java/ch/yuno/billing/infrastructure/persistence/PolicyholderViewJpaAdapter.java`

| Methode | Änderung |
|---|---|
| `upsert()` | `entity.setInsuredNumber(view.insuredNumber());` |
| `toDomain()` | 3. Parameter: `entity.getInsuredNumber()` |

---

### 10.6 Outbound Port erweitern: `PolicyholderViewRepository`

**Datei:** `billing/src/main/java/ch/yuno/billing/domain/port/out/PolicyholderViewRepository.java`

Neue Methode:

```java
/** Finds a policyholder by insured number (VN-XXXXXXXX). */
Optional<PolicyholderView> findByInsuredNumber(String insuredNumber);
```

Implementierung in `PolicyholderViewJpaAdapter`:

```java
@Override
public Optional<PolicyholderView> findByInsuredNumber(String insuredNumber) {
    List<PolicyholderViewEntity> results = em.createQuery(
            "SELECT e FROM PolicyholderViewEntity e WHERE e.insuredNumber = :vn",
            PolicyholderViewEntity.class)
        .setParameter("vn", insuredNumber)
        .setMaxResults(1)
        .getResultList();
    return results.stream().findFirst().map(this::toDomain);
}
```

---

### 10.7 Kafka Consumer anpassen: `PartnerStateConsumer`

**Datei:** `billing/src/main/java/ch/yuno/billing/infrastructure/messaging/PartnerStateConsumer.java`

Neues Feld extrahieren:

```java
@Transactional
@Incoming("partner-state-in")
public void onPersonState(String message) {
    try {
        JsonNode node = MAPPER.readTree(message);
        String personId = node.path("personId").asText(null);
        if (personId == null || personId.isBlank()) return;

        boolean deleted = node.path("deleted").asBoolean(false);
        if (deleted) {
            log.infof("Skipping deleted person: %s", personId);
            return;
        }

        String firstName = node.path("firstName").asText("");
        String lastName  = node.path("name").asText("");
        String fullName  = (firstName + " " + lastName).trim();
        if (fullName.isBlank()) fullName = personId;

        String insuredNumber = node.path("insuredNumber").asText(null);  // NEU

        policyholderViewRepository.upsert(
                new PolicyholderView(personId, fullName, insuredNumber));  // NEU: 3. Parameter
        log.infof("Upserted PolicyholderView: %s → %s (VN: %s)",
                  personId, fullName, insuredNumber);
    } catch (Exception e) {
        log.errorf("Failed to process person.v1.state: %s", e.getMessage());
    }
}
```

---

### 10.8 UI: Rechnungsliste – Versichertennummer anzeigen & suchen

**Datei:** `billing/src/main/resources/templates/rechnungen/list.html`

Erweiterung der Versicherungsnehmer-Spalte:

```html
<td>
  {#if partnerSichten.get(rechnung.partnerId)}
    {partnerSichten.get(rechnung.partnerId).name}
    {#if partnerSichten.get(rechnung.partnerId).insuredNumber}
      <br><span class="badge bg-success badge-sm font-monospace">
        {partnerSichten.get(rechnung.partnerId).insuredNumber}
      </span>
    {/if}
    <br><small class="text-muted">{rechnung.partnerId}</small>
  {#else}
    <span class="text-muted small">{rechnung.partnerId}</span>
  {/if}
</td>
```

Erweiterung des Suchfelds (analog zu Policy-Service):

```html
<div class="col-md-4">
  <input type="text" id="s-partner" name="partnerId" class="form-control"
         placeholder="Partner-ID oder VN-Nummer"
         hx-get="/billing/fragments/list"
         hx-include="#s-partner,#s-status"
         hx-target="#rechnungen-tabelle" hx-swap="innerHTML"
         hx-trigger="keyup changed delay:300ms">
</div>
```

Der `BillingUiController` erkennt am `VN-`-Präfix eine Versichertennummer-Suche und nutzt `policyholderViewRepository.findByInsuredNumber()` zur Auflösung der `partnerId`.

---

### 10.9 Tests

| Test | Datei | Prüft |
|---|---|---|
| `PolicyholderViewTest` (neu) | `test/domain/model/PolicyholderViewTest.java` | 3-Param Konstruktor; 2-Param Backward-Compat; `isInsured()` |
| `PartnerStateConsumerTest` (erweitern) | `test/infrastructure/messaging/PartnerStateConsumerTest.java` | JSON mit `insuredNumber` → korrekt im Upsert; JSON ohne → null |
| `PolicyholderViewJpaAdapterIT` (erweitern) | `test/infrastructure/persistence/PolicyholderViewJpaAdapterIT.java` | Upsert mit insuredNumber; `findByInsuredNumber()` → exakter Treffer |
| `BillingUiControllerTest` (erweitern) | `test/infrastructure/web/BillingUiControllerTest.java` | Suche mit `VN-00000042` → Auflösung + gefilterte Rechnungen |

---

## Phase 11: Iceberg / Trino / SQLMesh – Analytische Verfügbarkeit

> **Voraussetzung:** Phase 4 (Events enthalten `insuredNumber`), bestehende Iceberg-Sink-Connectors.

### 11.1 Iceberg Sink: Automatische Schema-Evolution

Die bestehenden Debezium Iceberg Sink Connectors (`iceberg-sink-partner.json`) haben `"table.auto-evolve": "true"`. Das neue `insuredNumber`-Feld im `person.v1.state`-Topic wird **automatisch** als neue Spalte in die Iceberg-Tabelle übernommen (nullable String). **Kein manueller Eingriff erforderlich.**

### 11.2 SQLMesh-Modelle

**Datei:** `infra/sqlmesh/models/` – Prüfen, ob bestehende Modelle die Partner-Tabelle verwenden. Falls ja, `insured_number` als optionale Spalte aufnehmen:

```sql
-- In bestehenden Partner-bezogenen Modellen:
SELECT
    ...,
    insured_number   -- NEU: Versichertennummer (nullable)
FROM iceberg.partner.person_v1_state
```

### 11.3 Trino Views (optional)

Für Superset-Dashboards kann ein Trino-View erstellt werden, der Partner mit und ohne Versichertennummer kategorisiert:

```sql
CREATE OR REPLACE VIEW analytics.partner_insurance_status AS
SELECT
    person_id,
    first_name,
    name AS last_name,
    insured_number,
    CASE WHEN insured_number IS NOT NULL THEN 'INSURED' ELSE 'PROSPECT' END AS insurance_status
FROM iceberg.partner.person_v1_state
WHERE deleted = false;
```

### 11.4 Superset Dashboard (optional)

Neues Chart: **Versicherungsquote** – Anteil der Partner mit vs. ohne Versichertennummer. Nützlich für Sales/Marketing-KPIs.

---

## Aktualisierte Dateiübersicht (Phase 8–11)

### Neue Dateien (Phase 8–11)

| # | Datei | Phase |
|---|---|---|
| 1 | `claims/src/main/resources/db/migration/V7__add_insured_number_to_partner_search_view.sql` | 8 |
| 2 | `policy/src/main/resources/db/migration/V8__add_insured_number_to_partner_sicht.sql` | 9 |
| 3 | `billing/src/main/resources/db/migration/V5__add_insured_number_to_policyholder_view.sql` | 10 |
| 4 | `policy/src/test/java/ch/yuno/policy/domain/model/PartnerViewTest.java` | 9 |
| 5 | `billing/src/test/java/ch/yuno/billing/domain/model/PolicyholderViewTest.java` | 10 |

### Geänderte Dateien (Phase 8–11)

| # | Datei | Änderung | Phase |
|---|---|---|---|
| 1 | `claims/.../domain/model/PartnerSearchView.java` | `insuredNumber`-Feld + `isInsured()` | 8 |
| 2 | `claims/.../infrastructure/persistence/PartnerSearchViewEntity.java` | `insured_number`-Spalte | 8 |
| 3 | `claims/.../infrastructure/persistence/PartnerSearchViewJpaAdapter.java` | `upsert`, `toDomain`, `findByInsuredNumber()` | 8 |
| 4 | `claims/.../domain/port/out/PartnerSearchViewRepository.java` | `findByInsuredNumber()` | 8 |
| 5 | `claims/.../infrastructure/messaging/acl/PersonStateEventTranslator.java` | `insuredNumber` extrahieren | 8 |
| 6 | `claims/.../domain/service/ClaimApplicationService.java` | `findPartnerByInsuredNumber()` | 8 |
| 7 | `claims/.../infrastructure/web/ClaimUiController.java` | `vn`-Parameter + VN-Such-Tab | 8 |
| 8 | `claims/.../templates/schaeden/form.html` | Tab „Versichertennr." | 8 |
| 9 | `claims/.../templates/schaeden/fragments/partner-search-results.html` | Spalte Versichertennr. | 8 |
| 10 | `claims/.../templates/schaeden/list.html` | Partner-Spalte mit VN-Badge | 8 |
| 11 | `policy/.../domain/model/PartnerView.java` | `insuredNumber`-Feld + Backward-Compat Konstruktor | 9 |
| 12 | `policy/.../infrastructure/persistence/PartnerViewEntity.java` | `insured_number`-Spalte | 9 |
| 13 | `policy/.../infrastructure/persistence/PartnerViewJpaAdapter.java` | `upsert`, `toDomain`, `search` (VN-Erkennung) | 9 |
| 14 | `policy/.../infrastructure/messaging/acl/PartnerEventTranslator.java` | `insuredNumber` extrahieren | 9 |
| 15 | `policy/.../templates/policen/list.html` | VN-Badge + Suchfeld-Placeholder | 9 |
| 16 | `policy/.../templates/policen/fragments/partner-search-widget.html` | VN-Badge in Ergebnissen | 9 |
| 17 | `billing/.../domain/model/PolicyholderView.java` | `insuredNumber`-Feld + Backward-Compat Konstruktor | 10 |
| 18 | `billing/.../infrastructure/persistence/PolicyholderViewEntity.java` | `insured_number`-Spalte | 10 |
| 19 | `billing/.../infrastructure/persistence/PolicyholderViewJpaAdapter.java` | `upsert`, `toDomain`, `findByInsuredNumber()` | 10 |
| 20 | `billing/.../domain/port/out/PolicyholderViewRepository.java` | `findByInsuredNumber()` | 10 |
| 21 | `billing/.../infrastructure/messaging/PartnerStateConsumer.java` | `insuredNumber` extrahieren + 3-Param Konstruktor | 10 |
| 22 | `billing/.../templates/rechnungen/list.html` | VN-Badge + Suchfeld-Placeholder | 10 |

---

## Aktualisierter Abhängigkeitsgraph (vollständig)

```
Phase 1–7: Partner Service (wie oben)
           │
           │  person.v1.state / person.v1.created / person.v1.updated
           │  (enthalten jetzt insuredNumber)
           │
           ├───────────────────────────┬──────────────────────┐
           ▼                           ▼                      ▼
Phase 8: Claims                Phase 9: Policy          Phase 10: Billing
  ├── V7 Migration               ├── V8 Migration          ├── V5 Migration
  ├── PartnerSearchView           ├── PartnerView            ├── PolicyholderView
  │   + insuredNumber             │   + insuredNumber        │   + insuredNumber
  ├── ACL erweitern               ├── ACL erweitern          ├── Consumer erweitern
  ├── findByInsuredNumber()       ├── search() VN-detect     ├── findByInsuredNumber()
  ├── UI: VN-Such-Tab             ├── UI: VN-Badge           ├── UI: VN-Badge
  ├── UI: Ergebnis-Spalte         ├── UI: Widget-Badge       ├── UI: Suchfeld
  └── UI: Schadenliste            └── UI: Suchfeld           └── Tests
      + Partner + VN                  + VN-Placeholder
                                                              │
           └──────────────────────┬──────────────────────────┘
                                  ▼
                        Phase 11: Analytics
                          ├── Iceberg (auto-evolve)
                          ├── SQLMesh-Modelle
                          ├── Trino View
                          └── Superset Dashboard
```

---

## Aktualisierte Aufwandschätzung

| Phase | Beschreibung | Aufwand |
|---|---|---|
| **1–7** | Partner-Service (wie oben) | ~11h |
| **8** | Claims: VN-Suche, Anzeige, ACL, Tests | ~4h |
| **9** | Policy: PartnerView, ACL, UI, Tests | ~3h |
| **10** | Billing: PolicyholderView, Consumer, UI, Tests | ~3h |
| **11** | Iceberg / Trino / SQLMesh / Superset | ~2h |
| | **Total** | **~23h** |

---

## Erweiterte Risiken & Mitigationen (Phase 8–11)

| Risiko | Wahrscheinlichkeit | Auswirkung | Mitigation |
|---|---|---|---|
| **Backward-Compat-Break in Records** – neue 3-Param Konstruktoren in PartnerView/PolicyholderView brechen existierende Tests | Hoch | Compilerfehler | 2-Param Backward-Compat Konstruktor beibehalten; alle Test-Instantiierungen prüfen |
| **Claims: PartnerSearchView noch nicht umgesetzt** – Phase 8 setzt plan-claims-partner-search.md voraus | Mittel | Phase 8 kann nicht starten | Phase 8 so formuliert, dass Änderungen als Delta auf die Claims-Partner-Suche beschrieben sind; ggf. V6+V7 zusammenlegen |
| **ACL-Änderungen vergessen** – Policy & Billing ACL/Consumer extrahieren `insuredNumber` nicht | Mittel | Feld bleibt immer null im Read-Model | Explizit als Pflicht-Step in Phase 9.6 und 10.7 definiert; Integration Tests prüfen den E2E-Fluss |
| **Iceberg Schema-Evolution schlägt fehl** – Sink Connector erkennt neues Feld nicht | Niedrig | Analytische Daten unvollständig | `table.auto-evolve=true` ist gesetzt; alternativ manuelles `ALTER TABLE` auf Iceberg-Tabelle |
| **Such-UX: Verwechslung VN-Nummer mit Policy-Nummer** – Sachbearbeiter gibt `POL-00042` statt `VN-00000042` ein | Niedrig | Keine Treffer | VN-Suchfeld mit `VN-`-Präfix als Input-Group-Addon; Placeholder-Text zur Orientierung |

---

## Definition of Done

### Partner-Service (Phase 1–7)
- [ ] `InsuredNumber` Value Object mit Validierung und `fromSequence()`
- [ ] `Person.assignInsuredNumber()` – idempotent, getestet
- [ ] Flyway-Migration `V9` – Spalte + Sequence + Audit-Tabelle
- [ ] `InsuredNumberGenerator` Port + Sequence-Adapter
- [ ] `PersonEntity` + `PersonJpaAdapter` – Mapping hin und zurück
- [ ] `PolicyIssuedConsumer` – Kafka Consumer für `policy.v1.issued`
- [ ] `PersonCommandService.assignInsuredNumberIfAbsent()` – idempotent, Outbox-Events
- [ ] `PersonEventPayloadBuilder` – `insuredNumber` in State/Updated/Created
- [ ] Qute UI – Versichertennummer in Edit-Seite, Liste und Personalien-Formular
- [ ] ODC-Contracts – `insuredNumber` (nullable) in allen 3 Topic-Contracts
- [ ] Unit Tests: Value Object, Aggregate, Service
- [ ] Integration Tests: Sequence, Persistence, Consumer, Outbox-Roundtrip

### Claims-Service (Phase 8)
- [ ] `PartnerSearchView` um `insuredNumber` erweitert
- [ ] Flyway-Migration `V7` – Spalte + Index
- [ ] `findByInsuredNumber()` in Repository + JPA Adapter
- [ ] ACL extrahiert `insuredNumber` aus `person.v1.state`
- [ ] `ClaimApplicationService.findPartnerByInsuredNumber()`
- [ ] UI: VN-Such-Tab im FNOL-Formular
- [ ] UI: VN-Spalte in Partner-Suchergebnissen
- [ ] UI: Partner + VN in Schadenliste
- [ ] Tests: Unit + Integration

### Policy-Service (Phase 9)
- [ ] `PartnerView` um `insuredNumber` erweitert (Backward-Compat Konstruktor)
- [ ] Flyway-Migration `V8` – Spalte + Index
- [ ] `PartnerEventTranslator` extrahiert `insuredNumber`
- [ ] `PartnerViewJpaAdapter.search()` erkennt VN-Präfix
- [ ] UI: VN-Badge in Policenliste + Partner-Such-Widget
- [ ] UI: Suchfeld akzeptiert VN-Nummern
- [ ] Tests: Unit + Integration

### Billing-Service (Phase 10)
- [ ] `PolicyholderView` um `insuredNumber` erweitert (Backward-Compat Konstruktor)
- [ ] Flyway-Migration `V5` – Spalte + Index
- [ ] `PartnerStateConsumer` extrahiert `insuredNumber`
- [ ] `findByInsuredNumber()` in Repository + JPA Adapter
- [ ] UI: VN-Badge in Rechnungsliste
- [ ] UI: Suchfeld akzeptiert VN-Nummern
- [ ] Tests: Unit + Integration

### Analytics (Phase 11)
- [ ] Iceberg-Tabelle enthält `insured_number`-Spalte (auto-evolve)
- [ ] SQLMesh-Modelle verwenden `insured_number`
- [ ] Trino View `partner_insurance_status` erstellt
- [ ] Superset Dashboard „Versicherungsquote" verfügbar

### Übergreifend
- [ ] `mvn test` + `mvn verify -Pintegration` grün in **allen 4 Services**
- [ ] Keine Breaking Changes für bestehende Downstream-Consumer

