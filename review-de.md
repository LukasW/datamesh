# Architektur-Review – CSS Sachversicherung Data Mesh Platform

**Datum:** 2026-03-19
**Reviewer:** Kritischer Software-Architekt
**Umfang:** Vollständiger Codebase-Review (partner, product, policy, infra)
**Methodik:** Data Mesh, Clean Architecture, Clean Code, DDD (Strategisch + Taktisch)

---

## Zusammenfassung

Das Projekt zeigt ein solides konzeptionelles Fundament: Die hexagonale Architektur ist durchgängig angewandt, die Bounded Contexts sind klar abgegrenzt, und die Data-Mesh-Governance-Schicht (ODC, DataHub, Schema Registry) geht weiter als die meisten Projekte. Allerdings unterlaufen mehrere **kritische Inkonsistenzen** die Zuverlässigkeitsgarantien der Architektur, und einige taktische DDD- sowie Clean-Code-Verstösse schwächen das Domänenmodell.

Die nachfolgenden Befunde sind nach Schweregrad geordnet. Die **kritischen** Punkte müssen vor dem Go-live behoben werden. Die übrigen sind technische Schulden, die mit der Zeit immer teurer werden.

---

## 1. Kritische Probleme

### 1.1 Inkonsistente Kafka-Publishing-Strategie – Kein Outbox im Policy-Service

**Problem:** Partner und Product verwenden das Transactional-Outbox-Muster mit Debezium CDC (korrekt). Der Policy-Service publiziert Events **direkt** an Kafka aus dem Application Service heraus – ohne Outbox-Tabelle. Das ist ein lehrbuchmässiges **Dual-Write-Antipattern**.

```
// PolicyApplicationService → PolicyKafkaAdapter → Emitter<GenericRecord>
// DB-Commit und Kafka-Send sind NICHT in derselben Transaktion
```

Ist der Kafka-Broker nicht erreichbar, oder stürzt die JVM nach dem DB-Commit aber vor dem abgeschlossenen Kafka-Send ab, geht das Event **lautlos verloren**. Es gibt keine Kompensation, kein Retry und keine Idempotenzgarantie.

**Auswirkung:** Verlorene Policy-Events zerstören die `PartnerView`/`ProductView`-Read-Models bei jedem Downstream-Consumer, der später startet. ADR-002 (At-least-once-Delivery) wird verletzt.

**Lösung:** Dasselbe Outbox-+Debezium-Muster anwenden wie bei Partner und Product. Alternativ: persistenter Retry-Mechanismus mit einer separaten `policy_outbox`-Tabelle und Publikation des Avro-Records über Debezium mit dem Avro-Converter (statt dem String-Converter).

---

### 1.2 ODC-Contracts deklarieren `format: AVRO`, aber Events werden als JSON publiziert

**Problem:** Die ODC-Contracts für Partner und Product deklarieren:

```yaml
spec:
  format: AVRO
  schemaRegistry: http://schema-registry:8081
```

In Wirklichkeit ist Debezium mit `"value.converter": "org.apache.kafka.connect.storage.StringConverter"` konfiguriert. Das tatsächliche Wire-Format ist ein **einfacher JSON-String**, kein Avro. Die Schema Registry wird für Partner- und Product-Events **gar nicht** verwendet.

**Auswirkung:** Downstream-Consumer, die den ODC-Contract lesen und Avro-Deserialisierung erwarten, schlagen fehl. Der Governance-Checker (`lint-contracts.py`) validiert einen Contract, der nicht der Realität entspricht. Das invalidiert das gesamte Contract-First-Governance-Versprechen.

**Lösung:** Entweder:
- Debezium auf `io.confluent.connect.avro.AvroConverter` mit Schema Registry umstellen (bevorzugt – konsistent mit Policy), oder
- Die ODC-Contracts auf `format: JSON` korrigieren und die `schemaRegistry`-Referenz entfernen.

Die Inkonsistenz zwischen Contract und Wire-Format ist gefährlicher als jede der beiden Optionen für sich.

---

### 1.3 Avro-Schemas sind inline in Java-Code definiert (keine `.avsc`-Dateien)

**Problem:** Der Policy-Service baut alle Avro-Schemas inline mit `SchemaBuilder`:

```java
private static final Schema POLICY_ISSUED_SCHEMA = SchemaBuilder.record("PolicyIssued")
    .namespace("ch.css.policy.events")
    .fields()
    .requiredString("eventId")
    // ...
    .endRecord();
```

