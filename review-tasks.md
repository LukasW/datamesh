# Umsetzungsplan – Architektur-Review CSS Sachversicherung Data Mesh

> Basierend auf [review-de.md](review-de.md)
> Erstellt: 2026-03-19

**Legende:** P0 = vor Go-live zwingend · P1 = nächster Sprint · P2 = mittelfristig · P3 = Backlog

---

## Phase 1 – Kritische Korrekturen (P0)

> Diese Tasks blockieren den Go-live. Keine dieser Items darf offen bleiben.

---

### TASK-01 · Outbox-Pattern im Policy-Service implementieren

**Review-Referenz:** 1.1
**Priorität:** P0 · **Aufwand:** M

**Ziel:** Dual-Write-Risiko beseitigen. Der Policy-Service muss Events transaktional über eine Outbox-Tabelle publizieren, identisch zum Partner- und Product-Service.

**Schritte:**

- [x] Flyway-Migration `V5__Create_Outbox_Table.sql` in [policy/src/main/resources/db/migration/](policy/src/main/resources/db/migration/) anlegen (identische Struktur wie [partner/src/main/resources/db/migration/V7__Create_Outbox_Table.sql](partner/src/main/resources/db/migration/V7__Create_Outbox_Table.sql))
- [x] `OutboxEvent`-Klasse von partner nach policy übertragen → nach `infrastructure/messaging/outbox/` (nicht in domain, siehe TASK-09)
- [x] `OutboxRepository`-Port in [policy/domain/port/out/](policy/src/main/java/ch/css/policy/domain/port/out/) anlegen
- [x] `OutboxJpaAdapter` und `OutboxEntity` in [policy/infrastructure/persistence/](policy/src/main/java/ch/css/policy/infrastructure/persistence/) anlegen
- [x] [PolicyApplicationService.java](policy/src/main/java/ch/css/policy/domain/service/PolicyApplicationService.java): Alle Kafka-Aufrufe via `PolicyEventPublisher` durch `OutboxRepository.save(...)` ersetzen
- [x] [PolicyKafkaAdapter.java](policy/src/main/java/ch/css/policy/infrastructure/messaging/PolicyKafkaAdapter.java): Port-Implementierung entfernen; Adapter wird durch Debezium ersetzt
- [x] Debezium-Connector-Konfiguration `policy-outbox-connector.json` in [infra/debezium/](infra/debezium/) anlegen (Vorlage: [partner-outbox-connector.json](infra/debezium/partner-outbox-connector.json))
- [x] `debezium-init` in [docker-compose.yaml](docker-compose.yaml) um den Policy-Connector ergänzen
- [x] `policy-db` in docker-compose.yaml mit `wal_level=logical` konfigurieren (analog partner-db/product-db)
- [x] Alle Unit-Tests in [policy/src/test/](policy/src/test/) anpassen (PolicyTest.java testet nur das Aggregate – keine Änderungen nötig)

**Akzeptanzkriterium:** `PolicyIssued`-Event landet in Kafka auch wenn die Policy-Service-JVM unmittelbar nach dem DB-Commit beendet wird.

---

### TASK-02 · ODC-Contracts und Debezium-Format synchronisieren

**Review-Referenz:** 1.2
**Priorität:** P0 · **Aufwand:** M

**Ziel:** Wire-Format und Contract-Deklaration müssen übereinstimmen.

**Entscheidung erforderlich:** Avro (bevorzugt) oder JSON?

**Option A – Auf Avro umstellen (empfohlen):**
- [ ] Debezium-Connector für Partner (`infra/debezium/partner-outbox-connector.json`) auf `io.confluent.connect.avro.AvroConverter` umstellen
- [ ] Debezium-Connector für Product (`infra/debezium/product-outbox-connector.json`) analog umstellen
- [ ] Avro-Schemas für alle Partner-Events als `.avsc`-Dateien anlegen (→ TASK-03)
- [ ] `schema.registry.url` in beiden Connector-Configs setzen

**Option B – Contracts auf JSON korrigieren:**
- [x] In allen ODC-Contracts unter [partner/src/main/resources/contracts/](partner/src/main/resources/contracts/) `format: JSON` setzen und `schemaRegistry`-Zeile entfernen
- [x] In allen ODC-Contracts unter [product/src/main/resources/contracts/](product/src/main/resources/contracts/) analog korrigieren
- [x] `lint-contracts.py` in [infra/governance/](infra/governance/) anpassen: Avro-spezifische Validierung für JSON-Topics überspringen (Linter prüft diese Felder nicht – keine Änderung nötig)

