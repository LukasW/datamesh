# Improvement Plan: Sachversicherung Datamesh Platform

**Basiert auf:** `review.md` (2026-04-03)
**Struktur:** Jede Massnahme enthaelt Kontext, betroffene Dateien, konkrete Schritte und Akzeptanzkriterien.

---

## Phase 1 -- Hexagonal Isolation reparieren (P0)

> Alle Domain Services verletzen die Hexagonal-Regel: Framework-Annotations und Infrastructure-Imports
> in `domain/service/`. Diese Phase behebt die kritischste Architekturverletzung.

### 1.1 OutboxEvent in die Domain verschieben

**Problem:** `OutboxEvent` lebt in `infrastructure/messaging/outbox/`, wird aber von `domain/port/out/OutboxRepository` referenziert. Das `OutboxRepository`-Interface importiert eine Infrastructure-Klasse.

**Schritte:**

1. Neue Klasse `domain/model/DomainEvent.java` erstellen (pure Java Record):

   ```java
   package ch.yuno.partner.domain.model;

   import java.util.UUID;

   public record DomainEvent(
       UUID id,
       String aggregateType,
       String aggregateId,
       String eventType,
       String topic,
       String payload
   ) {}
   ```

2. `domain/port/out/OutboxRepository.java` aendern: `save(DomainEvent)` statt `save(OutboxEvent)`

3. `infrastructure/persistence/OutboxJpaAdapter` anpassen: `DomainEvent` -> `OutboxEntity` Mapping

4. `infrastructure/messaging/outbox/OutboxEvent.java` entfernen (oder als internes DTO der Persistence behalten)

**Betroffene Dateien (pro Domain):**

- `{domain}/domain/model/DomainEvent.java` (neu)
- `{domain}/domain/port/out/OutboxRepository.java` (aendern)
- `{domain}/infrastructure/persistence/OutboxJpaAdapter.java` (aendern)
- `{domain}/infrastructure/messaging/outbox/OutboxEvent.java` (entfernen)

**Domains:** Partner, Product, Policy, Claims, Billing (5x)

### 1.2 EventPayloadBuilder hinter Port-Interface verstecken

**Problem:** `PersonEventPayloadBuilder`, `PolicyEventPayloadBuilder` etc. sind Infrastructure-Klassen, werden aber direkt in `domain/service/` importiert.

**Schritte:**

1. Neues Port-Interface `domain/port/out/EventPayloadPort.java` erstellen:

   ```java
   package ch.yuno.partner.domain.port.out;

   import ch.yuno.partner.domain.model.Person;
   import ch.yuno.partner.domain.model.DomainEvent;

   public interface EventPayloadPort {
       DomainEvent buildPersonCreated(Person person);
       DomainEvent buildPersonUpdated(Person person);
       DomainEvent buildPersonDeleted(String personId);
       DomainEvent buildPersonState(Person person);
       DomainEvent buildAddressAdded(Person person, String addressId);
       // etc.
   }
   ```

2. `infrastructure/messaging/PersonEventPayloadAdapter.java` erstellen: Implementiert `EventPayloadPort`, delegiert an bestehenden `PersonEventPayloadBuilder`

3. Analog fuer Policy, Product, Claims, Billing

**Betroffene Dateien (pro Domain):**

- `{domain}/domain/port/out/EventPayloadPort.java` (neu)
- `{domain}/infrastructure/messaging/{Domain}EventPayloadAdapter.java` (neu)
- `{domain}/infrastructure/messaging/{Domain}EventPayloadBuilder.java` (bleibt, wird intern genutzt)

### 1.3 Application Layer einfuehren

**Problem:** Die Command/Query Services in `domain/service/` verwenden `@ApplicationScoped`, `@Inject`, `@Transactional`, `@RolesAllowed`. Sie sind de facto Application Services im falschen Package.

**Schritte pro Domain:**