Es gibt keine `.avsc`-Schema-Dateien. Die Schema Registry erhält Schemas, die zur Laufzeit aus Java-Code generiert werden, der nicht unter Schema-Versionskontrolle steht.

**Auswirkung:**
- Schema-Evolution kann in einem Pull Request nicht als Schema-Diff reviewt werden
- Der ODC-Contract kann nicht auf eine stabile Schema-Datei verweisen
- Breaking Changes sind bis zur Laufzeit unsichtbar
- Schema-Rückwärtskompatibilität wird zum Laufzeit-Überraschungspaket, nicht zur Build-Zeit-Prüfung

**Lösung:** Schemas als `.avsc`-Dateien in `src/main/avro/` definieren. Das Avro-Maven-Plugin zur Code-Generierung nutzen. Die Schema-Datei im ODC-Contract referenzieren. Schemas in CI/CD vor dem Deployment registrieren.

---

### 1.4 Datenbank-Spaltennamen verletzen ADR-005 (deutsche Bezeichner im Code)

**Problem:** Flyway-Migrationen enthalten deutsche Datenbank-Spaltennamen über alle Services:

| Service | Deutsche Spaltennamen |
|---------|----------------------|
| Partner | `vorname`, `geburtsdatum`, `ahv_nummer`, `adress_typ`, `strasse`, `hausnummer`, `plz`, `gueltig_von`, `gueltig_bis` |
| Policy | `policy_nummer`, `produkt_id`, `versicherungsbeginn`, `versicherungsende`, `praemie`, `selbstbehalt`, `deckung`, `deckungstyp`, `versicherungssumme` |

ADR-005 besagt klar: **«DB-Spalten → Englisch»**. Dies sind Code-Artefakte, keine UI-Strings. Die JPA-Entity-Mappings (`@Column(name = "vorname")`) propagieren diese Verletzung in die Java-Infrastrukturschicht.

**Auswirkung:** Die Migrationshistorie (V6 für Partner, V4 für Policy) zeigt, dass Enum-Werte bereits einmal von Deutsch auf Englisch umbenannt wurden. Die Migration von Spaltennamen ist invasiver und erfordert sorgfältige Flyway-Migrationen – aber so zu belassen bedeutet eine dauerhafte Verletzung des eigenen ADRs.

**Lösung:** Flyway-Migrationen hinzufügen, die alle deutschen Spaltennamen in englische umbenennen. Beispiel:
```sql
-- V8__Rename_German_Columns_To_English.sql
ALTER TABLE person RENAME COLUMN vorname TO first_name;
ALTER TABLE person RENAME COLUMN geburtsdatum TO date_of_birth;
-- ...
```

---

### 1.5 REST-API-Pfade sind auf Deutsch – ADR-005-Verletzung

**Problem:** REST-Pfade sind technische Bezeichner, keine UI-Strings. Sie sind Code-Artefakte und müssen ADR-005 (Englisch) folgen:

```
/api/personen      → sollte /api/persons sein
/api/policen       → sollte /api/policies sein
/api/produkte      → sollte /api/products sein
/api/personen/{id}/adressen  → sollte /api/persons/{id}/addresses sein
/api/policen/{id}/deckungen  → sollte /api/policies/{id}/coverages sein
/api/policen/{id}/aktivieren → sollte /api/policies/{id}/activate sein
/api/policen/{id}/kuendigen  → sollte /api/policies/{id}/cancel sein
```

**Auswirkung:** Externe Consumer (Claims-Service, Broker-API, Integrationstests) koppeln sich an deutsche URL-Pfade. Jede zukünftige Migration wird ein Breaking API Change.

**Lösung:** Alle REST-Pfade auf Englisch umbenennen. Deutsche Labels nur in den Qute-Templates exponieren (was bereits korrekt ist).

---

### 1.6 Keine Integrationstests vorhanden

**Problem:** Der Architekturplan erwähnt Integrationstests mit `@QuarkusIntegrationTest` und Testcontainers. Diese existieren nicht. Die Testabdeckung besteht ausschliesslich aus Unit-Tests mit gemockten Repositories.

**Auswirkung:** Folgendes ist end-to-end ungetestet:
- Korrektheit der Flyway-Migrationen
- JPA-Entity-Mappings (einschliesslich der deutschen Spaltennamen)
- Kafka-Consumer-Verhalten bei doppelter Zustellung
- Debezium-Outbox → Kafka-Roundtrip
- Avro-Serialisierung/Deserialisierung gegen die Schema Registry
- Read-Model-Materialisierung der Policy aus Partner-/Product-Events

Das System kann ohne Integrationstests nicht als produktionsreif bezeichnet werden.