**Akzeptanzkriterium:** `governance`-Container startet ohne Fehler; alle Contract-Validierungen schlagen bei echter Abweichung fehl.

---

### TASK-03 · Avro-Schemas als `.avsc`-Dateien externalisieren

**Review-Referenz:** 1.3
**Priorität:** P0 · **Aufwand:** M

**Ziel:** Schema-Evolution ist per Code-Review prüfbar; Schema-Dateien sind die einzige Source of Truth.

**Schritte:**

- [x] Verzeichnis `policy/src/main/resources/schema/` anlegen (JSON Schema statt Avro, da TASK-02 Option B gewählt)
- [x] Je eine `.schema.json`-Datei für alle 5 Policy-Events erstellen:
  - `PolicyIssued.schema.json`, `PolicyCancelled.schema.json`, `PolicyChanged.schema.json`, `CoverageAdded.schema.json`, `CoverageRemoved.schema.json`
  - Schemas aus `PolicyEventPayloadBuilder.java` abgeleitet (PolicyKafkaAdapter.java wurde in TASK-01 entfernt)
- [x] Avro-Maven-Plugin entfällt (JSON-Wire-Format); tote Avro-Dependencies (`quarkus-confluent-registry-avro`, `kafka-avro-serializer`) aus [policy/pom.xml](policy/pom.xml) entfernt
- [x] PolicyKafkaAdapter.java bereits in TASK-01 entfernt; kein SchemaBuilder-Code mehr vorhanden
- [x] ODC-Contracts in [policy/src/main/resources/contracts/](policy/src/main/resources/contracts/) um `schemaFile`-Referenz ergänzt
- [x] TASK-02 Option A nicht gewählt → Partner- und Product-`.avsc`-Dateien entfallen

**Akzeptanzkriterium:** `mvn clean package` generiert Avro-Klassen aus `.avsc`; kein `SchemaBuilder`-Code mehr in Infrastruktur-Adaptern.

---

### TASK-04 · Datenbank-Spaltennamen auf Englisch umbenennen

**Review-Referenz:** 1.4
**Priorität:** P0 · **Aufwand:** M

**Ziel:** ADR-005 vollständig durchsetzen – keine deutschen Bezeichner in Datenbankschemas.

**Partner-Service:**

- [x] Flyway-Migration `V8__Rename_Columns_To_English.sql` in [partner/src/main/resources/db/migration/](partner/src/main/resources/db/migration/) angelegt (inkl. `geschlecht`→`gender`, `adress_id`→`address_id`, `ort`→`city` sowie alle Audit-Tabellen)
- [x] [PersonEntity.java](partner/src/main/java/ch/css/partner/infrastructure/persistence/PersonEntity.java): `@Column(name = ...)` auf englische Namen aktualisiert
- [x] [AddressEntity.java](partner/src/main/java/ch/css/partner/infrastructure/persistence/AddressEntity.java): `@Column(name = ...)` und `@Table(name = "address")` aktualisiert
- [x] JPQL-Queries in [PersonJpaAdapter.java](partner/src/main/java/ch/css/partner/infrastructure/persistence/PersonJpaAdapter.java) geprüft – nutzen Java-Feldnamen, keine Änderung nötig
- [x] Envers-Audit-Tabellen: `adresse_aud` → `address_aud` in Migration; Audit-Adapter nutzt Entity-Getter, keine Codeänderung nötig

**Policy-Service:**

- [x] Flyway-Migration `V6__Rename_Columns_To_English.sql` in [policy/src/main/resources/db/migration/](policy/src/main/resources/db/migration/) angelegt (V5 war bereits für Outbox belegt; inkl. `deckung_id`→`coverage_id` sowie alle Audit-Tabellen `deckung_aud`→`coverage_aud`)
- [x] [PolicyEntity.java](policy/src/main/java/ch/css/policy/infrastructure/persistence/PolicyEntity.java): `@Column(name = ...)` aktualisiert
- [x] [CoverageEntity.java](policy/src/main/java/ch/css/policy/infrastructure/persistence/CoverageEntity.java): `@Column(name = ...)` und `@Table(name = "coverage")` aktualisiert
- [x] JPQL-Queries in [PolicyJpaAdapter.java](policy/src/main/java/ch/css/policy/infrastructure/persistence/PolicyJpaAdapter.java) geprüft – nutzen Java-Feldnamen, keine Änderung nötig

**Akzeptanzkriterium:** `mvn test` grün; alle Envers-Audit-Queries funktionieren; keine deutschen Bezeichner in DB-Schema.

---

### TASK-05 · REST-Pfade auf Englisch umbenennen