1. Package `application/` erstellen

2. Command Services verschieben und aufteilen:

   **Vorher:** `domain/service/PersonCommandService.java` (mit Framework-Annotations + Outbox-Logik)

   **Nachher:**
   - `domain/service/PersonDomainService.java` -- Pure Business-Logik, kein Framework:

     ```java
     package ch.yuno.partner.domain.service;

     public class PersonDomainService {
         private final PersonRepository personRepository;

         public PersonDomainService(PersonRepository personRepository) {
             this.personRepository = personRepository;
         }

         public Person createPerson(String name, String firstName, Gender gender,
                                    LocalDate dateOfBirth, SocialSecurityNumber ssn) {
             if (ssn != null && personRepository.existsBySocialSecurityNumber(ssn)) {
                 throw new IllegalArgumentException("AHV number already exists");
             }
             return new Person(name, firstName, gender, dateOfBirth, ssn);
         }
     }
     ```

   - `application/CreatePersonUseCase.java` -- Orchestrierung mit Framework:

     ```java
     package ch.yuno.partner.application;

     @ApplicationScoped
     public class CreatePersonUseCase {
         @Inject PersonDomainService domainService;
         @Inject PersonRepository personRepository;
         @Inject OutboxRepository outboxRepository;
         @Inject EventPayloadPort eventPayloadPort;

         @Transactional
         public PersonId execute(CreatePersonCommand command) {
             Person person = domainService.createPerson(...);
             personRepository.save(person);
             outboxRepository.save(eventPayloadPort.buildPersonCreated(person));
             outboxRepository.save(eventPayloadPort.buildPersonState(person));
             return person.getPersonId();
         }
     }
     ```

3. Query Services verschieben (diese sind einfacher, da sie nur Reads machen):

   - `domain/service/PersonQueryService.java` -> `application/PersonQueryService.java`
   - Sie behalten `@ApplicationScoped` und `@Inject`, aber die Domain bleibt sauber

4. REST-Adapter und Kafka-Consumer aktualisieren: Statt `PersonCommandService` jetzt `CreatePersonUseCase` etc. injizieren

**Betroffene Dateien pro Domain:**

| Aktion | Datei |
| --- | --- |
| Neu | `application/CreatePersonUseCase.java` |
| Neu | `application/UpdatePersonUseCase.java` |
| Neu | `application/DeletePersonUseCase.java` |
| Neu | `application/AddAddressUseCase.java` |
| Neu | `application/AssignInsuredNumberUseCase.java` |
| Verschieben | `domain/service/PersonQueryService.java` -> `application/PersonQueryService.java` |
| Aendern | `domain/service/PersonDomainService.java` (pure Java, ohne Framework) |
| Aendern | `infrastructure/web/PersonUiController.java` (neue Imports) |
| Aendern | `infrastructure/web/PersonRestAdapter.java` (neue Imports) |
| Aendern | `infrastructure/messaging/PolicyIssuedConsumer.java` (neue Imports) |
| Loeschen | `domain/service/PersonCommandService.java` (ersetzt durch Use Cases) |

**Komplette Aufstellung aller Domains:**

| Domain | Command Use Cases | Query Service | Betroffene Adapter |
| --- | --- | --- | --- |
| Partner | Create, Update, Delete, AddAddress, UpdateAddress, DeleteAddress, AssignInsuredNumber | PersonQueryService | PersonUiController, PersonRestAdapter, PolicyIssuedConsumer |
| Policy | Create, CreateWithPremium, Activate, Cancel, Update, AddCoverage, RemoveCoverage | PolicyQueryService | PolicyUiController, PolicyRestAdapter |
| Product | Create, Update, Deprecate | ProductQueryService | ProductUiController, ProductRestAdapter |
| Claims | Open, StartReview, Settle, Reject, Update | ClaimQueryService (aus ClaimApplicationService extrahieren) | ClaimUiController, ClaimRestAdapter |
| Billing | CreateInvoice, RecordPayment, InitiateDunning, CancelInvoices, TriggerPayout | InvoiceQueryService | BillingUiController, BillingRestAdapter, PolicyEventConsumer, ClaimsEventConsumer |