**Lösung:** In jedem Service `src/test/integration/` anlegen und mindestens implementieren:
- Einen Flyway-Migration-Smoke-Test pro Service
- Einen Roundtrip-Test: Domain-Write → Outbox → Kafka-Consumer liest → Read-Model aktualisiert
- Einen Aggregate-State-Machine-Test durch den vollständigen Stack

---

### 1.7 Keine Dead-Letter-Queue-(DLQ)-Strategie

**Problem:** Die Kafka-Consumer (`PartnerEventConsumer`, `ProductEventConsumer`) haben keine Fehlerbehandlung für Verarbeitungsfehler (Poison Pills, Schema-Mismatch, NullPointer). SmallRye Reactive Messaging stoppt den Consumer-Channel bei unbehandelten Exceptions.

**Auswirkung:** Ein einziges fehlerhaftes Event unterbricht die Fähigkeit des Policy-Service dauerhaft, Read-Models aus Partner-/Product-Events zu materialisieren – bis der Service neu gestartet und die Poison Pill manuell übersprungen wird.

**Lösung:** `@Blocking` + Fault-Tolerance-Behandlung hinzufügen. Pro Consumer ein DLQ-Topic konfigurieren:
```properties
mp.messaging.incoming.partner-person-created.failure-strategy=dead-letter-queue
mp.messaging.incoming.partner-person-created.dead-letter-queue.topic=partner-person-created-dlq
```

---

## 2. Architekturprobleme

### 2.1 Command/Query-Trennung fehlt in den Application Services

**Problem:** `PolicyApplicationService` vermischt Command-Methoden (Mutationen) mit Query-Methoden:

```java
// Commands – korrekt
activatePolicy(), cancelPolicy(), addCoverage()

// Queries fälschlicherweise eingemischt
searchPolicies()
getPartnerViewsMap()
searchPartnerViews(nameQuery)
getActiveProducts()
getProductViewsMap()
```

Das verletzt das Single-Responsibility-Prinzip und macht es unmöglich, Lese- und Schreib-Workloads unabhängig zu skalieren. Dasselbe gilt für `PersonApplicationService` und `ProductApplicationService`.

**Lösung:** Aufteilen in `PolicyCommandService` (Mutationen + Event-Publikation) und `PolicyQueryService` (read-only, kann Ports bei Bedarf umgehen). Das ist der erste Schritt Richtung CQRS, falls das System wächst.

---

### 2.2 Read-Model-Bootstrapping nicht definiert

**Problem:** Die `PartnerView`- und `ProductView`-Tabellen in der Policy-Datenbank werden aus Kafka-Events befüllt. Wird der Policy-Service neu deployed (oder seine DB zurückgesetzt), muss er alle historischen Partner-/Product-Events aus Kafka replayed, um diese Views neu aufzubauen.

- Die Standard-Kafka-Retention garantiert nicht, dass alle Events noch verfügbar sind
- Das compacted Topic `person.v1.state` existiert (korrekt!), aber kein Consumer verwendet es für das Bootstrapping
- Die `product.v1.*`-Events haben offenbar kein compacted State-Topic

**Auswirkung:** Ein frisch deployeter Policy-Service mit leerer DB und einem Kafka-Cluster, dessen frühe Messages gelöscht wurden, hat eine unvollständige `PartnerView` (keine historischen Partner) und produziert lautlos falsche Ergebnisse.

**Lösung:**
- Ein `product.v1.state` compacted Topic neben den bestehenden Product-Event-Topics hinzufügen
- Im Policy-Service ein Startup-Bootstrapping implementieren: zuerst die compacted State-Topics lesen, dann auf die Event-Topics wechseln (mit Consumer-Group-Offsets)
- Das Bootstrapping-Verfahren in der `business_spec.md` des Services dokumentieren

---

### 2.3 Platform-DB ist eine gemeinsam genutzte Datenbank – Data-Mesh-Antipattern

**Problem:** Die `platform-db` ist eine einzelne PostgreSQL-Instanz, die von allen Analytics-Consumern gemeinsam genutzt wird. Alle Domain-Events landen in derselben Datenbank, verwaltet von einem einzigen Python-Consumer-Prozess und einem einzigen dbt-Projekt.

In Data Mesh sollte die Analytics-Schicht aus **autonomen Datenprodukten** bestehen, jedes mit eigenem Speicher, eigenem SLA und eigenem Ownership. Eine gemeinsam genutzte zentrale Datenbank ist das Analytics-Äquivalent der gemeinsamen operativen Datenbank, die Data Mesh ersetzen sollte.