**Review-Referenz:** 1.5
**Priorität:** P0 · **Aufwand:** S

**Ziel:** API-Pfade als technische Bezeichner müssen ADR-005 folgen.

**Partner-Service:**
- [x] [PersonRestAdapter.java](partner/src/main/java/ch/css/partner/infrastructure/web/PersonRestAdapter.java): `@Path("/api/personen")` → `@Path("/api/persons")`, Sub-Pfad `adressen` → `addresses`
- [x] [PersonUiController.java](partner/src/main/java/ch/css/partner/infrastructure/web/PersonUiController.java): interne Redirect-URLs anpassen

**Product-Service:**
- [x] `ProductRestAdapter.java`: `@Path("/api/produkte")` → `@Path("/api/products")`
- [x] `ProductUiController.java`: interne Redirect-URLs anpassen

**Policy-Service:**
- [x] [PolicyRestAdapter.java](policy/src/main/java/ch/css/policy/infrastructure/web/PolicyRestAdapter.java):
  - `@Path("/api/policen")` → `@Path("/api/policies")`
  - `/aktivieren` → `/activate`
  - `/kuendigen` → `/cancel`
  - `/deckungen` → `/coverages`
- [x] [PolicyUiController.java](policy/src/main/java/ch/css/policy/infrastructure/web/PolicyUiController.java): Redirect-URLs anpassen
- [x] Qute-Templates: `fetch`/`action`-Attribute in HTML auf neue Pfade aktualisieren (Labels bleiben Deutsch)
- [x] Swagger-UI testen: alle Endpunkte erreichbar

**Akzeptanzkriterium:** Alle REST-Endpunkte unter englischen Pfaden erreichbar; Qute-UI funktioniert; keine deutschen Pfad-Segmente mehr.

---

### TASK-06 · Integrationstests implementieren

**Review-Referenz:** 1.6
**Priorität:** P0 · **Aufwand:** L

**Ziel:** Kritische Pfade end-to-end abgesichert mit echter DB und echtem Kafka.

**Voraussetzungen:** Testcontainers-Dependency in allen Services bereits im pom.xml (prüfen, ggf. ergänzen)

**Partner-Service** – `partner/src/test/java/ch/css/partner/integration/`:
- [ ] `PersonFlywayMigrationIT.java`: Flyway läuft durch; alle Tabellen und Indizes vorhanden
- [ ] `PersonOutboxRoundtripIT.java`: Person anlegen → Outbox-Eintrag in DB → Debezium-Mock oder direkter Kafka-Consumer-Check
- [ ] `PersonRestAdapterIT.java`: POST/GET/PUT/DELETE gegen echte DB

**Product-Service** – `product/src/test/java/ch/css/product/integration/`:
- [ ] `ProductFlywayMigrationIT.java`
- [ ] `ProductOutboxRoundtripIT.java`

**Policy-Service** – `policy/src/test/java/ch/css/policy/integration/`:
- [ ] `PolicyFlywayMigrationIT.java`
- [ ] `PolicyActivationIT.java`: Policy anlegen → aktivieren → Outbox-Eintrag vorhanden (nach TASK-01)
- [ ] `PartnerViewMaterializationIT.java`: `person.v1.created`-Event einspielen → `PartnerView`-Tabelle befüllt
- [ ] `ProductViewMaterializationIT.java`: `product.v1.defined`-Event einspielen → `ProductView`-Tabelle befüllt

**Konfiguration:**
- [ ] `@QuarkusIntegrationTest` mit `@QuarkusTestResource(PostgreSQLTestResource.class)` und `@QuarkusTestResource(KafkaTestResource.class)` konfigurieren
- [ ] Maven-Profil `integration` in allen `pom.xml` Dateien konfigurieren (bereits in CLAUDE.md beschrieben)

**Akzeptanzkriterium:** `mvn verify -Pintegration` grün in allen drei Services.

---

### TASK-07 · Dead-Letter-Queue-Strategie implementieren

**Review-Referenz:** 1.7
**Priorität:** P0 · **Aufwand:** S

**Ziel:** Einzelne Poison-Pill-Messages blockieren nicht dauerhaft den Consumer-Channel.