### 1.4 Inbound Ports definieren (optional, empfohlen)

**Schritte:**

1. `domain/port/in/` Package erstellen

2. Use-Case-Interfaces definieren:

   ```java
   package ch.yuno.partner.domain.port.in;

   public interface CreatePersonUseCase {
       PersonId execute(CreatePersonCommand command);

       record CreatePersonCommand(String name, String firstName, Gender gender,
                                  LocalDate dateOfBirth, String socialSecurityNumber) {}
   }
   ```

3. `application/CreatePersonUseCase.java` implementiert dieses Interface

4. REST-Adapter und Kafka-Consumer injizieren das Interface, nicht die Implementierung

**Akzeptanzkriterien Phase 1:**

- Kein Import aus `infrastructure` in `domain/` (verifizierbar via ArchUnit oder Grep)
- Keine Framework-Annotations (`@ApplicationScoped`, `@Inject`, `@Transactional`, `@RolesAllowed`) in `domain/`
- Alle bestehenden Tests laufen weiter
- `mvn test` ist gruen fuer alle 5 Domains

---

## Phase 2 -- Schema Consistency (P1)

> Avro ist im Parent POM deklariert, aber Events werden als JSON serialisiert.

### 2.1 Entscheidung: JSON beibehalten

**Empfehlung:** JSON beibehalten und Avro-Dependencies entfernen. Begruendung:

- Alle Events sind bereits JSON
- Debezium StringConverter ist konfiguriert
- Iceberg Sink nutzt JsonConverter
- ODC Contracts definieren JSON-Schemas

**Schritte:**

1. Aus `pom.xml` (Parent) entfernen:

   ```xml
   <!-- Entfernen -->
   <avro.version>1.12.1</avro.version>
   <confluent.version>7.9.2</confluent.version>

   <!-- Entfernen -->
   <dependency>
       <groupId>org.apache.avro</groupId>
       <artifactId>avro</artifactId>
   </dependency>
   <dependency>
       <groupId>io.confluent</groupId>
       <artifactId>kafka-avro-serializer</artifactId>
   </dependency>

   <!-- Entfernen -->
   <repository>
       <id>confluent</id>
       <url>https://packages.confluent.io/maven/</url>
   </repository>

   <!-- Entfernen -->
   <plugin>
       <groupId>org.apache.avro</groupId>
       <artifactId>avro-maven-plugin</artifactId>
   </plugin>
   ```

2. Pruefen ob eine der Module-POMs Avro referenziert -- falls ja, ebenfalls entfernen

3. CLAUDE.md aktualisieren: "Avro" aus API/Schemas entfernen, JSON als Standard dokumentieren

4. Avro-Schema-Dateien in `contracts/schemas/` beibehalten als Referenz, aber klarstellen dass sie nicht fuer Runtime-Serialisierung genutzt werden

**Betroffene Dateien:**

- `pom.xml`
- `CLAUDE.md`

**Akzeptanzkriterien:**

- `mvn clean compile` laeuft ohne Avro-Dependencies
- Schema Registry weiterhin funktionsfaehig (nutzt JSON Schema, nicht Avro)

---

## Phase 3 -- Domain-Owned Data Products (P1)

> SQLMesh, Soda Checks und Iceberg Sink Configs sind zentral in `infra/`.
> Data Mesh fordert: Das Team das die Domain besitzt, besitzt auch das Data Product.

### 3.1 Verzeichnisstruktur pro Domain erweitern

**Ziel-Struktur:**