**Auswirkung:**
- Das Plattform-Team wird zum Flaschenhals für jede Domain-Analytics
- Schema-Änderungen in Events einer Domain können die gemeinsamen dbt-Modelle brechen
- Keine Domain kann Analytics-Änderungen unabhängig deployen

**Lösung:** Schrittweise Entwicklung hin zu domain-eigenen analytischen Datenprodukten:
1. Als Zwischenschritt schreibt jeder Domain-Service seine Events in domain-spezifische Schemas innerhalb der platform-db
2. Langfristig: Self-Serve-Datenplattform bereitstellen (z.B. separate Schemas pro Domain oder separate Datenbanken über Data-Lake-Federation)
3. Separate dbt-Projekte pro Domain, orchestriert durch Airflow, aber unabhängig deploybar

---

### 2.4 Keycloak fehlt in docker-compose

**Problem:** CLAUDE.md listet Keycloak als Kernkomponente mit den Rollen `UNDERWRITER`, `CLAIMS_AGENT`, `BROKER`, `ADMIN`. ADR-003 und die Architektur erfordern Keycloak für alle Authentifizierung. In `docker-compose.yaml` ist kein Keycloak-Service vorhanden.

**Auswirkung:**
- `@RolesAllowed`-Annotationen (in CLAUDE.md erwähnt) können nicht getestet werden
- Die Plattform hat in der Entwicklungsumgebung keine Authentifizierung
- Sicherheitstests sind lokal nicht möglich

**Lösung:** Einen Keycloak-Service in `docker-compose.yaml` hinzufügen mit vorkonfiguriertem Realm (`css`), Clients für jeden Service und den vier Rollen. Einen Init-Job hinzufügen, der das Realm-JSON importiert.

---

### 2.5 Einzelner Kafka-Broker – Keine Fehlertoleranz

**Problem:** Der Kafka-Cluster ist ein einzelner Broker (KRaft-Modus, Single-Node). Alle Topics haben implizit `replication.factor=1`.

**Auswirkung:** Jeder Kafka-Neustart führt zu einer vollständigen Unterbrechung der Message-Zustellung. Bei einem Plattenausfall des Brokers sind Datenverluste möglich. Das ist für ein System mit 7-jähriger Retention-Anforderung (ADR) nicht akzeptabel.

**Lösung:** Für die lokale Entwicklung ist ein einzelner Broker akzeptabel. Die docker-compose-Konfiguration sollte jedoch dokumentieren, dass Produktion mindestens einen 3-Broker-Cluster erfordert. Umgebungsspezifische Compose-Dateien (`docker-compose.prod.yaml`) hinzufügen, die die Produktionstopologie widerspiegeln.

---

### 2.6 dbt läuft als One-Shot beim Start – Falscher Lifecycle

**Problem:** `dbt` ist ein Docker-Service, der beim Container-Start einmalig läuft und dann beendet wird. Das bedeutet, Transformationen laufen nur zum Deployment-Zeitpunkt, nicht planmässig.

**Auswirkung:** Die Analytics-Tabellen der `platform-db` enthalten zwischen Deployments veraltete Daten. Jede Datenpipeline, die eine Frische von unter 24 Stunden erfordert, kann mit diesem Ansatz nicht erfüllt werden.

**Lösung:** `dbt` als eigenständigen Service aus `docker-compose.yaml` entfernen. dbt-Läufe ausschliesslich aus Airflow-DAGs heraus triggern (`DbtOperator` oder `BashOperator`). Das ist die korrekte Architekturtrennung: Airflow orchestriert, dbt transformiert.

---

## 3. Taktische DDD-Probleme

### 3.1 `OutboxEvent` ist ein Infrastruktur-Concern in der Domänenschicht

**Problem:** `OutboxEvent` liegt in `domain/model/` neben echten Domänenobjekten (`Person`, `Policy`, `Product`). `OutboxEvent` ist jedoch ein technisches Infrastruktur-Artefakt zur Erreichung von At-least-once-Delivery – es hat keine Geschäftsbedeutung.

```
domain/model/
├── Person.java               ← Domänenobjekt ✓
├── Address.java              ← Domänenobjekt ✓
├── SocialSecurityNumber.java ← Value Object ✓
└── OutboxEvent.java          ← Infrastruktur-Concern ✗
```

**Lösung:** `OutboxEvent` nach `infrastructure/messaging/outbox/` oder `infrastructure/persistence/outbox/` verschieben. Die Domänenschicht sollte nichts von Outbox-Mechanismen wissen.

---

### 3.2 `PersonEventPayloadBuilder` koppelt die Domäne an JSON-Serialisierung