**Policy-Service:**
- [ ] [PartnerEventConsumer.java](policy/src/main/java/ch/css/policy/infrastructure/messaging/PartnerEventConsumer.java): `failure-strategy=dead-letter-queue` konfigurieren; Try/Catch mit strukturiertem Error-Log
- [ ] [ProductEventConsumer.java](policy/src/main/java/ch/css/policy/infrastructure/messaging/ProductEventConsumer.java): analog
- [ ] [policy/src/main/resources/application.properties](policy/src/main/resources/application.properties) ergänzen:
  ```properties
  mp.messaging.incoming.partner-person-created.failure-strategy=dead-letter-queue
  mp.messaging.incoming.partner-person-created.dead-letter-queue.topic=partner-person-created-dlq
  mp.messaging.incoming.partner-person-updated.failure-strategy=dead-letter-queue
  mp.messaging.incoming.partner-person-updated.dead-letter-queue.topic=partner-person-updated-dlq
  mp.messaging.incoming.product-defined-in.failure-strategy=dead-letter-queue
  mp.messaging.incoming.product-defined-in.dead-letter-queue.topic=product-defined-dlq
  # ... weitere Channels
  ```
- [ ] DLQ-Topics in `kafka-init` in [docker-compose.yaml](docker-compose.yaml) vorab anlegen

**Akzeptanzkriterium:** Malformiertes JSON-Event stoppt nicht den Consumer; fehlerhafte Message landet im DLQ-Topic (per AKHQ prüfbar).

---

## Phase 2 – Architektur-Verbesserungen (P1)

---

### TASK-08 · Command/Query in Application Services trennen

**Review-Referenz:** 2.1
**Priorität:** P1 · **Aufwand:** M

**Schritte:**

- [ ] [PolicyApplicationService.java](policy/src/main/java/ch/css/policy/domain/service/PolicyApplicationService.java) aufteilen in:
  - `PolicyCommandService.java` (activatePolicy, cancelPolicy, createPolicy, updatePolicyDetails, addCoverage, removeCoverage)
  - `PolicyQueryService.java` (searchPolicies, getPartnerViewsMap, searchPartnerViews, getActiveProducts, getProductViewsMap)
- [ ] `PersonApplicationService.java` aufteilen in `PersonCommandService` + `PersonQueryService`
- [ ] `ProductApplicationService.java` aufteilen in `ProductCommandService` + `ProductQueryService`
- [ ] Alle Ports in `domain/port/in/` in Command-Ports und Query-Ports aufteilen (falls Use-Case-Ports vorhanden)
- [ ] REST-Adapter und UI-Controller auf die aufgeteilten Services umstellen
- [ ] Unit-Tests entsprechend anpassen

**Akzeptanzkriterium:** Kein Application-Service enthält gleichzeitig mutierte Methoden und reine Lesemethoden.

---

### TASK-09 · `OutboxEvent` und `EventPayloadBuilder` aus der Domänenschicht entfernen

**Review-Referenz:** 3.1 + 3.2
**Priorität:** P1 · **Aufwand:** S

**Ziel:** Domänenschicht hat null Infrastruktur- oder Serialisierungs-Concerns.

**Partner-Service:**
- [ ] [OutboxEvent.java](partner/src/main/java/ch/css/partner/domain/model/OutboxEvent.java) von `domain/model/` nach `infrastructure/messaging/outbox/` verschieben
- [ ] [PersonEventPayloadBuilder.java](partner/src/main/java/ch/css/partner/domain/service/PersonEventPayloadBuilder.java) von `domain/service/` nach `infrastructure/messaging/` verschieben
- [ ] [OutboxRepository.java](partner/src/main/java/ch/css/partner/domain/port/out/OutboxRepository.java): Port-Interface bleibt in `domain/port/out/`, verweist aber auf einen technischen Typ – dokumentieren oder durch typisierte Domain-Events ersetzen (→ TASK-13)

**Product-Service:**
- [ ] `OutboxEvent.java` analog verschieben
- [ ] `ProductEventPayloadBuilder.java` analog verschieben

**Policy-Service:**
- [ ] Nach TASK-01: neues `OutboxEvent` direkt in `infrastructure/messaging/outbox/` platzieren

**Akzeptanzkriterium:** `domain/model/` und `domain/service/` enthalten keine Klassen mit Jackson-, Kafka- oder Outbox-Imports.

---

### TASK-10 · Read-Model-Bootstrapping definieren und implementieren

**Review-Referenz:** 2.2
**Priorität:** P1 · **Aufwand:** M

**Schritte:**