```text
partner/
  src/main/java/...
  src/main/resources/contracts/...
  data-product/
    sqlmesh/
      silver/partner.sql
      silver/address.sql
      gold/partner_decrypted.sql
    soda/
      checks.yml
    iceberg/
      iceberg-sink-partner.json
```

**Schritte:**

1. Pro Domain `data-product/` Verzeichnis erstellen

2. SQLMesh Models verschieben:

   | Von | Nach |
   | --- | --- |
   | `infra/sqlmesh/models/silver/partner/partner.sql` | `partner/data-product/sqlmesh/silver/partner.sql` |
   | `infra/sqlmesh/models/silver/partner/address.sql` | `partner/data-product/sqlmesh/silver/address.sql` |
   | `infra/sqlmesh/models/gold/partner/partner_decrypted.sql` | `partner/data-product/sqlmesh/gold/partner_decrypted.sql` |
   | (analog fuer alle Domains) | |

3. Soda Checks verschieben:

   | Von | Nach |
   | --- | --- |
   | `infra/soda/checks/partner.yml` | `partner/data-product/soda/checks.yml` |
   | `infra/soda/checks/policy.yml` | `policy/data-product/soda/checks.yml` |
   | (analog fuer alle Domains) | |

4. Iceberg Sink Configs verschieben:

   | Von | Nach |
   | --- | --- |
   | `infra/debezium/iceberg-sink-partner.json` | `partner/data-product/iceberg/iceberg-sink.json` |
   | (analog fuer alle Domains) | |

5. `infra/sqlmesh/config.yaml` anpassen: Model-Pfade auf die neuen Locations setzen oder Symlinks

6. Deploy-Scripts anpassen: Soda und Debezium-Init muessen die neuen Pfade kennen

### 3.2 Cross-Domain Gold Models (analytics)

Die Cross-Domain Models (`analytics.management_kpi`, `analytics.org_hierarchy`) bleiben zentral in `infra/sqlmesh/models/gold/analytics/`, da sie keiner einzelnen Domain gehoeren.

### 3.3 Data Product Manifest

Pro Domain eine `data-product.yaml` erstellen:

```yaml
# partner/data-product/data-product.yaml
name: Partner Data Product
domain: partner
owner: team-partner@yuno.ch
output_ports:
  kafka:
    - person.v1.state
    - person.v1.created
    - person.v1.updated
    - person.v1.deleted
  iceberg:
    - partner_silver.partner
    - partner_silver.address
    - partner_gold.partner_decrypted
quality:
  soda: data-product/soda/checks.yml
sla:
  freshness: 5m
  availability: 99.9%
```

**Akzeptanzkriterien:**

- Jede Domain hat ein `data-product/` Verzeichnis mit SQLMesh, Soda und Iceberg Configs
- `infra/sqlmesh/` enthaelt nur noch `config.yaml`, `audits/` und `models/gold/analytics/`
- `infra/soda/` enthaelt nur noch `configuration.yml` und `Dockerfile`
- Deploy-Scripts funktionieren mit den neuen Pfaden

---

## Phase 4 -- CI/CD Pipeline (P1)

> Nur ein Contract-Check Workflow existiert. Kein Build/Test Workflow.

### 4.1 Build & Test Workflow

Neue Datei `.github/workflows/build.yml`:

```yaml
name: Build & Test

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        module: [partner, product, policy, claims, billing, hr-system, hr-integration]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
      - name: Build & Test ${{ matrix.module }}
        run: mvn -pl ${{ matrix.module }} -am clean verify
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results-${{ matrix.module }}
          path: ${{ matrix.module }}/target/surefire-reports/
```

### 4.2 Integration Test Workflow

Separate Workflow fuer Integration Tests mit Testcontainers:

```yaml
name: Integration Tests

on:
  pull_request:
    branches: [main]

jobs:
  integration:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        module: [partner, policy, product]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
      - name: Integration Test ${{ matrix.module }}
        run: mvn -pl ${{ matrix.module }} -am verify -Pintegration
```