**Problem:** `PersonEventPayloadBuilder` (in `domain/service/` abgelegt) baut JSON-Event-Payloads und kennt Topic-Namen wie `"person.v1.created"`. Damit werden Serialisierungs-Concerns (Jackson/JSON) und Infrastruktur-Routing-Concerns (Topic-Namen) in die Domänenschicht eingebracht.

**Lösung:** `PersonEventPayloadBuilder` nach `infrastructure/messaging/` verschieben. Der Domänen-Service sollte ein stark typisiertes Domänen-Event emittieren (z.B. `PersonCreatedEvent`), und die Infrastrukturschicht übersetzt es in ein Wire-Format.

---

### 3.3 Aggregate-Identitätsbehandlung ist inkonsistent

**Problem:** `Policy` verwendet `String` für `partnerId` und `productId` (Cross-Aggregate-Referenzen), was DDD-Praxis entspricht. Jedoch verwendet `Person` `UUID` für die eigene Identität, während in Policy `String` für alle domänenübergreifenden Referenzen genutzt wird. Es gibt keine typsicheren Wrapper (z.B. `PartnerId`, `ProductId`), um versehentliche ID-Verwechslungen zu verhindern.

**Lösung:** Typisierte ID-Value-Objects einführen:
```java
public record PartnerId(UUID value) {}
public record ProductId(UUID value) {}
public record PolicyId(UUID value) {}
```

Das verhindert Bugs wie `new Policy(productId, partnerId, ...)`, bei denen Argumente versehentlich vertauscht werden.

---

### 3.4 Policy-Nummern-Generierungsstrategie ist undefiniert

**Problem:** Das Policy-Nummern-Format `POL-YYYY-NNNN` ist dokumentiert, aber die Generierungsstrategie ist nicht sichtbar. Basiert sie auf einer Datenbanksequenz, können bei Last Lücken oder Kollisionen entstehen. Wird sie applikationsseitig generiert, gibt es eine Race Condition.

**Lösung:** Explizit dokumentieren und implementieren. Eine Datenbanksequenz mit jährlichem Reset ist angemessen. Beispiel:
```sql
CREATE SEQUENCE policy_number_seq START 1 INCREMENT 1;
```
Eingekapselt in einem Domänen-Service, der das Ergebnis formatiert.

---

### 3.5 Keine Domain Events als erstklassige Objekte

**Problem:** Das Domänenmodell definiert keine Domain Events (z.B. `PolicyIssued`, `PersonCreated`) als explizite Java-Typen. Stattdessen werden String-Literale wie `"PolicyIssued"`, `"PersonCreated"` als Event-Typ-Bezeichner verwendet, und die Payload-Konstruktion wird an Builder-Utilities delegiert.

**Auswirkung:** Event-Typen sind per IDE-Tooling nicht auffindbar, können nicht unabhängig getestet werden und haben keine Compile-Zeit-Sicherheit.

**Lösung:** Domain Events als Value Objects oder Records definieren:
```java
public record PolicyIssuedEvent(
    PolicyId policyId,
    String policyNumber,
    PartnerId partnerId,
    LocalDate coverageStartDate,
    BigDecimal premium,
    Instant occurredAt
) {}
```

Der Application Service emittiert diese; die Infrastrukturschicht serialisiert sie nach JSON oder Avro.

---

## 4. Clean-Code-Probleme

### 4.1 Docker-Image-Namen sind persönlich – Nicht projekt-scoped

**Problem:** Container-Images sind mit einem persönlichen Registry-Präfix benannt:
```yaml
image: lukasweibel/person-service:latest
image: lukasweibel/product-service:latest
image: lukasweibel/policy-service:latest
```

**Lösung:** Einen organisationsspezifischen Registry-Pfad verwenden:
```yaml
image: css/partner-service:latest
image: css/product-service:latest
image: css/policy-service:latest
```

Die Registry-URL über `.env` parametrisieren für CI/CD-Flexibilität.

---

### 4.2 `latest`-Tag in der Compose-Datei ist gefährlich

**Problem:** Alle Domain-Services referenzieren den `latest`-Tag. In einer Compose-Datei, die auch für Staging oder Produktion genutzt werden könnte, verhindert `latest` reproduzierbare Deployments.

**Lösung:** Explizite Versions-Tags verwenden (z.B. `1.0.0` oder einen Git-SHA). `latest` ist nur in rein dev-spezifischen Compose-Konfigurationen akzeptabel, wenn dies klar gekennzeichnet ist.

---

### 4.3 Such-Endpunkte haben keine Paginierung