- [ ] `product.v1.state` compacted Topic in `kafka-init` in [docker-compose.yaml](docker-compose.yaml) anlegen (analog `person.v1.state`)
- [ ] [ProductApplicationService.java](product/src/main/java/ch/css/product/domain/service/ProductApplicationService.java): Bei jeder Mutation zusätzlich ein State-Event in die Outbox schreiben (Topic: `product.v1.state`)
- [ ] ODC-Contract `product.v1.state.odcontract.yaml` in [product/src/main/resources/contracts/](product/src/main/resources/contracts/) anlegen
- [ ] [PartnerEventConsumer.java](policy/src/main/java/ch/css/policy/infrastructure/messaging/PartnerEventConsumer.java): Startup-Logik implementieren – zuerst `person.v1.state` von `earliest` konsumieren, dann auf Event-Topics wechseln
- [ ] [ProductEventConsumer.java](policy/src/main/java/ch/css/policy/infrastructure/messaging/ProductEventConsumer.java): analog mit `product.v1.state`
- [ ] Bootstrap-Protokoll in [partner/specs/business_spec.md](partner/specs/business_spec.md) und [product/specs/business_spec.md](product/specs/business_spec.md) dokumentieren

**Akzeptanzkriterium:** Frisch deployeter Policy-Service (leere DB, voller Kafka) materialisiert korrekte `PartnerView` und `ProductView` aus State-Topics.

---

### TASK-11 · Keycloak in docker-compose integrieren

**Review-Referenz:** 2.4
**Priorität:** P1 · **Aufwand:** M

**Schritte:**

- [ ] Keycloak-Service in [docker-compose.yaml](docker-compose.yaml) ergänzen (Image: `quay.io/keycloak/keycloak:24`)
- [ ] Realm-JSON `css-realm.json` erstellen: Realm `css`, Clients `partner-service`, `product-service`, `policy-service`, Rollen `UNDERWRITER`, `CLAIMS_AGENT`, `BROKER`, `ADMIN`
- [ ] Keycloak-Init-Job in docker-compose: importiert Realm via `--import-realm`
- [ ] Quarkus-OIDC-Extension in alle drei `pom.xml` aufnehmen (bereits in CLAUDE.md erwähnt): `quarkus-oidc`
- [ ] `application.yml` aller Services: OIDC-Config für Dev- und Prod-Profil eintragen
- [ ] `@RolesAllowed`-Annotationen in Application Services ergänzen (gemäss CLAUDE.md)
- [ ] Test-User für jeden Rolle in Realm-JSON anlegen

**Akzeptanzkriterium:** REST-Endpunkte ohne gültigen JWT geben 401 zurück; UNDERWRITER kann Policy aktivieren, CLAIMS_AGENT nicht.

---

### TASK-12 · dbt-Lifecycle korrigieren: Airflow orchestriert, kein One-Shot

**Review-Referenz:** 2.6
**Priorität:** P1 · **Aufwand:** S

**Schritte:**

- [ ] `dbt`-Service aus [docker-compose.yaml](docker-compose.yaml) entfernen (oder in `profiles: [tools]` verschieben)
- [ ] Airflow-DAG `dbt_transform_dag.py` in [infra/airflow/dags/](infra/airflow/dags/) anlegen:
  - Schedule: `@hourly` (oder konfigurierbares Intervall)
  - Task: `BashOperator` ruft `dbt run --project-dir /dbt` auf
  - Abhängigkeit: läuft nach `platform-consumer` hat neue Events geschrieben
- [ ] Bestehende DAGs in [infra/airflow/dags/](infra/airflow/dags/) auf dbt-DAG als Upstream verweisen

**Akzeptanzkriterium:** `platform-db`-Marts werden planmässig per Airflow aktualisiert; kein dbt-Container läuft beim Compose-Start.

---

### TASK-13 · Domain Events als erstklassige Java-Typen einführen

**Review-Referenz:** 3.5
**Priorität:** P1 · **Aufwand:** M

**Ziel:** Events sind typisiert, IDE-navigierbar und compile-time-sicher.

**Partner-Service:**
- [ ] `domain/model/events/`-Verzeichnis anlegen
- [ ] Records erstellen: `PersonCreatedEvent`, `PersonUpdatedEvent`, `PersonDeletedEvent`, `AddressAddedEvent`, `AddressUpdatedEvent`
- [ ] [PersonApplicationService.java](partner/src/main/java/ch/css/partner/domain/service/PersonApplicationService.java): Statt `outboxRepository.save(new OutboxEvent(...))` ein typisiertes Event emittieren
- [ ] [PersonEventPayloadBuilder.java](partner/src/main/java/ch/css/partner/domain/service/PersonEventPayloadBuilder.java) (nach TASK-09 in Infrastructure): übersetzt `PersonCreatedEvent` → JSON-Payload für Outbox

**Policy-Service:**
- [ ] `PolicyIssuedEvent`, `PolicyCancelledEvent`, `PolicyChangedEvent`, `CoverageAddedEvent`, `CoverageRemovedEvent` als Records anlegen