**Betroffene Dateien:**

- `.github/workflows/build.yml` (neu)
- `.github/workflows/integration.yml` (neu)

---

## Phase 5 -- SQLMesh Incremental Models (P2)

> Alle Silver Models sind `kind FULL`. Bei wachsenden Datenmengen wird das zum Performance-Problem.

### 5.1 Silver Models auf Incremental umstellen

**Beispiel `partner_silver.partner`:**

Vorher:

```sql
MODEL (
    name partner_silver.partner,
    kind FULL,
    cron '@hourly'
);
```

Nachher:

```sql
MODEL (
    name partner_silver.partner,
    kind INCREMENTAL_BY_TIME_RANGE (
        time_column updated_at
    ),
    cron '@hourly'
);
```

**Betroffene Dateien:**

- Alle Silver Models (9 Dateien):
  - `partner.sql`, `address.sql`, `policy.sql`, `coverage.sql`, `claim.sql`, `invoice.sql`, `product.sql`, `employee.sql`, `org_unit.sql`

**Akzeptanzkriterien:**

- `sqlmesh plan` zeigt Incremental-Strategie
- Initiales Full-Refresh funktioniert
- Inkrementelle Updates verarbeiten nur neue Events

### 5.2 Gold Layer JOINs korrigieren

`policy_gold.policy_detail`: INNER JOIN durch LEFT JOIN ersetzen:

```sql
-- Vorher
FROM policy_silver.policy p
JOIN partner_silver.partner pa ON p.partner_id = pa.partner_id
JOIN product_silver.product pr ON p.product_id = pr.product_id

-- Nachher
FROM policy_silver.policy p
LEFT JOIN partner_silver.partner pa ON p.partner_id = pa.partner_id
LEFT JOIN product_silver.product pr ON p.product_id = pr.product_id
```

Analog fuer `claims_gold.claim_detail`.

---

## Phase 6 -- Policy/Invoice Number Generation (P2)

> `ThreadLocalRandom` ist nicht kollisionssicher bei Horizontal Scaling.

### 6.1 DB-Sequence fuer PolicyNumber

**Schritte:**

1. Flyway-Migration `V{next}__add_policy_number_sequence.sql`:

   ```sql
   CREATE SEQUENCE policy_number_seq START 1;
   ```

2. `PolicyNumberGenerator` aendern:

   ```java
   public String generatePolicyNumber() {
       long seq = entityManager.createNativeQuery("SELECT nextval('policy_number_seq')")
               .getSingleResult();
       return "POL-%d-%06d".formatted(LocalDate.now().getYear(), seq);
   }
   ```

3. Analog fuer `InvoiceNumber` in Billing

**Betroffene Dateien:**

- `policy/src/main/resources/db/migration/V{n}__add_policy_number_sequence.sql` (neu)
- `policy/infrastructure/persistence/PolicyNumberGenerator.java` (aendern)
- `policy/domain/service/PolicyCommandService.java` (entferne `generatePolicyNumber()` Methode)
- `billing/src/main/resources/db/migration/V{n}__add_invoice_number_sequence.sql` (neu)
- `billing/domain/service/InvoiceCommandService.java` (entferne `generateInvoiceNumber()` Methode)

---

## Phase 7 -- Contract Testing (P2)

> Kein Consumer-Driven Contract Testing fuer Kafka Events.

### 7.1 ODC Contract Verification Test

Pro Domain einen Test, der die tatsaechlichen Event-Payloads gegen die ODC-Contracts validiert:

1. Dependency hinzufuegen (pro Domain POM):

   ```xml
   <dependency>
       <groupId>com.networknt</groupId>
       <artifactId>json-schema-validator</artifactId>
       <version>1.5.6</version>
       <scope>test</scope>
   </dependency>
   ```