**Problem:** Alle Suchmethoden (`searchPersons`, `listAllProducts`, `searchPolicies`) geben `List<T>` ohne Paginierung zurück. In Produktion:
- `listAllProducts()` gibt alle Produkte zurück
- `searchPersons()` könnte Tausende von Ergebnissen liefern

**Lösung:** `PageRequest`/`PageResult<T>`-Wrapper hinzufügen. `?page=0&size=20`-Query-Parameter an allen Collection-Endpunkten exponieren.

---

### 4.4 Maven-Compiler-Ziel ist Java 25 (noch nicht veröffentlicht)

**Problem:** Das Parent-`pom.xml` setzt den Compiler auf Java 25. Stand Anfang 2026 ist Java 25 kein GA-Release. Java 21 ist das aktuelle LTS.

**Lösung:** `<java.version>21</java.version>` im Parent-POM setzen. Virtual Threads über `quarkus.virtual-threads.enabled=true` nutzen (Quarkus behandelt dies transparent mit Java 21).

---

### 4.5 Keine Observability-Infrastruktur

**Problem:** Es gibt keine Prometheus-, Grafana- oder OpenTelemetry-Konfiguration. Quarkus stellt Micrometer-Metriken standardmässig bereit. Das aktuelle Setup verlässt sich nur auf AKHQ für Kafka-Monitoring.

**Lösung:** Zu `docker-compose.yaml` hinzufügen:
- Prometheus (scraped Quarkus-`/q/metrics`-Endpunkte)
- Grafana (vorgefertigte Dashboards für Kafka-Consumer-Lag, JVM-Metriken, DB-Connection-Pool)

Zu jedem Service-`pom.xml` hinzufügen:
```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
```

---

### 4.6 Keine `.env`-Validierung oder Startup-Fail-Fast

**Problem:** Umgebungsvariablen wie `KAFKA_BOOTSTRAP_SERVERS`, `DATABASE_PASSWORD`, `SCHEMA_REGISTRY_URL` verwenden Standard-Fallbacks (`${VAR:default}`). In Produktion fällt eine falsch konfigurierte Umgebung (leere `SCHEMA_REGISTRY_URL`) lautlos auf `http://localhost:8081` zurück und schlägt erst zur Laufzeit fehl, nicht beim Start.

**Lösung:** Für Produktions-Profile `${VAR}` (ohne Default) verwenden, damit Quarkus beim Start mit einer klaren Fehlermeldung abbricht, wenn eine erforderliche Variable fehlt. Defaults nur für das Dev-Profil vorbehalten.

---

## 5. Data-Mesh-Prinzipien

### 5.1 Auffindbarkeit von Datenprodukten ist unvollständig

**Problem:** DataHub ist für Metadaten integriert, und das Data Product Portal existiert. Jedoch:
- Das Portal liest ODC-Contracts, die in `src/main/resources/` der Services gespeichert sind (interne Classpath-Ressourcen), zur Laufzeit als Docker-Volumes eingebunden
- Es gibt keinen CI/CD-Schritt, der Contracts bei einem Merge in eine zentrale Contract-Registry publiziert
- ODC-Versionen sind nicht an Service-Versionen gekoppelt

**Lösung:** ODC-Contracts als unabhängig versionierte Artefakte behandeln:
1. Contracts bei einem Merge in ein dediziertes Git-Repository oder eine Artefakt-Registry publizieren
2. Einen CI-Schritt hinzufügen, der Schema-Rückwärtskompatibilität vor dem Merge prüft (nicht nur beim Start)
3. Contract-Version neben Service-Version in DataHub anzeigen

---

### 5.2 Kein Self-Serve-Datenzugriff für Downstream-Consumer

**Problem:** Data Mesh erfordert, dass Domain-Teams Datenprodukte unabhängig entdecken und nutzen können, ohne Koordination. Aktuell:
- Consumer müssen Kafka-Broker-Adressen und Consumer-Group-IDs manuell kennen
- Es gibt keine Service-Account-Provisionierung für neue Consumer
- DataHub zeigt Metadaten, stellt aber keine Zugriffsprovisionierung bereit

**Lösung:** Einen `dataProduct`-Abschnitt in jedem ODC-Contract definieren, der Zugriffsmuster spezifiziert:
```yaml
dataProduct:
  accessPatterns:
    - type: kafka-consumer
      topic: person.v1.created
      sampleConsumerConfig: contracts/samples/person-consumer.properties
```

---

### 5.3 Bootstrap-Prozess für compacted Topic `person.v1.state` nicht dokumentiert