**Product-Service:**
- [ ] `ProductDefinedEvent`, `ProductUpdatedEvent`, `ProductDeprecatedEvent` anlegen

**Akzeptanzkriterium:** Kein String-Literal `"PolicyIssued"` o.ä. im Application-Service-Code; alle Event-Typ-Referenzen sind Klassen-Referenzen.

---

### TASK-14 · Claims-Domain als Stub implementieren

**Review-Referenz:** 6.1
**Priorität:** P1 · **Aufwand:** M

**Ziel:** ADR-003 (Circuit Breaker für REST Claims→Policy) validierbar machen; alle vier Bounded Contexts vorhanden.

**Schritte:**

- [ ] `claims/`-Verzeichnis und Maven-Modul anlegen; in [pom.xml](pom.xml) als `<module>claims</module>` eintragen
- [ ] `claims/specs/business_spec.md` erstellen (Schadenmeldung, FNOL, Regulierung)
- [ ] Minimales Quarkus-Projekt in `claims/` aufbauen (Skelett mit pom.xml, application.yml, Port 9083)
- [ ] `CoverageCheckPort` (out-Port) als Interface definieren
- [ ] `PolicyCoverageRestClient.java` als REST-Client mit `@RegisterRestClient` und `@CircuitBreaker` implementieren
- [ ] Minimaler `ClaimApplicationService` mit `openClaim()` → ruft `CoverageCheckPort` auf
- [ ] Kafka-Topics `claims.v1.opened` und `claims.v1.settled` definieren
- [ ] ODC-Contracts `claims.v1.opened.odcontract.yaml` und `claims.v1.settled.odcontract.yaml` anlegen
- [ ] `claims-service` in [docker-compose.yaml](docker-compose.yaml) ergänzen (Port 9083)

**Akzeptanzkriterium:** Claims-Service startet; Coverage-Check-Aufruf auf Policy-Service mit Circuit Breaker schlägt bei Policy-Ausfall nach 3 Versuchen offen.

---

### TASK-15 · Paginierung für alle Such-Endpunkte

**Review-Referenz:** 4.3
**Priorität:** P1 · **Aufwand:** S

**Schritte:**

- [ ] `PageRequest`-Record erstellen: `record PageRequest(int page, int size) {}`
- [ ] `PageResult<T>`-Record erstellen: `record PageResult<T>(List<T> content, long totalElements, int totalPages) {}`
- [ ] `PersonRepository`-Port: `search()`-Methode auf `PageResult<Person>` umstellen
- [ ] `ProductRepository`-Port: analog
- [ ] `PolicyRepository`-Port: analog
- [ ] JPA-Adapter: `Panache`- oder `TypedQuery.setFirstResult/setMaxResults`-Paginierung implementieren
- [ ] REST-Adapter: `?page=0&size=20`-Query-Parameter exponieren
- [ ] Qute-Templates: Paginierungs-Controls (Vor/Zurück-Buttons) ergänzen (Text Deutsch: «Weiter», «Zurück»)

**Akzeptanzkriterium:** `GET /api/persons?page=0&size=10` gibt max. 10 Ergebnisse zurück; Response enthält `totalElements` und `totalPages`.

---

### TASK-16 · Java-Version von 25 auf 21 korrigieren

**Review-Referenz:** 4.4
**Priorität:** P1 · **Aufwand:** S

**Schritte:**

- [ ] [pom.xml](pom.xml) (Parent): `<java.version>25</java.version>` → `<java.version>21</java.version>`
- [ ] Alle drei Service-`pom.xml` prüfen: keine eigene Java-Version-Überschreibung
- [ ] `application.yml` aller Services: `quarkus.virtual-threads.enabled=true` setzen (Quarkus-Loom-Integration)
- [ ] `mvn clean package -DskipTests` in allen Services erfolgreich

**Akzeptanzkriterium:** Build läuft mit Java 21 durch; Virtual Threads aktiv (prüfbar per `/q/health`-Thread-Dump).

---

### TASK-17 · Env-Var-Fail-Fast für Produktions-Profil

**Review-Referenz:** 4.6
**Priorität:** P1 · **Aufwand:** S

**Schritte:**

- [ ] [policy/src/main/resources/application.properties](policy/src/main/resources/application.properties): Prod-Profil-Variablen ohne Fallback:
  ```properties
  %prod.mp.messaging.kafka.bootstrap.servers=${KAFKA_BOOTSTRAP_SERVERS}
  %prod.mp.messaging.outgoing.policy-issued.schema.registry.url=${SCHEMA_REGISTRY_URL}
  ```
