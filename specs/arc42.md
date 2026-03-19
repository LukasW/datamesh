# arc42 Architekturdokumentation – Sachversicherung Datamesh

## 1. Einführung und Ziele

### 1.1 Aufgabenstellung

Aufbau einer modernen Versicherungsplattform für eine Sachversicherung auf Basis von:

- **Domain-Driven Design (DDD)** mit klar abgegrenzten Bounded Contexts
- **Hexagonal Architecture** (Ports & Adapters) pro Domäne
- **Self-Contained Systems (SCS)** – jede Domäne ist eine eigenständige Applikation
- **Data Mesh** – jede Domäne besitzt und publiziert ihre Daten als Produkt
- **Asynchrone Integration** via Apache Kafka als primäres Integrationsmuster

### 1.2 Qualitätsziele

| Priorität | Qualitätsmerkmal | Motivation |
|-----------|-----------------|------------|
| 1 | **Autonomie** | Teams entwickeln und deployen unabhängig voneinander |
| 2 | **Datensouveränität** | Jede Domäne ist Owner ihrer Daten (Data Mesh) |
| 3 | **Skalierbarkeit** | Kritische Domänen (Claims, Policy) skalieren unabhängig |
| 4 | **Ausfallsicherheit** | Ausfall einer Domäne beeinflusst andere minimal |
| 5 | **Nachvollziehbarkeit** | Vollständiger Audit-Trail aller Geschäftsvorfälle |

### 1.3 Stakeholder

| Stakeholder | Erwartung |
|-------------|-----------|
| Versicherungsnehmer | Einfache Antragstellung, transparente Schadensabwicklung |
| Underwriter | Risikobeurteilung und Vertragsführung |
| Schadensachbearbeiter | Effiziente Schadensabwicklung |
| IT-Architekten | Klare Schnittstellendefinitionen via ODC |
| Compliance | Vollständige Audit-Trails, DSGVO-Konformität |

---

## 2. Randbedingungen

### 2.1 Technische Randbedingungen

| Constraint | Beschreibung |
|------------|--------------|
| Java 21+ | Alle Services in Java 21 mit Virtual Threads |
| Quarkus | Micro-Framework für schnellen Start und geringen Footprint |
| Apache Kafka | Einziger Kanal für asynchrone Domänenintegration |
| REST | Synchrone Kommunikation nur wo zwingend nötig |
| PostgreSQL | Relationale Persistenz pro Domäne (eigene DB-Instanz) |
| Qute + Bootstrap + htmx | Server-seitige UIs, kein SPA-Overhead |
| Open Data Contract (ODC) | Formale Beschreibung aller publizierten Datensätze |
| Hibernate Envers | Versionierung und Audit-Trails für Entities |

---

### 2.2 Organisatorische Randbedingungen