**Problem:** Das compacted State-Topic `person.v1.state` existiert (im ODC dokumentiert), aber die Consumer-Strategie ist nicht definiert. Unklar ist:
- Liest ein neuer Service zuerst den State und wechselt dann auf die Event-Topics?
- Wie bootstrapped der Policy-Service `PartnerView` aus diesem Topic?
- Was sind die Reihenfolgegarantien zwischen dem State-Topic und den Event-Topics?

**Lösung:** Das Bootstrap-Protokoll in `partner/specs/business_spec.md` dokumentieren:
1. Neuer Consumer liest `person.v1.state` ab `earliest` bis er aufgeholt hat
2. Consumer wechselt auf die `person.v1.created`/`person.v1.updated`-Event-Topics
3. Der Offset-Wasserstand aus Schritt 1 wird genutzt, um in Schritt 2 bereits angewandte Events zu überspringen

---

## 6. Strategische DDD-Probleme

### 6.1 Claims-Domain fehlt – Architekturentscheidungen können nicht validiert werden

**Problem:** Claims ist als Core Domain gelistet, und ADR-003 definiert einen spezifischen synchronen REST-Call (Claims → Policy für Coverage-Check). Ohne den Claims-Service kann dieser ADR nicht validiert werden. Der `SmallRye Fault Tolerance`-Circuit-Breaker (in CLAUDE.md gefordert) ist ungetestet.

**Lösung:** Einen minimalen Claims-Bounded-Context-Stub erstellen:
- `claims/specs/business_spec.md` definieren
- Das `claims/`-Modul zum Maven-Parent-POM hinzufügen
- `CoverageCheckPort` (REST-Client zum Policy-Service) mit `@CircuitBreaker` implementieren
- Mindestens `claims.v1.opened`- und `claims.v1.settled`-Kafka-Topics mit ODC-Contracts definieren

Ohne dies fehlt einer der vier deklarierten Bounded Contexts in der Codebase.

---

### 6.2 Kein Anti-Corruption Layer zwischen Policy und Partner-/Product-Events

**Problem:** Der `PartnerEventConsumer` bildet Debezium-JSON-Payloads direkt per String-Field-Parsing (Jackson) auf `PartnerView` ab. Ändert sich das Partner-Event-Schema (selbst rückwärtskompatibel), bricht der Consumer lautlos oder ignoriert lautlos neue Felder.

**Lösung:** Einen expliziten Anti-Corruption Layer (ACL) in der Policy-Infrastruktur einführen:
```java
// infrastructure/messaging/acl/PartnerEventTranslator.java
public class PartnerEventTranslator {
    public PartnerView translate(PersonCreatedPayload payload) { ... }
}
```

Das isoliert den Übersetzungs-Concern, macht ihn testbar und markiert klar die Grenze, wo die Partner-Domain-Sprache in die Policy-Domain-Sprache übersetzt wird.

---

### 6.3 `billing`- und `sales`-Domains sind nicht einmal als Stub vorhanden

**Problem:** CLAUDE.md erwähnt Billing & Collection sowie Sales & Distribution als geplante Domains. Keine von beiden hat einen Stub, eine Spec oder einen ODC-Contract.

**Lösung:** Auch wenn nicht implementiert, folgendes hinzufügen:
- `billing/specs/business_spec.md` und `sales/specs/business_spec.md` mit Platzhalter-Specs
- Einträge in `specs/arc42.md` mit Begründung, warum sie zurückgestellt wurden
- Kafka-Topic-Namen in der Namenskonvention reservieren (z.B. `billing.v1.*`, `sales.v1.*`)

Das verhindert, dass andere Domains versehentlich Namen belegen, die von zukünftigen Domains verwendet werden.

---

## 7. Übersichtstabelle