- [ ] Partner- und Product-`application.yml`: Datenbankverbindung im Prod-Profil ohne Default
- [ ] `.env.example` mit allen Pflicht-Variablen und Beschreibungen aktualisieren
- [ ] README: Sektion «Required Environment Variables» ergänzen

**Akzeptanzkriterium:** Service startet im Prod-Profil ohne gesetzte ENV-Vars mit klarem Fehler (kein lautloser Fallback auf localhost).

---

## Phase 3 – Mittelfristige Verbesserungen (P2)

---

### TASK-18 · Typisierte ID-Value-Objects einführen

**Review-Referenz:** 3.3
**Priorität:** P2 · **Aufwand:** S

- [ ] `PartnerId`, `ProductId`, `PolicyId`, `PersonId` als Records in der jeweiligen `domain/model/`-Schicht anlegen
- [ ] Aggregat-Konstruktoren und Methoden-Signaturen auf typisierte IDs umstellen
- [ ] Cross-Domain-Referenzen in Policy (`partnerId`, `productId`) auf Typen umstellen
- [ ] JPA-Adapter: `AttributeConverter<PartnerId, String>` implementieren

---

### TASK-19 · Policy-Nummern-Generierung explizit implementieren

**Review-Referenz:** 3.4
**Priorität:** P2 · **Aufwand:** S

- [ ] DB-Sequenz in Flyway-Migration anlegen:
  ```sql
  CREATE SEQUENCE policy_number_seq START 1 INCREMENT 1;
  ```
- [ ] `PolicyNumberGenerator`-Service in `infrastructure/persistence/` implementieren (kapselt Sequenzabfrage)
- [ ] Port `PolicyNumberPort` in `domain/port/out/` definieren
- [ ] [PolicyApplicationService.java](policy/src/main/java/ch/css/policy/domain/service/PolicyApplicationService.java) verwendet Port, nicht direkt DB

---

### TASK-20 · Anti-Corruption Layer für Partner-/Product-Events in Policy

**Review-Referenz:** 6.2
**Priorität:** P2 · **Aufwand:** S

- [ ] `infrastructure/messaging/acl/`-Verzeichnis im Policy-Service anlegen
- [ ] `PartnerEventTranslator.java`: übersetzt `PersonCreatedPayload` → `PartnerView`
- [ ] `ProductEventTranslator.java`: übersetzt `ProductDefinedPayload` → `ProductView`
- [ ] [PartnerEventConsumer.java](policy/src/main/java/ch/css/policy/infrastructure/messaging/PartnerEventConsumer.java) und [ProductEventConsumer.java](policy/src/main/java/ch/css/policy/infrastructure/messaging/ProductEventConsumer.java) delegieren Übersetzung an ACL
- [ ] Unit-Tests für beide Translator-Klassen

---

### TASK-21 · Observability: Prometheus + Grafana

**Review-Referenz:** 4.5
**Priorität:** P2 · **Aufwand:** M

- [ ] `quarkus-micrometer-registry-prometheus` in alle drei Service-`pom.xml` hinzufügen
- [ ] Prometheus-Service in [docker-compose.yaml](docker-compose.yaml) ergänzen; scraped `/q/metrics` aller Quarkus-Services
- [ ] Grafana-Service in docker-compose ergänzen; Port 3000
- [ ] Vorkonfiguriertes Grafana-Dashboard als JSON-Datei: Kafka-Consumer-Lag, JVM-Heap, DB-Connection-Pool, HTTP-Request-Rate
- [ ] `grafana/provisioning/` in [infra/](infra/) anlegen

---

### TASK-22 · Docker-Image-Namen und Tags normalisieren

**Review-Referenz:** 4.1 + 4.2
**Priorität:** P2 · **Aufwand:** S

- [ ] [docker-compose.yaml](docker-compose.yaml): Image-Namen auf `css/partner-service`, `css/product-service`, `css/policy-service` umbenennen
- [ ] [.env.example](.env.example): `IMAGE_REGISTRY=css` und `IMAGE_TAG=latest` als konfigurierbare Variablen
- [ ] Quarkus-Maven-Plugin in den `pom.xml` Dateien: `quarkus.container-image.group=${IMAGE_REGISTRY:css}` konfigurieren
- [ ] [build.sh](build.sh): Registry und Tag aus ENV lesen

---

### TASK-23 · Platform-DB auf domain-spezifische Schemas aufteilen

**Review-Referenz:** 2.3
**Priorität:** P2 · **Aufwand:** L