- Ein autonomes Team pro Domäne (Conway's Law bewusst genutzt)
- Jede Domäne deployed unabhängig (kein gemeinsamer Release-Zug)
- Data Contracts sind verbindliche API-Verträge für Kafka-Topics

---

## 3. Kontextabgrenzung

> **Fachliche Spezifikationen der implementierten Services:**
> - Partner/Customer Service → [`partner/specs/business_spec.md`](../partner/specs/business_spec.md)
> - Product Management Service → [`product/specs/business_spec.md`](../product/specs/business_spec.md)
> - Policy Management Service → [`policy/specs/business_spec.md`](../policy/specs/business_spec.md)

### 3.1 Fachlicher Kontext (Context Map)

```plantuml
@startuml context-map
!theme plain
skinparam componentStyle rectangle
skinparam defaultFontSize 12

title Context Map – Sachversicherung

package "Core Domains" {
  component [Product Management\n(Produktdefinition)] as PM
  component [Policy Management\n(Vertragsverwaltung)] as POL
  component [Claims Management\n(Schadenabwicklung)] as CLM
}

package "Supporting Domains" {
  component [Partner/Customer\nManagement] as PCM
  component [Billing & Collection\n(Inkasso/Exkasso)] as BIL
  component [Sales & Distribution\n(Vertrieb)] as SAL
}

package "Generic Domains" {
  component [Document Management\n(DMS)] as DMS
  component [Identity & Access\nManagement (IAM)] as IAM
}

database "Apache Kafka\n(Event Backbone)" as KAFKA

' Customer/Supplier
PM -down-> KAFKA : publiziert\nProductDefined
KAFKA -down-> POL : konsumiert\nProductDefined

' Upstream/Downstream
POL -down-> KAFKA : publiziert\nPolicyIssued\nPolicyCancelled
KAFKA -down-> CLM : konsumiert\nPolicyIssued
KAFKA -down-> BIL : konsumiert\nPolicyIssued

' Claims triggers Billing
CLM -down-> KAFKA : publiziert\nClaimSettled
KAFKA -down-> BIL : konsumiert\nClaimSettled

' Partner referenced by many
PCM -down-> KAFKA : publiziert\nPartnerCreated\nPartnerUpdated
KAFKA --> POL
KAFKA --> CLM
KAFKA --> BIL

' Sales feeds Policy
SAL -down-> KAFKA : publiziert\nOfferAccepted
KAFKA -down-> POL : konsumiert\nOfferAccepted

' Generic services
DMS <.. POL : Dokumente ablegen
DMS <.. CLM : Schadenfotos
IAM <.. POL
IAM <.. CLM
IAM <.. SAL

@enduml
```

### 3.2 Technischer Kontext

```plantuml
@startuml tech-context
!theme plain
skinparam defaultFontSize 11

title Technischer Kontext

actor "Versicherungsnehmer" as VN
actor "Underwriter" as UW
actor "Sachbearbeiter" as SB
actor "Broker/Agent" as BR

cloud "Browser" as BROWSER

rectangle "Sachversicherungs-Plattform" {
  rectangle "Policy UI\n(Quarkus/Qute/htmx)" as POLUI
  rectangle "Claims UI\n(Quarkus/Qute/htmx)" as CLMUI
  rectangle "Sales UI\n(Quarkus/Qute/htmx)" as SALUI

  component "Policy Service" as POLSVC
  component "Claims Service" as CLMSVC
  component "Product Service" as PRODSVC
  component "Partner Service" as PARTSVC
  component "Billing Service" as BILSVC
  component "Sales Service" as SALSVC
  component "DMS Service" as DMSSVC
  component "IAM Service\n(Keycloak)" as IAMSVC

  database "Kafka Cluster\n(Event Backbone)" as KAFKA
  database "Policy DB\n(PostgreSQL)" as POLDB
  database "Claims DB\n(PostgreSQL)" as CLMDB
  database "Billing DB\n(PostgreSQL)" as BILDB
  database "Product DB\n(PostgreSQL)" as PRODDB
  database "Partner DB\n(PostgreSQL)" as PARTDB
  database "Sales DB\n(PostgreSQL)" as SALESDB
}

VN --> BROWSER
UW --> BROWSER
SB --> BROWSER
BR --> BROWSER

BROWSER --> POLUI
BROWSER --> CLMUI
BROWSER --> SALUI

POLUI --> POLSVC
CLMUI --> CLMSVC
SALUI --> SALSVC

POLSVC --> POLDB
CLMSVC --> CLMDB
BILSVC --> BILDB
PRODSVC --> PRODDB
PARTSVC --> PARTDB
SALSVC --> SALESDB

POLSVC <--> KAFKA
CLMSVC <--> KAFKA
BILSVC <--> KAFKA
PRODSVC <--> KAFKA
PARTSVC <--> KAFKA
SALSVC <--> KAFKA

POLSVC ..> IAMSVC : REST (Auth)
CLMSVC ..> IAMSVC : REST (Auth)

@enduml
```

---

## 4. Lösungsstrategie

### 4.1 Architektur-Entscheidungen

| Entscheidung | Begründung |
|--------------|------------|
| **Self-Contained Systems** | Jede Domäne ist deploybar, testbar und skalierbar ohne andere Domänen |
| **Event-First (Kafka)** | Entkopplung in Raum und Zeit; natürliche Audit-Logs |
| **Data Mesh** | Domänen publishen Daten als Produkt mit Open Data Contract |
| **Hexagonal Architecture** | Domänenlogik ist unabhängig von Infrastruktur (DB, Kafka, UI) |
| **Shared Nothing** | Keine geteilten Datenbanken; kein direkter Service-zu-Service-Aufruf |
| **REST nur synchron** | Für zeitkritische Queries (z.B. IAM-Auth) als Ausnahme |

### 4.2 Data Mesh Prinzipien

```plantuml
@startuml datamesh-principles
!theme plain
skinparam defaultFontSize 11

title Data Mesh – 4 Prinzipien im System

rectangle "1. Domain Ownership" as D1 {
  note as N1
    Jeder BC ist Data Product Owner.
    Policy-Team besitzt PolicyEvents,
    Claims-Team besitzt ClaimEvents.
  end note
}

rectangle "2. Data as a Product" as D2 {
  note as N2
    Kafka-Topics sind Data Products.
    Beschrieben durch Open Data Contracts (ODC).
    SLOs: Verfügbarkeit, Latenz, Qualität.
  end note
}

rectangle "3. Self-Serve Platform" as D3 {
  note as N3
    Kafka Schema Registry (Avro/Protobuf).
    ODC-Katalog für Discoverability.
    Standardisierte Quarkus-Templates.
  end note
}

rectangle "4. Federated Governance" as D4 {
  note as N4
    ODC als verbindlicher Vertrag.
    Breaking Changes → neue Topic-Version.
    Consumer informiert via Changelog-Topic.
  end note
}

@enduml
```

---

## 5. Bausteinsicht

> **Implementierte Services und ihre fachlichen Spezifikationen:**
>
> | Service | Fachspezifikation | Port |
> |---|---|---|
> | Partner/Customer Management | [`partner/specs/business_spec.md`](../partner/specs/business_spec.md) | 9080 |
> | Product Management | [`product/specs/business_spec.md`](../product/specs/business_spec.md) | 9081 |
> | Policy Management | [`policy/specs/business_spec.md`](../policy/specs/business_spec.md) | 9082 |

### 5.1 Ebene 1 – Systemübersicht

```plantuml
@startuml baustein-l1
!theme plain
skinparam componentStyle rectangle
skinparam defaultFontSize 11

title Bausteinsicht Ebene 1 – Domänen-Übersicht

package "Sachversicherungs-Plattform" {

  package "Core" #LightBlue {
    [Product Management SCS\n:9081] as PM
    [Policy Management SCS\n:9082] as POL
    [Claims Management SCS\n(nicht implementiert)] as CLM
  }

  package "Supporting" #LightGreen {
    [Partner/Customer SCS\n:9080] as PCM
    [Billing & Collection SCS\n(nicht implementiert)] as BIL
    [Sales & Distribution SCS\n(nicht implementiert)] as SAL
  }

  package "Generic" #LightYellow {
    [Document Management SCS] as DMS
    [IAM (Keycloak)] as IAM
  }

  database "Apache Kafka\n(KRaft, :9092)" as KAFKA
  database "Schema Registry\n(Avro, :8081)" as SR
  database "AKHQ Kafka UI\n(:8085)" as AKHQ

  package "Analytics & Governance Platform" #LightGrey {
    [Platform Consumer\n(Kafka → platform_db)] as PC
    [dbt\n(Staging + Marts)] as DBT
    [Spark Streaming\n(Delta Lake)] as SPARK
    [Governance\n(lint + compat + freshness)] as GOV
    [Data Product Portal\n(FastAPI, :8090)] as PORTAL
    database "platform_db\n(PostgreSQL, :5435)" as PLTDB
  }

  package "Metadata Platform" #LightCyan {
    [DataHub GMS\n(:8080)] as DHGMS
    [DataHub Frontend\n(:9002)] as DH
    [DataHub Ingest\n(ODC + Kafka)] as DHINGEST
    database "datahub-mysql" as DHMYSQL
    database "Elasticsearch\n(:9200)" as DHES
  }
}

PM --> KAFKA
POL --> KAFKA
PCM --> KAFKA

KAFKA --> SR : Schema\nvalidation
KAFKA --> PC : konsumiert\nperson/product/policy
KAFKA --> SPARK : Structured\nStreaming

PC --> PLTDB
DBT --> PLTDB
PORTAL ..> KAFKA : Metadaten lesen
PORTAL ..> PLTDB : Demo-Abfragen
GOV ..> SR : Schema-Compat\nPrüfung
DHGMS --> DHMYSQL
DHGMS --> DHES
DH --> DHGMS
DHINGEST ..> SR : Avro-Ingestion
DHINGEST ..> KAFKA : Schema-Ingestion

@enduml
```

### 5.2 Ebene 2 – Policy Management SCS (Hexagonal)

```plantuml
@startuml baustein-policy
!theme plain
skinparam defaultFontSize 11
skinparam componentStyle rectangle

title Policy Management – Hexagonale Architektur

package "Policy Management SCS" {

  package "Driving Adapters (Input)" #LightBlue {
    [REST/Qute UI Controller] as UI
    [Kafka Consumer\n(OfferAccepted)\n(PartnerCreated)\n(ProductDefined)] as KCONS
    [REST Server\n(PolicyQuery)] as REST_IN
  }

  package "Domain Core" #LightCoral {
    [Policy Application\nService] as APP
    package "Domain Model" {
      [Policy Aggregate] as POL_AGG
      [Premium Calculator] as PREM
      [Risk Assessor] as RISK
    }
    interface "PolicyRepository\n(Port)" as REPO_PORT
    interface "EventPublisher\n(Port)" as EVENT_PORT
    interface "DocumentPort" as DOC_PORT
  }

  package "Driven Adapters (Output)" #LightGreen {
    [PostgreSQL Adapter\n(JPA/Hibernate)] as DB_ADAPT
    [Kafka Producer\n(PolicyIssued)\n(PolicyCancelled)\n(PolicyChanged)] as KPROD
    [DMS REST Client] as DMS_ADAPT
  }

  database "Policy DB\n(PostgreSQL)" as POLDB
  database "Kafka" as KAFKA
  component "DMS Service" as DMS
}

UI --> APP
KCONS --> APP
REST_IN --> APP

APP --> POL_AGG
APP --> PREM
APP --> RISK
APP --> REPO_PORT
APP --> EVENT_PORT
APP --> DOC_PORT

REPO_PORT <|.. DB_ADAPT
EVENT_PORT <|.. KPROD
DOC_PORT <|.. DMS_ADAPT

DB_ADAPT --> POLDB
KPROD --> KAFKA
DMS_ADAPT --> DMS

@enduml
```

### 5.3 Ebene 2 – Claims Management SCS (Hexagonal)

```plantuml
@startuml baustein-claims
!theme plain
skinparam defaultFontSize 11
skinparam componentStyle rectangle

title Claims Management – Hexagonale Architektur

package "Claims Management SCS" {

  package "Driving Adapters" #LightBlue {
    [Qute UI Controller\n(Schadenmeldung)] as UI
    [Kafka Consumer\n(PolicyIssued)\n(PartnerCreated)] as KCONS
  }

  package "Domain Core" #LightCoral {
    [Claims Application\nService] as APP
    package "Domain Model" {
      [Claim Aggregate] as CLM_AGG
      [Coverage Checker] as COV
      [Settlement Calculator] as SETTLE
    }
    interface "ClaimRepository\n(Port)" as REPO_PORT
    interface "EventPublisher\n(Port)" as EVENT_PORT
    interface "PolicyQueryPort" as POL_PORT
  }

  package "Driven Adapters" #LightGreen {
    [PostgreSQL Adapter] as DB_ADAPT
    [Kafka Producer\n(ClaimOpened)\n(ClaimSettled)\n(ClaimRejected)] as KPROD
    [Policy REST Client\n(synchrone Deckungsprüfung)] as POL_ADAPT
  }

  database "Claims DB\n(PostgreSQL)" as CLMDB
  database "Kafka" as KAFKA
  component "Policy Service\n(REST)" as POLSVC
}

UI --> APP
KCONS --> APP

APP --> CLM_AGG
APP --> COV
APP --> SETTLE
APP --> REPO_PORT
APP --> EVENT_PORT
APP --> POL_PORT

REPO_PORT <|.. DB_ADAPT
EVENT_PORT <|.. KPROD
POL_PORT <|.. POL_ADAPT

DB_ADAPT --> CLMDB
KPROD --> KAFKA
POL_ADAPT --> POLSVC

@enduml
```

---

## 6. Laufzeitsicht

### 6.1 Szenario: Police ausstellen (Policy Issuance)

```plantuml
@startuml runtime-policy-issuance
!theme plain
skinparam defaultFontSize 10

title Laufzeitsicht – Police ausstellen

actor "Underwriter" as UW
participant "Policy UI\n(Qute/htmx)" as UI
participant "Policy\nApplication Service" as APP
participant "Policy Aggregate" as AGG
participant "PostgreSQL\n(Policy DB)" as DB
participant "Kafka Producer" as KPROD
queue "Kafka Topic:\npolicy.v1.issued" as TOPIC
participant "Billing Service\n(Consumer)" as BIL
participant "Claims Service\n(Consumer)" as CLM

UW -> UI : Police erfassen\n(Formular absenden)
UI -> APP : createPolicy(command)
APP -> AGG : apply(PolicyIssuanceRequested)
AGG -> AGG : validateRisk()\ncalculatePremium()
AGG -> DB : save(PolicyEntity)
AGG -> KPROD : publish(PolicyIssuedEvent)
KPROD -> TOPIC : PolicyIssuedEvent\n{policyId, partnerId,\nproductId, premium, ...}
APP --> UI : 201 Created + policyId
UI --> UW : Police erstellt ✓

TOPIC -> BIL : konsumiert PolicyIssuedEvent\n→ Rechnung erstellen
TOPIC -> CLM : konsumiert PolicyIssuedEvent\n→ Policy-Snapshot speichern

@enduml
```

### 6.2 Szenario: Partner im Policy-Erfassungsformular suchen (Partner Picker)

```plantuml
@startuml runtime-partner-picker
!theme plain
skinparam defaultFontSize 10

title Laufzeitsicht – Partner-Suche im Policy-Formular (htmx)

actor "Underwriter" as UW
participant "Policy UI\n(Qute/htmx)" as UI
participant "PolicyUiController" as CTRL
participant "PolicyApplicationService" as APP
database "partner_sicht\n(Policy DB – Read Model)" as DB

UW -> UI : Klick «🔍 Partner suchen»
UI -> CTRL : GET /policen/fragments/partner-suche\n[htmx – lädt Widget]
CTRL -> APP : searchPartnerSichten("")
APP -> DB : SELECT * FROM partner_sicht\nORDER BY name LIMIT 20
DB --> APP : Liste PartnerSicht
CTRL --> UI : partner-suchen-widget.html\n(max. 20 Treffer)

UW -> UI : Tipp «Müller» in Suchfeld
UI -> CTRL : GET /policen/fragments/partner-suche?q=Müller\n[htmx – keyup delay 300ms]
CTRL -> APP : searchPartnerSichten("Müller")
APP -> DB : SELECT … WHERE LOWER(name) LIKE '%müller%'
DB --> APP : Gefilterte Treffer
CTRL --> UI : Aktualisiertes Widget (Trefferliste)

UW -> UI : Klick auf Eintrag «Hans Müller»
UI -> CTRL : POST /policen/fragments/partner-auswaehlen/{partnerId}\n[htmx – swap #partner-picker outerHTML]
CTRL -> APP : findPartnerSicht(partnerId)
APP -> DB : SELECT … WHERE partner_id = ?
DB --> APP : PartnerSicht
CTRL --> UI : partner-picker-selected.html\n(hidden input + Anzeige)

note right of UI
  Das hidden input name="partnerId" ist jetzt
  befüllt → wird beim Erstellen der Police
  als Form-Parameter mitgeschickt.
end note

@enduml
```

**Technische Umsetzung:**

| Schicht | Komponente | Verantwortung |
|---------|-----------|---------------|
| Port | `PartnerSichtRepository.search(nameQuery)` | Interface für Name-Suche (max 20 Treffer) |
| Adapter | `PartnerSichtJpaAdapter.search(nameQuery)` | JPA JPQL: `LOWER(name) LIKE :q` |
| Service | `PolicyApplicationService.searchPartnerSichten(q)` | Delegiert an Repository |
| Service | `PolicyApplicationService.findPartnerSicht(id)` | Lookup für Selektion |
| Controller | `GET /policen/fragments/partner-suche?q=` | Liefert Such-Widget (Qute-Fragment) |
| Controller | `POST /policen/fragments/partner-auswaehlen/{id}` | Liefert «Ausgewählt»-State des Pickers |
| Template | `partner-suchen-widget.html` | Live-Such-Panel mit Trefferliste |
| Template | `partner-picker-selected.html` | Picker im «Partner gewählt»-Zustand |

**Datenfluss (Data Mesh – keine direkte DB-Abhängigkeit):**  
Die `partner_sicht`-Tabelle im Policy-Service ist ein lokales Read Model, das ausschließlich durch Kafka-Events (`person.v1.created`, `person.v1.updated`) befüllt wird. Die Suche läuft vollständig gegen diese materialisierte Sicht – es gibt keine synchrone REST-Abhängigkeit zum Partner-Service (ADR-001).



```plantuml
@startuml runtime-fnol
!theme plain
skinparam defaultFontSize 10

title Laufzeitsicht – Schadensfall melden (FNOL)

actor "Versicherungsnehmer" as VN
participant "Claims UI" as UI
participant "Claims\nApplication Service" as APP
participant "Claim Aggregate" as AGG
participant "Policy Service\n(REST)" as POLSVC
participant "Coverage Checker" as COV
participant "Claims DB" as DB
participant "Kafka Producer" as KPROD
queue "Kafka Topic:\nclaims.v1.opened" as TOPIC
participant "Billing Service" as BIL
participant "DMS Service" as DMS

VN -> UI : Schaden melden\n(Datum, Beschreibung, Fotos)
UI -> APP : reportClaim(command)
APP -> POLSVC : getPolicy(policyId) [REST]
POLSVC --> APP : PolicySnapshot\n(Deckungsumfang, Selbstbehalt)
APP -> COV : checkCoverage(claim, policySnapshot)
COV --> APP : CoverageResult (gedeckt/nicht gedeckt)
APP -> AGG : apply(ClaimOpenedEvent)
AGG -> DB : save(ClaimEntity)
AGG -> DMS : uploadDocuments(fotos) [REST]
AGG -> KPROD : publish(ClaimOpenedEvent)
KPROD -> TOPIC : ClaimOpenedEvent\n{claimId, policyId,\namount, coverageResult}
APP --> UI : 201 Created + claimId
UI --> VN : Schadensfall registriert ✓

TOPIC -> BIL : Rückstellung prüfen

@enduml
```

### 6.3 Szenario: Schadensfall abschliessen (Settlement)

```plantuml
@startuml runtime-settlement
!theme plain
skinparam defaultFontSize 10

title Laufzeitsicht – Schadenabschluss

actor "Sachbearbeiter" as SB
participant "Claims UI" as UI
participant "Claims\nApplication Service" as APP
participant "Settlement\nCalculator" as SETTLE
participant "Claims DB" as DB
participant "Kafka Producer" as KPROD
queue "Kafka Topic:\nclaims.v1.settled" as TOPIC
participant "Billing Service\n(Consumer)" as BIL

SB -> UI : Entschädigungsbetrag\neingeben & bestätigen
UI -> APP : settleClaim(claimId, amount)
APP -> SETTLE : calculate(grossAmount, deductible)
SETTLE --> APP : netSettlementAmount
APP -> DB : update(Claim, status=SETTLED)
APP -> KPROD : publish(ClaimSettledEvent)
KPROD -> TOPIC : ClaimSettledEvent\n{claimId, policyId,\npartnerId, netAmount}
APP --> UI : Claim settled ✓
UI --> SB : Abschluss bestätigt

TOPIC -> BIL : konsumiert ClaimSettledEvent\n→ Auszahlung veranlassen\n→ Regress prüfen

@enduml
```

---

## 7. Verteilungssicht

```plantuml
@startuml deployment
!theme plain
skinparam defaultFontSize 10

title Verteilungssicht – Container/Kubernetes

node "Kubernetes Cluster" {

  node "Namespace: core" {
    artifact "product-svc\n(Quarkus JAR)" as PROD_SVC
    database "product-db\n(PostgreSQL)" as PROD_DB
    PROD_SVC --> PROD_DB
  }

  node "Namespace: policy" {
    artifact "policy-svc\n(Quarkus JAR)" as POL_SVC
    database "policy-db\n(PostgreSQL)" as POL_DB
    POL_SVC --> POL_DB
  }

  node "Namespace: claims" {
    artifact "claims-svc\n(Quarkus JAR)" as CLM_SVC
    database "claims-db\n(PostgreSQL)" as CLM_DB
    CLM_SVC --> CLM_DB
  }

  node "Namespace: billing" {
    artifact "billing-svc\n(Quarkus JAR)" as BIL_SVC
    database "billing-db\n(PostgreSQL)" as BIL_DB
    BIL_SVC --> BIL_DB
  }

  node "Namespace: partner" {
    artifact "partner-svc\n(Quarkus JAR)" as PCM_SVC
    database "partner-db\n(PostgreSQL)" as PCM_DB
    PCM_SVC --> PCM_DB
  }

  node "Namespace: sales" {
    artifact "sales-svc\n(Quarkus JAR)" as SAL_SVC
    database "sales-db\n(PostgreSQL)" as SAL_DB
    SAL_SVC --> SAL_DB
  }

  node "Namespace: platform" {
    artifact "Kafka Cluster\n(3 Broker)" as KAFKA
    artifact "Schema Registry" as SR
    artifact "Keycloak (IAM)" as IAM
    artifact "DMS Service" as DMS
    artifact "ODC Catalog" as ODC
    KAFKA --> SR
  }
}

POL_SVC <--> KAFKA
CLM_SVC <--> KAFKA
BIL_SVC <--> KAFKA
PROD_SVC <--> KAFKA
PCM_SVC <--> KAFKA
SAL_SVC <--> KAFKA

CLM_SVC ..> POL_SVC : REST\n(Deckungsprüfung)
POL_SVC ..> IAM : REST (Auth)
CLM_SVC ..> DMS : REST (Dokumente)

@enduml
```

### 7.2 Lokale Entwicklungsumgebung (Podman Compose)

Alle Services laufen lokal via `podman compose up`. Ports und Abhängigkeiten:

```plantuml
@startuml deployment-local
!theme plain
skinparam defaultFontSize 10

title Lokale Deployment-Sicht – Podman Compose

node "Localhost" {

  node "Messaging" {
    artifact "kafka\n(KRaft :9092/:29092)" as KAFKA
    artifact "schema-registry\n(:8081)" as SR
    artifact "akhq\n(:8085)" as AKHQ
    KAFKA --> SR
    AKHQ --> KAFKA
  }

  node "Domain Services" {
    artifact "partner\n(Quarkus :9080)" as PARTNER
    artifact "product\n(Quarkus :9081)" as PRODUCT
    artifact "policy\n(Quarkus :9082)" as POLICY
    database "partner_db\n(PostgreSQL :5432)" as PARTNERDB
    database "product_db\n(PostgreSQL :5433)" as PRODUCTDB
    database "policy_db\n(PostgreSQL :5434)" as POLICYDB
    PARTNER --> PARTNERDB
    PRODUCT --> PRODUCTDB
    POLICY --> POLICYDB
  }

  node "CDC" {
    artifact "debezium-connect\n(:8083)" as DEBEZIUM
    DEBEZIUM --> PARTNERDB : WAL (logical)
    DEBEZIUM --> PRODUCTDB : WAL (logical)
    DEBEZIUM --> KAFKA
  }

  node "Analytics Platform" {
    artifact "platform-consumer\n(Python)" as PC
    artifact "dbt\n(models + tests)" as DBT
    artifact "spark\n(Structured Streaming)" as SPARK
    database "platform_db\n(PostgreSQL :5435)" as PLTDB
    PC --> PLTDB
    DBT --> PLTDB
    artifact "airflow-scheduler" as AIRFLOW_SCH
    artifact "airflow-webserver\n(:8091)" as AIRFLOW_WEB
    database "airflow_db\n(PostgreSQL internal)" as AIRFLOWDB
    AIRFLOW_SCH --> AIRFLOWDB
    AIRFLOW_WEB --> AIRFLOWDB
  }

  node "Governance & Portal" {
    artifact "governance\n(lint + compat + freshness)" as GOV
    artifact "portal\n(FastAPI :8090)" as PORTAL
  }

  node "Metadata Platform" {
    database "datahub-mysql\n(DataHub Store)" as DHMYSQL
    artifact "datahub-elasticsearch\n(:9200)" as DHES
    artifact "datahub-gms\n(:8080)" as DHGMS
    artifact "datahub-frontend\n(:9002)" as DHFE
    artifact "datahub-actions" as DHACT
    artifact "datahub-ingest\n(einmalig)" as DHINGEST
    DHGMS --> DHMYSQL
    DHGMS --> DHES
    DHFE --> DHGMS
    DHACT --> DHGMS
    DHINGEST --> DHGMS
  }
}

PARTNER --> DEBEZIUM : outbox table
PRODUCT --> DEBEZIUM : outbox table
POLICY --> KAFKA : Outbox\n(in-proc)

PC --> KAFKA : konsumiert\nperson/product/policy.*
SPARK --> KAFKA : Structured Streaming

AIRFLOW_SCH --> PLTDB

PORTAL --> PLTDB
PORTAL --> SR

DHGMS --> KAFKA : interne Topics\n(MetadataChange*)
DHINGEST --> SR : Avro-Schema\nIngestion

@enduml
```

**Startup-Reihenfolge (docker-compose depends_on):**

1. `kafka` → `schema-registry`, `akhq`, `kafka-init`
2. `partner-db`, `product-db`, `policy-db` → je Domain-Service
3. `debezium-connect` (depends: kafka, partner-db, product-db) → `debezium-init`
4. `platform-db` → `platform-consumer`, `dbt`
5. `airflow-db` → `airflow-init` → `airflow-scheduler`, `airflow-webserver`
6. `portal`, `governance` (depends: schema-registry healthy, platform-db)
7. `datahub-mysql`, `datahub-elasticsearch` → `datahub-kafka-setup` → `datahub-upgrade` → `datahub-gms` → `datahub-frontend`, `datahub-actions`, `datahub-ingest`

---

## 8. Querschnittliche Konzepte

### 8.1 Data Mesh – Open Data Contracts

Jedes Kafka-Topic wird durch einen **Open Data Contract (ODC)** beschrieben und im ODC-Katalog registriert. Dies ist der verbindliche "Vertrag" zwischen Producer und Consumer.

**Beispiel ODC für `policy.v1.issued`:**

```yaml
# policy.v1.issued.odcontract.yaml
apiVersion: v1
kind: DataContract
metadata:
  name: policy.v1.issued
  version: "1.2.0"
  domain: policy
  description: Event published when a policy is successfully activated (DRAFT → ACTIVE)

spec:
  topic: policy.v1.issued
  format: AVRO
  schemaRegistry: http://schema-registry:8081
  schemaSubject: policy.v1.issued-value

  schema:
    fields:
      - name: eventId
        type: string
        format: uuid
        nullable: false
      - name: eventType
        type: string
        enum: ["PolicyIssued"]
        nullable: false
      - name: policyId
        type: string
        format: uuid
        nullable: false
        description: Unique policy identifier
      - name: policyNumber
        type: string
        nullable: false
        description: Human-readable policy number (e.g. POL-00042)
      - name: partnerId
        type: string
        format: uuid
        nullable: false
        description: Reference to Partner domain (person)
      - name: productId
        type: string
        format: uuid
        nullable: false
        description: Reference to Product domain
      - name: coverageStartDate
        type: string
        format: date
        nullable: false
      - name: premium
        type: string
        description: Annual premium in CHF
        nullable: false
      - name: timestamp
        type: string
        format: datetime
        nullable: false

  quality:
    - type: SodaCL
      checks: |
        checks for policy.v1.issued:
          - not_null:
              columns: [eventId, eventType, policyId, policyNumber, partnerId, productId, coverageStartDate, premium, timestamp]
          - no_duplicate_rows:
              columns: [eventId]

dataProduct:
  owner: team-policy@css.ch
  domain: policy
  outputPort: kafka
  sla:
    freshness: 5m
    availability: "99.9%"
    qualityScore: 0.98
  tags:
    - pii
```

### 8.2 Kafka Topic-Konvention

```
{domain}.v{version}.{event-name}

Beispiele:
  policy.v1.issued
  policy.v1.cancelled
  claims.v1.opened
  claims.v1.settled
  partner.v1.created
  product.v1.defined
```

**Breaking Changes** erfordern eine neue Major-Version (z.B. `policy.v2.issued`). Der alte Topic wird für eine Übergangsperiode parallel betrieben (Consumer-Driven Contract Testing).

### 8.3 Hexagonal Architecture – Schichtenstruktur (Quarkus)

```
{domain}/
├── src/main/java/ch/css/{domain}/
│   ├── domain/                    ← Reine Domänenlogik (kein Framework)
│   │   ├── model/                 ← Aggregate, Entities, Value Objects
│   │   ├── service/               ← Application Services
│   │   └── port/                  ← Interfaces (Input/Output Ports)
│   │       ├── in/                ← UseCasePorts (Commands/Queries)
│   │       └── out/               ← RepositoryPort, EventPublisherPort
│   └── infrastructure/            ← Adapter-Implementierungen
│       ├── persistence/           ← JPA/Hibernate Adapter
│       ├── messaging/             ← Kafka Producer/Consumer (SmallRye)
│       ├── api/                   ← REST Server/Client
│       └── web/                   ← Qute Templates + REST Controllers
├── src/main/resources/
│   ├── templates/                 ← Qute HTML-Templates (UI-Text auf Deutsch)
│   └── contracts/                 ← ODC YAML-Dateien
└── src/test/
    ├── domain/                    ← Unit Tests (reine Domäne, kein Framework)
    └── integration/               ← @QuarkusIntegrationTest (Testcontainers)
```

### 8.4 Event-Sourcing Light (Outbox Pattern)

Um Dual-Write-Probleme zu vermeiden (DB schreiben + Kafka publishen), wird das **Transactional Outbox Pattern** eingesetzt:

```plantuml
@startuml outbox
!theme plain
skinparam defaultFontSize 11

title Transactional Outbox Pattern

participant "Application\nService" as APP
database "Domain DB\n(PostgreSQL)" as DB
participant "Outbox\nPoller\n(Debezium/CDC)" as CDC
queue "Kafka" as KAFKA

APP -> DB : BEGIN TRANSACTION\n  INSERT domain_entity\n  INSERT outbox_events\nCOMMIT
CDC -> DB : CDC / Polling\n(outbox_events)
CDC -> KAFKA : publish event\n(at-least-once)
DB -> DB : mark as published

@enduml
```

### 8.5 Authentifizierung und Autorisierung

- **IAM:** Keycloak (OIDC/OAuth2) – einzige synchrone Abhängigkeit via REST
- **Token-Propagation:** Bearer Tokens in allen HTTP-Requests (Quarkus OIDC)
- **RBAC:** Quarkus `@RolesAllowed` auf Application-Service-Ebene
- **Rollen:** `UNDERWRITER`, `CLAIMS_AGENT`, `BROKER`, `ADMIN`

### 8.6 Data Mesh Analytics- und Governance-Plattform

Die Plattformschicht konsumiert alle Domain-Events und stellt sie als analysierbares Data Warehouse bereit. Sie greift **nie direkt auf die operativen Domain-Datenbanken zu** (ADR-004: Data Inside vs. Data Outside).

```plantuml
@startuml analytics-platform
!theme plain
skinparam defaultFontSize 10

title Analytics & Governance – Datenpfad

queue "Kafka\nperson.v1.*\nproduct.v1.*\npolicy.v1.*" as KAFKA

package "infra/platform" {
  component "Platform Consumer\n(Python/kafka-python-ng)" as PC
  database "raw.person_events\nraw.person_state\nraw.product_events\nraw.policy_events\n(platform_db)" as RAW
  PC --> RAW : UPSERT / INSERT
}

package "infra/dbt" {
  component "Staging Models\n(stg_person_events\nstg_product_events\nstg_policy_events)" as STG
  component "Mart Models\n(dim_partner, dim_product\nfact_policies\nmart_portfolio_summary)" as MART
  component "dbt Tests\n(assert_no_orphan_policies\nassert_premium_positive)" as TESTS
  STG --> MART
  MART --> TESTS
}

package "infra/spark" {
  component "Spark Structured Streaming\n(Delta Lake)" as SPARK
}

package "infra/governance" {
  component "lint-contracts.py\n(ODC-Pflichtfelder)" as LINT
  component "schema-compat-check.sh\n(Avro Backward Compat)" as COMPAT
  component "check-freshness.py\n(Topic-Latenz vs. SLA)" as FRESH
}

package "infra/portal" {
  component "Data Product Portal\n(FastAPI :8090)" as PORTAL
  note right of PORTAL
    /           Produktkatalog
    /products/{topic}  Detail + Schema
    /lineage    Cross-Domain Lineage (D3.js)
    /governance Governance Dashboard
    /demo       mart_portfolio_summary Live
  end note
}

package "infra/datahub" {
  component "DataHub\n(integriert, :9002)" as DH
  note right of DH
    Startet automatisch mit podman compose up.
    Eigene Kafka/ZooKeeper-Instanz entfällt –
    nutzt bestehenden Broker :29092.
    datahub-ingest läuft einmalig beim Start:
      recipe_kafka.yaml  → Topics + Avro-Schemas
      ingest_odc.py      → ODC Custom Properties,
                            Tags, Ownership
  end note
}

KAFKA --> PC
KAFKA --> SPARK

RAW --> STG
STG --> MART

LINT ..> KAFKA : liest ODC-Files
COMPAT ..> KAFKA : Schema Registry
FRESH ..> KAFKA : Lag-Messung

PORTAL --> RAW
PORTAL --> MART

DH ..> KAFKA : Schema Registry Ingestion
DH ..> MART  : dbt Manifest Ingestion

@enduml
```

**dbt-Modellhierarchie:**

| Layer | Model | Quelle | Beschreibung |
| --- | --- | --- | --- |
| Staging | `stg_person_events` | `raw.person_events` | JSON-Parsing, typisierte Spalten |
| Staging | `stg_product_events` | `raw.product_events` | JSON-Parsing, typisierte Spalten |
| Staging | `stg_policy_events` | `raw.policy_events` | JSON-Parsing, typisierte Spalten |
| Mart | `dim_partner` | `stg_person_events` | Aktuellster Stand pro Person |
| Mart | `dim_product` | `stg_product_events` | Aktuellster Stand pro Produkt |
| Mart | `fact_policies` | `stg_policy_events` | Eine Zeile pro Police (aktiver Status) |
| Mart | `mart_portfolio_summary` | `fact_policies` + `dim_partner` + `dim_product` | Cross-Domain-Aggregation: aktive Policen pro Stadt und Produktlinie |

**Governance-Prüfungen (laufen beim Compose-Start):**

| Skript | Prüfung | Fehlverhalten |
| --- | --- | --- |
| `lint-contracts.py` | Alle ODC-Felder mandatory: owner, domain, outputPort, freshness, availability, qualityScore, tags | Exit 1 → Build fehlschlägt |
| `schema-compat-check.sh` | Avro-Schemas backward-kompatibel gegen Schema Registry | Exit 1 → Deployment blockiert |
| `check-freshness.py` | Topic-Lag ≤ SLA-Freshness (5m default) | Exit 1 → Alert im Portal |

### 8.7 Event-Carried State Transfer (ECST) – `person.v1.state`

Neben den Delta-Events (`person.v1.created`, `person.v1.updated`, …) publiziert der Partner-Service das Topic **`person.v1.state`** als **compacted Kafka Topic** (cleanup.policy=compact).

**Zweck:** Consumer können ihren lokalen Read-Model ohne vollständiges Event-Replay aufbauen – sie lesen nur den aktuellen State-Snapshot.

```plantuml
@startuml ecst
!theme plain
skinparam defaultFontSize 11

title Event-Carried State Transfer – person.v1.state

participant "PersonApplicationService" as APP
database "partner_db\noutbox" as OUTBOX
participant "Debezium CDC" as CDC
queue "person.v1.created/updated/deleted\n(Delta-Events, unbegrenzte Retention)" as DELTA
queue "person.v1.state\n(compacted, ein Eintrag pro PersonId)" as STATE
participant "Policy Service\n(PartnerView Read Model)" as POL
participant "Platform Consumer\n(raw.person_state)" as PC

APP -> OUTBOX : INSERT outbox (delta-event + state-event)
CDC -> OUTBOX : WAL polling
CDC -> DELTA : publish PersonCreated/Updated
CDC -> STATE : publish PersonState (key=personId)

STATE -> POL : Consumer liest aktuellen State\nbeim Start (kein Replay nötig)
STATE -> PC : UPSERT in raw.person_state

note right of STATE
  Tombstone-Event (deleted=true):
  Unterstützt DSGVO Right-to-Erasure –
  compaction löscht den Eintrag.
end note

@enduml
```

**ECST vs. Delta-Events – Vergleich:**

| Aspekt | Delta-Events (`person.v1.created`) | State-Topic (`person.v1.state`) |
| --- | --- | --- |
| Inhalt | Einzelne Mutation | Vollständiger aktueller Zustand |
| Retention | Unbegrenzt (7 Jahre) | Compacted (nur neuester Wert pro Key) |
| Verwendung | Audit Trail, Event Sourcing | Read-Model Bootstrap, Query |
| GDPR Erasure | Tombstone + Nachfolge-Events | Tombstone löscht Eintrag via Compaction |

---

## 9. Architekturentscheidungen (ADRs)

### ADR-001: Asynchrone Integration via Kafka

**Status:** Accepted

**Kontext:** Domänen müssen kommunizieren, ohne voneinander abhängig zu sein.

**Entscheidung:** Kafka ist der einzige Integrationskanal. Direkte DB-Zugriffe zwischen Domänen sind verboten.

**Konsequenzen:** Eventual Consistency muss akzeptiert werden. Kompensations-Events statt Rollbacks.

---

### ADR-002: Open Data Contract als verbindlicher Vertrag

**Status:** Accepted

**Kontext:** Ohne formale Verträge entstehen implizite Abhängigkeiten zwischen Teams.

**Entscheidung:** Jedes Kafka-Topic hat einen ODC. Breaking Changes erfordern neue Topic-Version und Abstimmung mit Consumern.

**Konsequenzen:** Initiale Mehrarbeit bei Produktdefinition. Langfristig weniger Integrationsprobleme.

---

### ADR-003: REST nur für synchrone Ausnahmen

**Status:** Accepted

**Kontext:** Deckungsprüfung bei Schadenmeldung braucht aktuelle Policy-Daten (Eventual Consistency reicht nicht).

**Entscheidung:** Claims -> Policy via REST für Deckungsprüfung. Alle anderen Integrationen via Kafka.

**Konsequenzen:** Policy-Service wird zu einer synchronen Abhängigkeit von Claims. Circuit Breaker notwendig.

---

### ADR-004: Shared Nothing – keine geteilten Datenbanken

**Status:** Accepted

**Kontext:** Geteilte Datenbanken schaffen implizite Kopplung zwischen Teams.

**Entscheidung:** Jede Domäne hat ihre eigene PostgreSQL-Instanz. Cross-Domain-Queries werden über Events oder REST abgebildet.

**Konsequenzen:** Kein JOIN über Domänengrenzen. Reporting-Bedarf wird durch dedizierte Read-Models (materialisierte Views aus Events) abgedeckt.

---

### ADR-006: Transactional Outbox Pattern via Debezium CDC (Partner Service)

**Status:** Accepted

**Kontext:** Der bisherige Ansatz im Partner Service publizierte Kafka-Events direkt nach dem Datenbank-Commit (Dual-Write). Fiel der Kafka-Publish fehl, war das Event verloren, obwohl die DB-Transaktion committed war. Dies verletzt das Prinzip der at-least-once Delivery und widerspricht dem architektonischen Qualitätsziel «Ausfallsicherheit».

**Entscheidung:** Der Partner Service schreibt Domain-Events atomar in eine `outbox`-Tabelle innerhalb derselben DB-Transaktion wie die Geschäftsdaten. Debezium Connect liest neue Zeilen via PostgreSQL WAL (logical replication) und publiziert sie an die Kafka-Topics. Der Application Service hat keine direkte Kafka-Abhängigkeit mehr.

```
PersonApplicationService
  └─ outbox INSERT (same TX as domain entity)
       └─ PostgreSQL WAL (wal_level=logical)
            └─ Debezium Connect (EventRouter SMT)
                 └─ Kafka topics  person.v1.*
```

**Konsequenzen:**
- Garantierte at-least-once Delivery (keine Events gehen verloren)
- Leichte Erhöhung der End-to-End-Latenz (WAL-Polling-Intervall von Debezium, typisch < 500ms)
- Debezium Connect ist eine neue Infrastrukturkomponente (eigener Container, Port 8083)
- PostgreSQL benötigt `wal_level=logical` (bereits in `docker-compose.yaml` konfiguriert)
- Der Partner Service hat keine `quarkus-messaging-kafka`-Abhängigkeit mehr

---

### ADR-005: Sprachpolitik – Code Englisch, UI Deutsch

**Status:** Accepted

**Kontext:** Das Projekt richtet sich an eine deutschsprachige Organisation (CSS), die Code jedoch international wartbar halten muss. Ohne klare Regel entstehen Mischsprachen im Codebase.

**Entscheidung:** Strikte Trennung nach Schicht:

| Schicht | Sprache | Beispiele |
| --- | --- | --- |
| Code (Klassen, Methoden, Felder, Logs, Exceptions) | Englisch | `PolicyRepository`, `coverageStartDate`, `PersonCreated` |
| UI (Qute-Templates, Labels, Buttons, Fehlermeldungen) | Deutsch | «Police ausstellen», «Bitte Vorname eingeben» |
| Dokumentation (`specs/`, `CLAUDE.md`) | Englisch | Dieses Dokument (Ausnahme: arc42 auf Deutsch) |
| Kafka Event Types | Englisch, PascalCase | `PolicyIssued`, nicht `PolicyAusgestellt` |

**Konsequenzen:**

- Domänenmodell vollständig in Englisch (`Person`, `Policy`, `Coverage`, `CoverageType.GLASS_BREAKAGE`)
- Qute-Templates vollständig in Deutsch (Benutzerinterface)
- Technische Schuld: Partner- und Policy-Service haben teilweise noch deutsche Feldnamen (R-6, R-7)

---

### ADR-007: Event-Carried State Transfer (ECST) via `person.v1.state`

**Status:** Accepted

**Kontext:** Consumer des Partner-Service müssen bei einem Neustart alle vergangenen Person-Events replay'en, um ihr lokales Read-Model zu befüllen. Bei hohem Eventvolumen ist das zeitintensiv und fehleranfällig.

**Entscheidung:** Der Partner-Service publiziert zusätzlich das compacted Topic `person.v1.state` (cleanup.policy=compact, 6 Partitionen). Bei jeder Personenmutation wird ein vollständiger State-Snapshot mit Key=personId publiziert. Kafka behält nur den neuesten Wert pro Key.

**Konsequenzen:**

- Consumer (z.B. Policy-Service) lesen beim Start nur den letzten State – kein vollständiges Replay
- Platform-Consumer maintained `raw.person_state` als UPSERT-Tabelle
- GDPR Right-to-Erasure: Tombstone-Event (deleted=true) → Compaction entfernt den Eintrag dauerhaft
- Zusätzliche Outbox-Einträge pro Mutation (ein Delta-Event + ein State-Event)

---

## 10. Qualitätsanforderungen

### 10.1 Qualitätsbaum

```plantuml
@startuml quality-tree
!theme plain
skinparam defaultFontSize 11

title Qualitätsbaum

rectangle "Qualitätsziele" as ROOT

rectangle "Autonomie" as A
rectangle "Team-Deploy\nohne Absprache" as A1
rectangle "Unabhängige\nSkalierung" as A2

rectangle "Datensouveränität" as B
rectangle "ODC als\nverbindlicher Vertrag" as B1
rectangle "Kein direkter\nDB-Zugriff" as B2

rectangle "Resilienz" as C
rectangle "Circuit Breaker\n(REST)" as C1
rectangle "Dead Letter\nQueue (Kafka)" as C2

rectangle "Nachvollzieh-\nbarkeit" as D
rectangle "Event Log\n(7 Jahre)" as D1
rectangle "Audit Trail\n(alle Mutationen)" as D2

ROOT --> A
ROOT --> B
ROOT --> C
ROOT --> D

A --> A1
A --> A2
B --> B1
B --> B2
C --> C1
C --> C2
D --> D1
D --> D2

@enduml
```

### 10.2 Qualitätsszenarien

| ID | Qualitätsmerkmal | Szenario | Reaktion | Messgrösse |
|----|-----------------|----------|----------|------------|
| QS-1 | Autonomie | Policy-Team deployed neue Version | Claims-Service läuft unverändert weiter | 0 Deployments anderer Teams nötig |
| QS-2 | Resilienz | Policy-Service nicht erreichbar | Claims zeigt Fehler, Kafka-Events werden gepuffert | Claims-Service erholt sich nach Policy-Recovery |
| QS-3 | Datensouveränität | Consumer will Policy-Daten ändern | Abweisung – nur Policy-Team ändert Policy-Daten | 0 direkte DB-Zugriffe von extern |
| QS-4 | Nachvollziehbarkeit | Audit-Anfrage zu Police XY | Vollständiger Ereignisverlauf aus Kafka | 100% der Mutationen im Log |
| QS-5 | Performance | 1000 gleichzeitige Schadenmeldungen | System verarbeitet alle innerhalb 30s | p99 < 3s response time |

---

## 11. Risiken und technische Schulden

| ID | Risiko | Auswirkung | Massnahme |
|----|--------|------------|-----------|
| R-1 | Eventual Consistency schwer verständlich für Entwickler | Fehler bei UI-Feedback ("Ist die Police schon aktiv?") | UI-Patterns für Async (optimistic updates, polling) |
| R-2 | Schema-Evolution (Avro) komplex | Breaking Changes unbemerkt | ODC Enforcement + Consumer-Driven Contract Tests |
| R-3 | Kafka Single Point of Failure | Alle Domänen betroffen | Multi-AZ Kafka Cluster, Replikationsfaktor 3 |
| R-4 | REST Claims->Policy synchrone Abhängigkeit | Claims bei Policy-Ausfall nicht nutzbar | Circuit Breaker (SmallRye Fault Tolerance) + Fallback |
| R-5 | Data Mesh Governance-Overhead | Teams umgehen ODC | Automatisierte ODC-Validierung in CI/CD-Pipeline |
| R-6 | **Sprachinkonsistenz: Partner-Service (TD)** | Domainmodell verwendet deutsche Feldnamen und Klassennamen (`vorname`, `Adresse`, `Geschlecht`, `gueltigVon` etc.) anstatt englischer (ADR-005-Verletzung) | Koordiniertes Refactoring + ODC-Versionsbump für geänderte Event-Felder. Details: [`partner/specs/business_spec.md#10`](../partner/specs/business_spec.md) |
| R-7 | **Sprachinkonsistenz: Policy-Service (TD)** | Domainmodell und Enums verwenden deutsche Bezeichner (`Deckung`, `Deckungstyp`, `ENTWURF`, `AKTIV`, `versicherungsbeginn` etc.) | Koordiniertes Refactoring über alle Consumer hinweg nötig. Details: [`policy/specs/business_spec.md#10`](../policy/specs/business_spec.md) |