| # | Befund | Schweregrad | Aufwand | Priorität |
|---|--------|-------------|---------|-----------|
| 1.1 | Kein Outbox im Policy-Service – Dual-Write-Risiko | Kritisch | Mittel | P0 |
| 1.2 | ODC behauptet Avro, Wire-Format ist JSON | Kritisch | Mittel | P0 |
| 1.3 | Avro-Schemas inline in Java-Code | Kritisch | Mittel | P0 |
| 1.4 | Deutsche Spaltennamen verletzen ADR-005 | Kritisch | Mittel | P0 |
| 1.5 | Deutsche REST-Pfade verletzen ADR-005 | Kritisch | Gering | P0 |
| 1.6 | Keine Integrationstests vorhanden | Kritisch | Hoch | P0 |
| 1.7 | Keine Dead-Letter-Queue-Strategie | Kritisch | Gering | P1 |
| 2.1 | Command/Query nicht getrennt | Architektur | Mittel | P1 |
| 2.2 | Read-Model-Bootstrapping undefiniert | Architektur | Mittel | P1 |
| 2.3 | Platform-DB ist gemeinsame DB – Antipattern | Architektur | Hoch | P2 |
| 2.4 | Keycloak fehlt in docker-compose | Architektur | Gering | P1 |
| 2.5 | Einzelner Kafka-Broker – keine Fehlertoleranz | Architektur | Gering | P1 |
| 2.6 | dbt läuft beim Start (falscher Lifecycle) | Architektur | Gering | P1 |
| 3.1 | OutboxEvent in der Domänenschicht | DDD Taktisch | Gering | P2 |
| 3.2 | EventPayloadBuilder koppelt Domäne an JSON | DDD Taktisch | Gering | P2 |
| 3.3 | Keine typisierten ID-Value-Objects | DDD Taktisch | Gering | P2 |
| 3.4 | Policy-Nummern-Generierung undefiniert | DDD Taktisch | Gering | P2 |
| 3.5 | Keine Domain Events als erstklassige Typen | DDD Taktisch | Mittel | P2 |
| 4.1 | Persönliche Docker-Image-Namen | Clean Code | Gering | P2 |
| 4.2 | `latest`-Tag in Compose | Clean Code | Gering | P2 |
| 4.3 | Keine Paginierung bei Such-Endpunkten | Clean Code | Gering | P1 |
| 4.4 | Java-25-Compiler (nicht veröffentlicht) | Clean Code | Gering | P1 |
| 4.5 | Keine Observability (Metriken/Tracing) | Clean Code | Mittel | P2 |
| 4.6 | Kein Env-Var-Fail-Fast in Produktion | Clean Code | Gering | P1 |
| 5.1 | Auffindbarkeit von Datenprodukten unvollständig | Data Mesh | Mittel | P2 |
| 5.2 | Kein Self-Serve-Datenzugriff | Data Mesh | Hoch | P3 |
| 5.3 | State-Topic-Bootstrap-Prozess undokumentiert | Data Mesh | Gering | P2 |
| 6.1 | Claims-Domain fehlt | Strategisch DDD | Hoch | P1 |
| 6.2 | Kein ACL zwischen Policy und Partner/Product | Strategisch DDD | Gering | P2 |
| 6.3 | Billing/Sales-Domains nicht einmal als Stub | Strategisch DDD | Gering | P3 |

---

## 8. Was gut gelöst ist

Zur ausgewogenen Darstellung – folgendes ist genuinen well-executed und sollte bewahrt werden:

- **Hexagonale Architektur** ist durchgängig angewandt. Die Domänenschicht hat in allen drei Services null Framework-Abhängigkeiten.
- **Transactional Outbox via Debezium** bei Partner und Product ist eine korrekte, kampferprobte Implementierung von At-least-once-Delivery.
- **AHV-Nummer-EAN-13-Validierung** im Domänenmodell ist ein exzellentes Beispiel für Value-Object-Invarianten-Durchsetzung.
- **Temporales Adressmanagement** (automatisches Abschneiden überlappender Adressen) im Person-Aggregat ist ausgefeilt und korrekt.
- **ODC-Contracts mit SodaCL-Qualitätsprüfungen und SLA** gehen weiter als die meisten Data-Mesh-Implementierungen in der Praxis.
- **Compacted `person.v1.state`-Topic** zeigt Bewusstsein für das Consumer-Bootstrapping-Problem (auch wenn die Lösung noch nicht vollständig implementiert ist).
- **KRaft-Kafka** (ohne ZooKeeper) ist die korrekte moderne Wahl.
- **Flyway + Hibernate Envers** Kombination liefert sowohl Schema-Versionierung als auch vollständige Audit-Historie.
- **Policy-Read-Models** (`PartnerView`, `ProductView`) implementieren domänenübergreifendes Daten-Sharing korrekt, ohne Datensouveränität zu verletzen.
- **DataHub-Integration** für Metadaten-Lineage ist ein überdurchschnittliches Investment in Governance.
- **ADR-005-Sprachpolitik** ist eine wohlbegründete, klar dokumentierte Entscheidung (auch wenn sie im DB-Schema und den REST-Pfaden noch nicht vollständig umgesetzt ist).

---

*Dieser Bericht wurde durch statische Code-Analyse und Architektur-Review erstellt. Er ersetzt kein Runtime-Profiling, kein Security-Testing und kein Lasttesting.*