**Erster Schritt (interim):**
- [ ] `infra/platform/init.sql`: Schemas `partner_raw`, `product_raw`, `policy_raw` anlegen statt alles in `public`
- [ ] `infra/platform/consumer.py`: Events in das jeweilige Domain-Schema schreiben
- [ ] `infra/dbt/`-Modelle: `source`-Definitionen auf Schema-Namespaces aktualisieren

**Langfristig (P3):**
- [ ] Separate dbt-Projekte pro Domain: `infra/dbt-partner/`, `infra/dbt-product/`, `infra/dbt-policy/`

---

### TASK-24 · ODC-Contract-Versionierung an CI/CD anbinden

**Review-Referenz:** 5.1
**Priorität:** P2 · **Aufwand:** M

- [ ] GitHub-Actions-/CI-Pipeline anlegen (`.github/workflows/contract-check.yml`):
  - Trigger: PR-Merge auf main
  - Step 1: Schema-Kompatibilitätsprüfung gegen Schema Registry (`infra/governance/schema-compat-check.sh`)
  - Step 2: ODC-Contract-Lint (`infra/governance/lint-contracts.py`)
  - Step 3: Contract-Artefakt publizieren (z.B. in Git-Tag oder separates Repo)
- [ ] DataHub-Ingest um Contract-Version aus ODC-`metadata.version`-Feld ergänzen

---

### TASK-25 · Billing- und Sales-Domains reservieren

**Review-Referenz:** 6.3
**Priorität:** P2 · **Aufwand:** S

- [ ] `billing/specs/business_spec.md` anlegen (Platzhalter: Fakturierung, Mahnwesen, Zahlungseingang)
- [ ] `sales/specs/business_spec.md` anlegen (Platzhalter: Angebote, Maklervermittlung)
- [ ] `specs/arc42.md`: Abschnitte für Billing und Sales mit Status «Geplant, nicht implementiert» ergänzen
- [ ] `kafka-init` in [docker-compose.yaml](docker-compose.yaml): Topic-Namespaces `billing.v1.*` und `sales.v1.*` dokumentarisch reservieren (Kommentare)

---

## Phase 4 – Backlog (P3)

---

### TASK-26 · Einzelner Kafka-Broker: Produktions-Topologie dokumentieren

**Review-Referenz:** 2.5
**Priorität:** P3 · **Aufwand:** S

- [ ] `docker-compose.prod.yaml` anlegen mit 3-Broker-Kafka-Konfiguration
- [ ] README: Sektion «Production Setup» mit Hinweis auf Mindest-3-Broker-Anforderung
- [ ] `replication.factor=3`, `min.insync.replicas=2` in Topic-Konfiguration dokumentieren

---

### TASK-27 · Self-Serve-Datenzugriff in ODC-Contracts definieren

**Review-Referenz:** 5.2
**Priorität:** P3 · **Aufwand:** M

- [ ] Alle ODC-Contracts um `accessPatterns`-Sektion ergänzen
- [ ] Sample-Consumer-Properties-Dateien unter `contracts/samples/` in jedem Service anlegen
- [ ] Data Product Portal (`infra/portal/main.py`): `/governance`-Route um Access-Pattern-Anzeige erweitern

---

### TASK-28 · State-Topic-Bootstrapping dokumentieren

**Review-Referenz:** 5.3
**Priorität:** P3 · **Aufwand:** S

- [ ] [partner/specs/business_spec.md](partner/specs/business_spec.md): Abschnitt «Consumer Bootstrap Protocol» ergänzen
- [ ] [product/specs/business_spec.md](product/specs/business_spec.md): analog (nach TASK-10)
- [ ] [policy/specs/business_spec.md](policy/specs/business_spec.md): Abschnitt «Read-Model Bootstrapping» ergänzen

---

## Übersicht nach Priorität

| Phase | Tasks | Priorität | Ziel |
|-------|-------|-----------|------|
| **1** | TASK-01 bis TASK-07 | P0 | Go-live-Blocker beseitigen |
| **2** | TASK-08 bis TASK-17 | P1 | Architektur stabilisieren |
| **3** | TASK-18 bis TASK-25 | P2 | Qualität und Data Mesh reifen |
| **4** | TASK-26 bis TASK-28 | P3 | Langfristige Exzellenz |

**Gesamtanzahl Tasks:** 28
**Geschätzter Gesamtaufwand:** ~40–60 Personentage (abhängig von Teamgrösse und Parallelisierung)

---

*Alle Dateipfade verweisen auf das Root-Verzeichnis `/Users/lukasweibel/src/css/datamesh/`*