2. `DataContractVerificationTest.java` erstellen (pro Domain):

   ```java
   @QuarkusTest
   class DataContractVerificationTest {
       @Test
       void personCreatedEventMatchesContract() {
           String payload = PersonEventPayloadBuilder.buildPersonCreated(...);
           JsonSchema schema = loadSchemaFromContract("person.v1.created.odcontract.yaml");
           assertThat(schema.validate(payload)).isEmpty();
       }
   }
   ```

**Betroffene Dateien (pro Domain):**

- `{domain}/pom.xml` (Dependency)
- `{domain}/src/test/java/.../DataContractVerificationTest.java` (neu)

---

## Phase 8 -- Soda Freshness Checks (P3)

### 8.1 Freshness Checks ergaenzen

Pro Domain-Check-Datei:

```yaml
# partner/data-product/soda/checks.yml
checks for partner_silver.partner:
  - freshness(updated_at) < 2h:
      name: Partner data is fresh (within 2 hours)
```

**Betroffene Dateien:** Alle 5 Soda Check-Dateien

---

## Phase 9 -- Superset Keycloak OIDC (P3)

### 9.1 AUTH_TYPE umstellen

`infra/superset/superset_config.py` aendern:

```python
# Vorher
AUTH_TYPE = 1  # AUTH_DB

# Nachher
from flask_appbuilder.security.manager import AUTH_OAUTH
AUTH_TYPE = AUTH_OAUTH
OAUTH_PROVIDERS = [{
    'name': 'keycloak',
    'icon': 'fa-key',
    'token_key': 'access_token',
    'remote_app': {
        'client_id': 'superset',
        'client_secret': os.environ.get('SUPERSET_OIDC_SECRET', 'secret'),
        'api_base_url': os.environ.get('KEYCLOAK_URL', 'http://keycloak:8080') + '/realms/yuno/protocol/openid-connect/',
        'access_token_url': os.environ.get('KEYCLOAK_URL', 'http://keycloak:8080') + '/realms/yuno/protocol/openid-connect/token',
        'authorize_url': os.environ.get('KEYCLOAK_FRONTEND_URL', 'http://localhost:8280') + '/realms/yuno/protocol/openid-connect/auth',
        'server_metadata_url': os.environ.get('KEYCLOAK_URL', 'http://keycloak:8080') + '/realms/yuno/.well-known/openid-configuration',
        'client_kwargs': {'scope': 'openid email profile'},
    }
}]
```

**Betroffene Dateien:**

- `infra/superset/superset_config.py`
- `infra/keycloak/yuno-realm.json` (neuer Client `superset`)

---

## Uebersicht und Reihenfolge

| Phase | Prioritaet | Aufwand | Abhaengigkeiten |
| --- | --- | --- | --- |
| 1. Hexagonal Isolation | P0 | 2-3 Tage | Keine |
| 2. Schema Consistency | P1 | 0.5 Tage | Keine |
| 3. Domain-Owned Data Products | P1 | 1-2 Tage | Keine |
| 4. CI/CD Pipeline | P1 | 0.5 Tage | Keine |
| 5. SQLMesh Incremental | P2 | 1 Tag | Phase 3 (neue Pfade) |
| 6. Number Generation | P2 | 0.5 Tage | Phase 1 (Application Layer) |
| 7. Contract Testing | P2 | 1 Tag | Phase 1 (EventPayloadPort) |
| 8. Soda Freshness | P3 | 2 Stunden | Phase 3 (neue Pfade) |
| 9. Superset OIDC | P3 | 2 Stunden | Keine |

**Empfohlene Bearbeitungsreihenfolge:** Phase 1 -> 2 -> 4 -> 3 -> 5 -> 6 -> 7 -> 8 -> 9

Phase 1 ist die Grundlage und sollte zuerst umgesetzt werden. Phase 2 und 4 sind unabhaengig und koennen parallel bearbeitet werden. Phase 3 kann danach folgen, gefolgt von den restlichen Phasen.
