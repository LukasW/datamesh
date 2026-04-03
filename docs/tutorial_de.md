# Tutorial – Sachversicherung Datamesh Platform

> Umfassendes Tutorial zu Architektur, Konzepten, Tools und Zusammenhängen der Plattform. Dieses Dokument erklärt das **Warum** hinter den Entwurfsentscheidungen, beschreibt alle Komponenten im Detail und zeigt, wie ein Geschäftsvorfall vom ersten Klick bis zur domainübergreifenden Auswertung durch alle Systeme fliesst.

---

## Inhaltsverzeichnis

1. [Was ist diese Plattform?](#1-was-ist-diese-plattform)
2. [Architekturprinzipien](#2-architekturprinzipien)
   - 2.1 [Domain-Driven Design (DDD)](#21-domain-driven-design-ddd)
   - 2.2 [Hexagonale Architektur](#22-hexagonale-architektur)
   - 2.3 [Self-Contained Systems (SCS)](#23-self-contained-systems-scs)
   - 2.4 [Data Mesh](#24-data-mesh)
   - 2.5 [Architekturentscheidungen (ADRs)](#25-architekturentscheidungen-adrs)
3. [Infrastruktur – Das Rückgrat der Plattform](#3-infrastruktur--das-rückgrat-der-plattform)
   - 3.1 [Apache Kafka – Der Event-Bus](#31-apache-kafka--der-event-bus)
   - 3.2 [Schema Registry – Vertrag für Datenformate](#32-schema-registry--vertrag-für-datenformate)
   - 3.3 [Debezium – Change Data Capture](#33-debezium--change-data-capture)
   - 3.4 [AKHQ – Kafka beobachten](#34-akhq--kafka-beobachten)
4. [Domain Services](#4-domain-services)
   - 4.1 [Partner Service – Personenverwaltung](#41-partner-service--personenverwaltung)
   - 4.2 [Product Service – Produktverwaltung](#42-product-service--produktverwaltung)
   - 4.3 [Policy Service – Policenverwaltung](#43-policy-service--policenverwaltung)
   - 4.4 [Billing Service – Fakturierung & Mahnwesen](#44-billing-service--fakturierung--mahnwesen)
   - 4.5 [Claims Service – Schadenbearbeitung (Stub)](#45-claims-service--schadenbearbeitung-stub)
5. [Schlüsselmuster der Event-Architektur](#5-schlüsselmuster-der-event-architektur)
   - 5.1 [Transactional Outbox Pattern](#51-transactional-outbox-pattern)
   - 5.2 [Event-Carried State Transfer (ECST)](#52-event-carried-state-transfer-ecst)
   - 5.3 [Consumer-seitige Read Models](#53-consumer-seitige-read-models)
   - 5.4 [Dead Letter Queue (DLQ)](#54-dead-letter-queue-dlq)
6. [Analytics Platform – Data Outside](#6-analytics-platform--data-outside)
   - 6.1 [Platform Consumer – Landing Zone](#61-platform-consumer--landing-zone)
   - 6.2 [dbt – Transformation in Schichten](#62-dbt--transformation-in-schichten)
   - 6.3 [Spark Structured Streaming](#63-spark-structured-streaming)
   - 6.4 [Apache Airflow – Orchestrierung](#64-apache-airflow--orchestrierung)
7. [Data Governance – Vertrauen durch Kontrolle](#7-data-governance--vertrauen-durch-kontrolle)
   - 7.1 [Open Data Contract (ODC)](#71-open-data-contract-odc)
   - 7.2 [Governance-Container – Automatisierte Qualitätstore](#72-governance-container--automatisierte-qualitätstore)
   - 7.3 [Data Product Portal](#73-data-product-portal)
8. [DataHub – Unternehmensweiter Metadaten-Katalog](#8-datahub--unternehmensweiter-metadaten-katalog)
9. [End-to-End Durchlauf – Ein Geschäftsvorfall durch alle Systeme](#9-end-to-end-durchlauf--ein-geschäftsvorfall-durch-alle-systeme)
10. [Konzept-Glossar](#10-konzept-glossar)
11. [Organisation](#11-organisation)
    - 11.1 [Team-Struktur und Conway's Law](#111-team-struktur-und-conways-law)
    - 11.2 [Teams und ihre Zuständigkeiten](#112-teams-und-ihre-zuständigkeiten)
    - 11.3 [Datenprodukt-Ownership](#113-datenprodukt-ownership)
    - 11.4 [Assessment: Unterstützt die Architektur maximale Unabhängigkeit?](#114-assessment-unterstützt-die-architektur-maximale-unabhängigkeit)

---

## 1. Was ist diese Plattform?

Die **Yuno Sachversicherung Datamesh Platform** ist ein modernes Versicherungssystem, das zeigt, wie ein mittelgrosses Versicherungsunternehmen seine IT-Landschaft auf der Basis aktueller Software-Engineering-Prinzipien gestalten kann.

### Das Geschäftsproblem

Klassische Versicherungs-IT litt jahrzehntelang unter drei Kernproblemen:

1. **Monolyth-Falle** – Ein einziges System enthält alles: Kundenstamm, Produkte, Policen, Schäden, Abrechnungen. Jede Änderung erfordert Koordination zwischen allen Teams. Releases werden zum Risiko.

2. **Daten-Silos mit zentralem Chaos** – Datenteams pflegen ein zentrales Data Warehouse. Jeder Domainteam muss «ETL-Tickets» beantragen, um seine Daten in das Warehouse zu liefern. Das schafft Abhängigkeiten, Verzögerungen und Qualitätsprobleme weit vom Datenerzeuger entfernt.

3. **Tight Coupling** – Services rufen sich gegenseitig synchron auf. Fällt ein Service aus, kaskadiert der Ausfall. Eine Police kann nicht ausgestellt werden, weil der Billing-Service gerade nicht antwortet.

### Die Lösung: Autonome Domains + Data Mesh

Diese Plattform löst alle drei Probleme:

- **Autonomie**: Jeder Domain-Service ist ein vollständig eigenständiges System mit eigener Datenbank, eigenem UI, eigenem Kafka-Namespace. Das Partner-Team kann deployen, ohne das Policy-Team zu koordinieren.

- **Data Mesh**: Jedes Team besitzt nicht nur seinen Service, sondern auch seine **Daten als Produkt** – vollständig beschrieben, qualitätsgesichert, direkt konsumierbar. Kein zentrales ETL-Team mehr.

- **Async-First**: Domains kommunizieren über Kafka-Events. Synchrone REST-Calls gibt es nur in wenigen, wohldefinierten Ausnahmen (ADR-003).

### Die vier implementierten Domains

| Domain | Zuständigkeit | Port |
|---|---|---|
| **Partner** | Natürliche Personen (Versicherungsnehmer) | 9080 |
| **Product** | Produktkatalog, Deckungstypen, Prämienstruktur | 9081 |
| **Policy** | Policenlebenszyklus (Entwurf → Aktiv → Gekündigt) | 9082 |
| **Billing** | Fakturierung, Zahlungseingang, Mahnwesen, Auszahlungen | 9084 |

Zusätzlich gibt es einen **Claims-Stub** (9083) der die Domain-Modell-Struktur zeigt, aber noch keine Kafka-Integration oder Persistenz hat.

---

## 2. Architekturprinzipien

### 2.1 Domain-Driven Design (DDD)

**Domain-Driven Design** ist eine Methodik, um komplexe Software an der Fachlichkeit auszurichten – nicht an technischen Schichten.

#### Ubiquitäre Sprache (Ubiquitous Language)

Jede Domain hat ihr eigenes Vokabular, das in Code, Tests, Dokumentation und Gesprächen einheitlich verwendet wird. Beispiele:

| German (UI) | English (Code) | Kontext |
|---|---|---|
| Police | Policy | Versicherungsvertrag |
| Schaden | Claim | Schadenfall |
| Prämie | Premium | Versicherungsgebühr |
| Selbstbehalt | Deductible | Eigenanteil des Kunden |
| Deckung | Coverage | Was versichert ist |
| Mahnstufe | DunningLevel | Eskalationsstufe im Mahnwesen |

> **Warum ist das wichtig?** Wenn Entwickler und Fachexperten unterschiedliche Begriffe verwenden, entstehen Missverständnisse, die sich als Bugs im Code materialisieren. Eine gemeinsame Sprache – genau so im Code wie im Gespräch – eliminiert diese Klasse von Fehlern.

#### Bounded Contexts

Jede Domain ist ein **Bounded Context**: Ein klar abgegrenzter Bereich, innerhalb dessen ein Konzept genau eine Bedeutung hat. Das Wort «Person» bedeutet im Partner-Kontext «Versicherungsnehmer mit Stammdaten»; im Policy-Kontext bedeutet «Person» nur noch «Referenz-ID zu einem externen Partner».

Diese Abgrenzung ist physisch erzwungen: Kein Service darf direkt auf die Datenbank einer anderen Domain zugreifen (ADR-004).

#### Aggregates

Ein **Aggregate** ist die Einheit der Konsistenzgarantie. Alle Zustandsänderungen innerhalb eines Aggregats geschehen atomar. Beispiele:

- `Invoice` (Billing): Kann nur bezahlt oder storniert werden, wenn sie vorher OPEN oder OVERDUE ist. Die Übergänge sind im Aggregat selbst kodiert – nicht in einem Service.
- `Policy` (Policy): Eine Police kann nur aktiviert werden, wenn sie im ENTWURF-Status ist. Das `activate()`-Kommando prüft diesen Invarianten.

```
// Invoice ist das Aggregate Root
Invoice {
  - invoiceId
  - status: OPEN → PAID / OVERDUE / CANCELLED
  - lineItems: List<InvoiceLineItem>   // Teil des Aggregats
  + recordPayment()   // ändert Status zu PAID
  + markOverdue()     // ändert Status zu OVERDUE
  + cancel()          // ändert Status zu CANCELLED
}
```

### 2.2 Hexagonale Architektur

Die **Hexagonale Architektur** (auch «Ports & Adapters» oder «Clean Architecture») trennt das Domain-Modell von technischen Details.

```
┌─────────────────────────────────────────────────┐
│                 Infrastructure                   │
│  ┌─────────────┐  ┌──────────┐  ┌────────────┐  │
│  │ REST-Adapter│  │ JPA-Adap │  │ Kafka-Adap │  │
│  └──────┬──────┘  └────┬─────┘  └─────┬──────┘  │
│         │ Port (In)    │ Port (Out)    │          │
│  ┌──────┴──────────────┴──────────────┴──────┐   │
│  │              Domain                        │   │
│  │  model/  service/  port/                  │   │
│  │  (kein @Inject, kein JPA, kein Kafka)     │   │
│  └────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

#### Die drei Schichten

**Domain** (`domain/model/`, `domain/service/`, `domain/port/`):
- Enthält die reine Geschäftslogik
- **Keine** Framework-Abhängigkeiten: kein `@Inject`, kein `@Entity`, kein `@Incoming`
- Vollständig unit-testbar ohne Container, ohne Datenbank, ohne Kafka

**Ports** (`domain/port/in/`, `domain/port/out/`):
- Java-Interfaces, die beschreiben was gebraucht wird
- `InvoiceRepository` ist ein Port – «ich brauche jemanden, der Rechnungen speichert»
- `BillingEventPublisher` ist ein Port – «ich brauche jemanden, der Events published»

**Infrastructure** (`infrastructure/persistence/`, `infrastructure/messaging/`, `infrastructure/api/`):
- Konkrete Implementierungen der Ports
- `JpaInvoiceRepository implements InvoiceRepository` – die JPA-Implementierung
- `KafkaOutboxEventPublisher implements BillingEventPublisher` – die Kafka-Implementierung
- Hier sind die Framework-Annotationen (`@ApplicationScoped`, `@Entity`, `@Incoming`)

> **Warum ist das wichtig?** Die Domain ist isoliert von technischen Entscheidungen. Man kann die Datenbank von PostgreSQL zu MongoDB wechseln, ohne eine einzige Zeile Domain-Code zu ändern. Tests für Geschäftslogik laufen in Millisekunden, weil sie keine Container brauchen.

### 2.3 Self-Contained Systems (SCS)

Ein **Self-Contained System** ist ein Service, der alles enthält, was er für seine Aufgabe braucht:

- **Eigene UI** – Jeder Service hat eine Qute/htmx-Benutzeroberfläche. Nutzer können direkt auf den Service zugreifen, ohne eine separate «Frontend-App» zu installieren.
- **Eigene Datenbank** – Jeder Service hat seine eigene PostgreSQL-Instanz. Kein Sharing, kein Cross-DB-JOIN.
- **Eigene Business-Logik** – Kein «gemeinsamer Service» für CRUD-Operationen.
- **Kommunikation nur über definierte Schnittstellen** – Kafka-Events oder REST (Ausnahmen).

Der Kontrast zu einem **Microservice**-Ansatz: Microservices sind oft sehr klein und brauchen viele andere Services für eine einzige Anfrage. SCS sind bewusst grösser und autonomer – ein SCS kann auch im Falle von Ausfällen anderer Services weiterarbeiten.

### 2.4 Data Mesh

**Data Mesh** ist ein Paradigmenwechsel in der Datenstrategie: Von einem zentralen Daten-Team, das ETL-Pipelines für alle Domains pflegt, hin zu verteilter Dateneigentümerschaft.

#### Die vier Prinzipien

**1. Domain-orientierte Dateneigentümerschaft**
Das Partner-Team besitzt nicht nur den Partner-Service, sondern auch die Daten, die der Partner-Service erzeugt. Das Team ist verantwortlich für Qualität, Schema-Stabilität und Dokumentation seiner Kafka-Topics.

**2. Daten als Produkt**
Jeder Kafka-Topic ist ein «Data Product» – mit klarer Beschreibung, SLA, Quality Score und Zugriffsmuster. Das Open Data Contract (ODC) ist der formale Ausdruck dieses Produkts.

**3. Self-Service Data Infrastructure**
Neue Teams sollen Daten einfach konsumieren können, ohne das erzeugende Team fragen zu müssen. Das Data Product Portal und der Schema Registry machen das möglich.

**4. Federated Computational Governance**
Governance-Regeln (Schema-Kompatibilität, ODC-Pflicht, Freshness-SLA) werden automatisiert überprüft. Kein manuelles «Daten-Gremium» entscheidet darüber, ob ein Topic berechtigt ist.

#### Data Inside vs. Data Outside

Ein fundamentaler Begriff in Data Mesh:

- **Data Inside**: Die primäre Datenbank eines Services (`partner_db`, `policy_db`, ...). Nur der eigene Service schreibt und liest hier. Streng privat – kein anderer Service darf zugreifen.
- **Data Outside**: Die Kafka-Topics eines Services. Das sind die offiziellen, öffentlich zugänglichen Datenprodukte. Andere Services und die Analytics-Plattform konsumieren ausschliesslich von hier.

```
partner-service
  Data Inside  → partner_db (PostgreSQL)   ← nur partner-service liest/schreibt
  Data Outside → person.v1.*  (Kafka)      ← alle anderen Services konsumieren
```

### 2.5 Architekturentscheidungen (ADRs)

Architecture Decision Records (ADRs) dokumentieren wichtige Entscheidungen und deren Begründung. Diese Plattform hat fünf bindende ADRs:

| ADR | Entscheidung | Begründung |
|---|---|---|
| **ADR-001** | Kafka ist der einzige Integrationskanal zwischen Domains | Vermeidet tight coupling; Domains können unabhängig skalieren und ausfallen |
| **ADR-002** | Jeder Kafka-Topic braucht ein ODC; Breaking Changes → neuer Topic-Name (`v2`) | Schützt Consumer vor unangekündigten Schema-Änderungen |
| **ADR-003** | REST nur für Deckungsprüfung (Claims→Policy) und IAM-Auth | Sync. REST schafft Abhängigkeiten; nur wo unvermeidbar erlaubt |
| **ADR-004** | Shared Nothing – kein Cross-Domain DB-Zugriff; Read Models aus Events | Verhindert versteckte Kopplung auf DB-Ebene |
| **ADR-005** | Code in Englisch, UI-Strings auf Deutsch, technische Doku auf Englisch | Codebase bleibt international lesbar; UI bedient deutschsprachige Nutzer |

---

## 3. Infrastruktur – Das Rückgrat der Plattform

### 3.1 Apache Kafka – Der Event-Bus

**Apache Kafka** ist das Herzstück der asynchronen Kommunikation. Es ist eine verteilte, persistente Log-Plattform – kein klassischer Message-Broker.

#### Was Kafka von einem klassischen Message-Broker unterscheidet

| Eigenschaft | Klassischer Broker (RabbitMQ) | Apache Kafka |
|---|---|---|
| Nachrichten nach Konsum | Gelöscht | **Behalten** (konfigurierbare Aufbewahrung) |
| Mehrere Consumer derselben Nachricht | Nicht möglich (Queue-Semantik) | **Möglich** (Topic mit Consumer Groups) |
| Consumer offline | Nachrichten verloren (ohne DLQ) | **Consumer liest nach Wiederherstellung weiter** |
| Neue Consumer, alte Daten | Nicht möglich | **Möglich** (Consumer kann von Anfang lesen) |
| Nachrichtenreihenfolge | Pro Queue | **Pro Partition garantiert** |

Diese Eigenschaften machen Kafka ideal für Event Sourcing und Event-Driven Architecture.

#### Topics, Partitionen, Offsets

Ein **Topic** ist ein benannter Log, vergleichbar mit einer Kafka-«Tabelle». Die Benennung folgt der Konvention:

```
{domain}.v{version}.{event-name}

Beispiele:
  person.v1.created      → Partner-Domain, Version 1, PersonCreated-Event
  policy.v1.issued       → Policy-Domain, Version 1, PolicyIssued-Event
  billing.v1.invoice-created
```

Ein Topic ist in **Partitionen** aufgeteilt. Nachrichten mit demselben **Key** landen immer in derselben Partition – und damit in garantierter Reihenfolge. In dieser Plattform ist der Key typischerweise die Entity-ID (z.B. `personId`, `policyId`).

Ein **Offset** ist die Position einer Nachricht innerhalb einer Partition. Consumer speichern ihren Fortschritt als Offset-Commit. Fällt ein Consumer aus und startet neu, liest er ab dem letzten committed Offset weiter – keine Nachrichten gehen verloren.

#### Consumer Groups

Eine **Consumer Group** ist eine Gruppe von Prozessen, die gemeinsam einen Topic konsumieren. Jede Partition wird von genau einem Consumer innerhalb der Group verarbeitet.

```
Topic: policy.v1.issued (3 Partitionen)

Consumer Group: billing-service
  Consumer A → Partition 0
  Consumer B → Partition 1
  Consumer C → Partition 2

Consumer Group: platform-consumer
  Consumer X → Partition 0, 1, 2 (ein Consumer, alle Partitionen)
```

Wenn `billing-service` ausfällt und 1000 neue `policy.v1.issued`-Events ankommen, warten diese in Kafka. Beim Neustart von billing-service werden alle 1000 Events der Reihe nach verarbeitet – kein Datenverlust.

#### Kompaktierte Topics (`*.state`)

Die `person.v1.state`- und `product.v1.state`-Topics sind **Log-kompaktiert**: Kafka behält für jeden Key (= Entity-ID) nur die **neueste** Nachricht. Ältere Versionen desselben Keys werden automatisch gelöscht.

```
person.v1.state (kompaktiert):
  Key: uuid-anna      → { name: "Anna Müller", address: "Hauptstrasse 1" }  (aktuell)
  Key: uuid-beat      → { name: "Beat Keller", address: "Seeweg 5" }        (aktuell)
  (alle älteren Einträge für dieselbe personId wurden kompaktiert weg)
```

Ein neuer Consumer, der `person.v1.state` liest, erhält sofort den **vollen aktuellen State** aller Personen – ohne die gesamte Mutationshistorie wiedergeben zu müssen. Das ist **Event-Carried State Transfer (ECST)** – mehr dazu in [Abschnitt 5.2](#52-event-carried-state-transfer-ecst).

#### KRaft-Modus (kein ZooKeeper)

Diese Plattform verwendet Kafka im **KRaft-Modus** (Kafka Raft Metadata). Ältere Kafka-Versionen brauchten Apache ZooKeeper für Cluster-Koordination. KRaft integriert die Metadata-Verwaltung direkt in Kafka – weniger Komponenten, einfacherer Betrieb.

### 3.2 Schema Registry – Vertrag für Datenformate

Die **Schema Registry** (Confluent) ist ein Dienst, der Avro-Schemas für alle Kafka-Topics speichert und versioniert.

#### Warum Schemas?

Ohne Schema-Kontrolle kann ein Producer morgen ein Feld umbenennen, und Consumer brechen ohne Vorwarnung. Die Schema Registry verhindert das:

1. **Producer** registriert ein Schema beim ersten Publish. Bei jedem weiteren Publish prüft die Registry: «Ist das neue Schema kompatibel mit dem bestehenden?»
2. **Consumer** fragen die Registry beim Deserialisieren: «Welches Schema hat diese Nachricht?»
3. **Breaking Change** (z.B. Pflichtfeld entfernt) → Registry **verweigert** die Registrierung → Producer-Deployment schlägt fehl.

#### Avro vs. JSON

Diese Plattform nutzt **Apache Avro** für alle Kafka-Schemas:

| Eigenschaft | JSON | Avro |
|---|---|---|
| Schema-Validierung | Optional | Erzwungen |
| Grösse | Gross (Feldnamen in jeder Nachricht) | Kompakt (nur Daten, Schema separat) |
| Null-Behandlung | Implizit | Explizit (union type) |
| Versionierung | Manuell | Schema Registry |
| Backward Compatibility | Manuell prüfen | Automatisch geprüft |

Ein Avro-Schema für `person.v1.created` sieht so aus:

```json
{
  "type": "record",
  "name": "PersonCreated",
  "namespace": "ch.yuno.partner",
  "fields": [
    { "name": "personId",   "type": "string" },
    { "name": "firstName",  "type": "string" },
    { "name": "familyName", "type": "string" },
    { "name": "birthDate",  "type": "string" },
    { "name": "eventAt",    "type": "string" }
  ]
}
```

#### Schema-Kompatibilitätsmodi

| Modus | Bedeutung | Erlaubte Änderungen |
|---|---|---|
| `BACKWARD` | Neue Consumer können alte Nachrichten lesen | Felder hinzufügen (optional), Felder entfernen |
| `FORWARD` | Alte Consumer können neue Nachrichten lesen | Felder hinzufügen (optional) |
| `FULL` | Beide Richtungen | Nur optionale Felder hinzufügen |
| `NONE` | Keine Prüfung | Alles erlaubt (gefährlich) |

Diese Plattform verwendet `BACKWARD` – der sicherste Modus für rolling deployments.

### 3.3 Debezium – Change Data Capture

**Debezium** ist ein CDC-Framework (Change Data Capture), das Datenbankänderungen in Echtzeit in Kafka-Events übersetzt.

#### Das Problem, das Debezium löst: Dual-Write

Eine naive Implementierung würde so aussehen:

```java
// FALSCH – Dual-Write-Problem
db.save(person);          // Schritt 1: DB speichern
kafka.publish(event);     // Schritt 2: Kafka publizieren
```

Was passiert, wenn nach Schritt 1 das System abstürzt? Die DB hat den neuen Zustand, Kafka nicht. **Inkonsistenz**. Dieses Muster ist gefährlich und schwer zu debuggen.

#### Die Lösung: Transactional Outbox + CDC

```java
// RICHTIG – Outbox Pattern
db.save(person);           // Schritt 1: Domainentität speichern
db.insertOutbox(event);    // Schritt 2: Event in Outbox-Tabelle (SELBE Transaktion)
// Kafka wird nicht direkt angesprochen
```

Beide Schritte 1 und 2 sind in **einer einzigen Datenbanktransaktion**: Entweder beide oder keiner. Debezium liest dann den **PostgreSQL Write-Ahead-Log (WAL)** und publiziert Outbox-Einträge nach Kafka – asynchron, aber zuverlässig.

#### WAL-Replikation

Der **Write-Ahead-Log (WAL)** ist ein PostgreSQL-Feature: Jede Schreiboperation wird zuerst im WAL protokolliert, bevor sie die eigentlichen Datenbankdateien ändert. Das garantiert Durability (ACID-D). Debezium liest diesen WAL über das **Logical Replication Protocol** – ein stabiles, produktionserprobtes Interface.

```
PostgreSQL WAL → Debezium Connector → Kafka Topic
  (partner_db)    (partner-outbox)     (person.v1.created)
```

#### Aktive Connectoren

In dieser Plattform laufen drei Debezium-Connectoren:

| Connector | Quell-DB | Ziel-Topics |
|---|---|---|
| `partner-outbox-connector` | `partner_db` | `person.v1.*` |
| `product-outbox-connector` | `product_db` | `product.v1.*` |
| `billing-outbox-connector` | `billing_db` | `billing.v1.*` |

> **Warum nicht auch Policy?** Der Policy Service schreibt Events **direkt** via Quarkus SmallRye Reactive Messaging in Kafka – innerhalb einer Quarkus-Transaktion, die Outbox-Schreibung und Kafka-Publish koordiniert. Das ist auch ein valider Ansatz, wenn man Quarkus-Transaktionen kontrolliert.

### 3.4 AKHQ – Kafka beobachten

**AKHQ** (Another Kafka HQ) ist das Web-UI für Kafka-Administration und Debugging. Es ist kein Teil des Datenflusses, sondern reines Beobachtungswerkzeug.

Nützliche Features:
- **Topics**: Alle Topics mit Nachrichtenanzahl, Partitionsverteilung, Retention
- **Messages**: Einzelne Kafka-Nachrichten lesen, Avro-deserialisiert
- **Consumer Groups**: Lag pro Partition – wie weit ist ein Consumer hinter den neuesten Events?
- **Schema Registry**: Alle registrierten Schemas mit Versionsverlauf
- **Live Tail**: Nachrichten in Echtzeit beobachten, während sie eintreffen

---

## 4. Domain Services

Alle Domain Services folgen derselben technischen Grundstruktur:

```
{domain}/
├── domain/
│   ├── model/          ← Aggregates, Entities, Value Objects (pure Java)
│   ├── service/        ← Application Services (Orchestrierung)
│   └── port/
│       ├── in/         ← Use Case Ports (Command/Query Interfaces)
│       └── out/        ← RepositoryPort, EventPublisherPort
└── infrastructure/
    ├── persistence/    ← JPA-Entitäten, Spring-Data-Repositories
    ├── messaging/      ← Kafka-Producer/Consumer
    ├── api/            ← REST-Endpoints
    └── web/            ← Qute-Templates + htmx-Controller
```

### 4.1 Partner Service – Personenverwaltung

**URL:** http://localhost:9080 · **Datenbank:** `partner_db` :5432

Der Partner Service ist das **Stammdaten-System für natürliche Personen** in der Plattform. Er ist der einzige Service, der Personendaten schreiben darf (Datensouveränität).

#### Domainmodell

```
Person (Aggregate Root)
  ├── personId: UUID
  ├── firstName, familyName, gender, birthDate
  ├── socialSecurityNumber (AHV-Nummer, unveränderlich)
  └── addresses: List<Address>
        ├── addressId: UUID
        ├── street, postalCode, city, country
        ├── addressType: MAIN | POSTAL | BUSINESS
        └── validFrom, validTo (zeitliche Versionierung)
```

#### Zeitliche Adress-Versionierung

Adressen sind zeitlich versioniert: Wird eine neue Adresse desselben Typs mit einem späteren Gültigkeitsdatum erfasst, setzt das System die `validTo` der bestehenden Adresse auf den Vortag. So bleibt die vollständige Historie erhalten – relevant für DSGVO-Auskunftsanfragen und Audit.

#### Ereignisfluss (Partner)

```
UI/REST → PersonCommandService
  → person.save() + outbox.save()   [eine DB-Transaktion]
    → Debezium CDC (WAL)
      → person.v1.created   (Delta-Event)
      → person.v1.state     (kompaktierter State – UPSERT per personId)
```

Jede Mutation erzeugt zwei Events:
- **Delta-Event** (`person.v1.created`, `person.v1.updated`): Was hat sich geändert?
- **State-Event** (`person.v1.state`): Wie sieht die Person jetzt vollständig aus?

### 4.2 Product Service – Produktverwaltung

**URL:** http://localhost:9081 · **Datenbank:** `product_db` :5433

Der Product Service verwaltet den **Versicherungsprodukt-Katalog**: Welche Produkte werden angeboten, welche Deckungstypen sind verfügbar, und zu welchen Basisprämien.

#### Domainmodell

```
Product (Aggregate Root)
  ├── productId: UUID
  ├── name, productLine (HOUSEHOLD / MOTOR / LIABILITY / ...)
  ├── basePremium: BigDecimal
  ├── status: ACTIVE | DEPRECATED
  └── coverageTypes: List<CoverageType>
        ├── coverageTypeId: UUID
        ├── name (FIRE / WATER / THEFT / ...)
        └── description
```

#### Warum ein eigener Service?

Produktdefinitionen ändern sich selten, aber wenn sie sich ändern, müssen alle betroffenen Policen informiert werden. Durch einen eigenen Service und eigene Events können der Policy Service und andere Konsumenten automatisch ihre Read Models aktualisieren – ohne direkten DB-Zugriff.

#### Lebenszyklus eines Produkts

```
ACTIVE → (Underwriter deprecated) → DEPRECATED
```

Ein **DEPRECATED**-Produkt kann nicht mehr für neue Policen verwendet werden. Bestehende Policen auf diesem Produkt bleiben davon unberührt – der Policy Service hat eine lokale Kopie der Produktdaten.

### 4.3 Policy Service – Policenverwaltung

**URL:** http://localhost:9082 · **Datenbank:** `policy_db` :5434

Der Policy Service ist der **fachliche Kern** der Plattform. Er verwaltet den Lebenszyklus eines Versicherungsvertrags.

#### Domainmodell

```
Policy (Aggregate Root)
  ├── policyId: UUID
  ├── policyNumber: String  (POL-YYYY-NNNNN)
  ├── status: ENTWURF | AKTIV | GEKÜNDIGT | ABGELAUFEN
  ├── partnerId, partnerName (Read Model aus person.v1.state)
  ├── productId, productName (Read Model aus product.v1.state)
  ├── annualPremium: BigDecimal
  ├── coverageStartDate, coverageEndDate
  └── coverages: List<Coverage>
        ├── coverageId: UUID
        ├── coverageType (FIRE / WATER / THEFT / ...)
        ├── insuredAmount: BigDecimal
        └── deductible: BigDecimal
```

#### Lokale Read Models (Anti-Corruption Layer)

Der Policy Service braucht Partner- und Produktdaten – aber er ruft den Partner- oder Product-Service **nicht synchron an**. Stattdessen konsumiert er Kafka-Events und baut lokale Tabellen auf:

```
person.v1.state → PartnerStateConsumer → partner_view-Tabelle in policy_db
product.v1.state → ProductStateConsumer → product_view-Tabelle in policy_db
```

Beim Anlegen einer neuen Police sucht der UI-Controller in diesen lokalen Tabellen – ohne Netzwerkanfrage an externe Services. Das macht den Policy Service **resilient**: Er funktioniert auch wenn Partner- oder Product-Service gerade nicht erreichbar ist.

#### Policen-Lebenszyklus

```
[Neu] → ENTWURF
  → [Aktivieren] → AKTIV       (publiziert: policy.v1.issued)
    → [Kündigen] → GEKÜNDIGT   (publiziert: policy.v1.cancelled)
    → [Ablaufen] → ABGELAUFEN
```

#### Direktes Kafka-Publishing (ohne Debezium)

Der Policy Service publiziert Events **direkt** über Quarkus SmallRye Reactive Messaging – innerhalb einer Quarkus-verwalteten Transaktion:

```java
@Transactional
public void activatePolicy(UUID policyId) {
    Policy policy = policyRepository.findById(policyId);
    policy.activate();
    policyRepository.save(policy);
    eventPublisher.publishPolicyIssued(policy);  // In selber Transaktion
}
```

Quarkus stellt sicher, dass Event-Publish und DB-Save atomar sind (Quarkus Reactive Messaging mit `@Transactional`).

### 4.4 Billing Service – Fakturierung & Mahnwesen

**URL:** http://localhost:9084 · **Datenbank:** `billing_db` :5436

Der Billing Service ist der **Finanz-Lebenszyklus-Service** – er verwaltet alle monetären Transaktionen rund um Versicherungsverträge.

#### Domainmodell

```
Invoice (Aggregate Root)   ← Rechnung
  ├── invoiceId: UUID
  ├── invoiceNumber: String  (BILL-YYYY-NNNNN)
  ├── policyId, policyNumber
  ├── partnerId
  ├── status: OPEN | OVERDUE | PAID | CANCELLED
  ├── billingCycle: ANNUAL | SEMI_ANNUAL | QUARTERLY | MONTHLY
  ├── totalAmount: BigDecimal  (= annualPremium / installmentsPerYear)
  ├── invoiceDate, dueDate
  ├── paidAt, cancelledAt
  └── lineItems: List<InvoiceLineItem>

DunningCase (Aggregate Root)   ← Mahnfall
  ├── dunningCaseId: UUID
  ├── invoiceId
  ├── level: REMINDER → FIRST_WARNING → FINAL_WARNING → COLLECTION
  ├── initiatedAt
  └── escalatedAt
```

#### Prämienaufteilung (BillingCycle)

Die Jahresprämie wird gemäss Zahlungsrhythmus aufgeteilt:

| BillingCycle | installmentsPerYear | Betrag (bei CHF 480/Jahr) |
|---|---|---|
| ANNUAL | 1 | CHF 480.00 |
| SEMI_ANNUAL | 2 | CHF 240.00 |
| QUARTERLY | 4 | CHF 120.00 |
| MONTHLY | 12 | CHF 40.00 |

#### Reaktion auf Policy-Events

```
policy.v1.issued → PolicyEventConsumer → InvoiceCommandService
  → createInvoiceForPolicy(policyId, premium, billingCycle=ANNUAL)
    → Invoice-Aggregat erstellen (OPEN)
    → invoice + outbox in billing_db speichern
      → billing.v1.invoice-created

policy.v1.cancelled → PolicyEventConsumer → InvoiceCommandService
  → cancelInvoicesForPolicy(policyId)
    → Alle OPEN/OVERDUE-Rechnungen → CANCELLED
      → billing.v1.invoice-created (Status-Update)
```

#### Hibernate Envers – Finanzieller Audit-Trail

Alle Mutationen an `InvoiceEntity` werden durch **Hibernate Envers** in einer separaten `invoice_aud`-Tabelle protokolliert:

```sql
-- invoice_aud enthält für jede Invoice-Mutation:
invoice_id | status | total_amount | rev  | revtype
uuid-abc   | OPEN   | 480.00       | 1    | 0       (INSERT)
uuid-abc   | PAID   | 480.00       | 2    | 1       (UPDATE)
```

`rev` verweist auf `revinfo`, die den Zeitstempel der Revision enthält. So kann für jede Rechnung lückenlos nachgewiesen werden, wer wann was geändert hat – ein Muss in der regulierten Versicherungswelt.

### 4.5 Claims Service – Schadenbearbeitung (Stub)

**URL:** http://localhost:9083 · **Status:** Domainmodell + REST-Skeleton, keine Persistenz/Kafka

Der Claims Service zeigt die **geplante Struktur** der Schadenbearbeitung. Er hat:
- Domainmodell (`Claim`, `ClaimStatus`, `FnolData`)
- REST-Endpoints für FNOL (First Notice of Loss) und Statusabfragen
- Einen synchronen REST-Call zu Policy-Service für Deckungsprüfung (ADR-003-Ausnahme)
- **Keine** echte Persistenz (in-memory)
- **Kein** Kafka-Publishing

Beim produktiven Ausbau würde der Claims Service:
1. Schadensfälle in `claims_db` persistieren
2. `claims.v1.opened` publizieren (→ Billing für Reservierung)
3. `claims.v1.settled` publizieren (→ Billing für Auszahlung)

---

## 5. Schlüsselmuster der Event-Architektur

### 5.1 Transactional Outbox Pattern

Das **Transactional Outbox Pattern** löst das Dual-Write-Problem auf elegante Weise.

#### Das Problem

```
Naiver Ansatz (FALSCH):
  db.save(entity)         ← DB-Commit
  kafka.publish(event)    ← Kann fehlschlagen!

Was bei Fehler passiert:
  DB: Entität gespeichert ✓
  Kafka: Event nicht publiziert ✗
  → Downstream-Services wissen nichts von der Änderung
```

#### Die Lösung

```
Outbox Pattern (RICHTIG):
  TRANSACTION START
    db.save(entity)        ← Entität in domain-Tabelle
    db.save(outboxEntry)   ← Event in outbox-Tabelle
  TRANSACTION COMMIT

  [asynchron, Debezium]
    WAL-Änderung lesen → kafka.publish(event)
```

Die `outbox`-Tabelle enthält:
```sql
CREATE TABLE outbox (
  id          UUID PRIMARY KEY,
  aggregate_type VARCHAR(64),    -- z.B. "Invoice"
  aggregate_id   VARCHAR(36),    -- z.B. invoice_id
  topic          VARCHAR(128),   -- z.B. "billing.v1.invoice-created"
  payload        JSONB,          -- Event-Payload
  created_at     TIMESTAMPTZ
);
```

Debezium liest neue Zeilen aus der `outbox`-Tabelle (via WAL) und routet sie zum konfigurierten Kafka-Topic. Nach dem Publish markiert Debezium den Offset – nie denselben Eintrag zweimal.

> **At-Least-Once Delivery**: Im Fehlerfall kann Debezium dasselbe Event zweimal publizieren. Consumer müssen **idempotent** sein – dasselbe Event zweimal verarbeiten muss zum gleichen Ergebnis führen. In dieser Plattform wird das durch `ON CONFLICT DO NOTHING` in der Platform-Consumer-Datenbank und durch Primärschlüssel-Checks in der Domain-Logik sichergestellt.

### 5.2 Event-Carried State Transfer (ECST)

**ECST** ist ein Muster, bei dem ein Service seinen vollständigen Zustand als Kafka-Event publiziert – nicht nur die Delta-Änderung.

#### Delta-Events vs. State-Events

```
Delta-Events (person.v1.created, person.v1.updated):
  → Was hat sich geändert?
  → Consumer muss History zusammenbauen, wenn er den vollen Zustand braucht
  → Gut für: Ereignisprotokoll, Audit, Reaktionen auf spezifische Änderungen

State-Events (person.v1.state – kompaktierter Topic):
  → Wie sieht die Entität jetzt vollständig aus?
  → Consumer bekommt sofort den aktuellen Zustand ohne History
  → Gut für: Read Models, neue Services die «sofort loslegen» wollen
```

#### Wie der Billing Service ECST nutzt

```
person.v1.state → PartnerStateConsumer → policyholder_view (in billing_db)

SQL (vereinfacht):
  INSERT INTO policyholder_view (person_id, first_name, family_name, ...)
  ON CONFLICT (person_id) DO UPDATE SET
    first_name = EXCLUDED.first_name,
    family_name = EXCLUDED.family_name,
    ...
```

Der Billing Service braucht Adressdaten des Versicherungsnehmers (z.B. für Rechnungsversand). Statt den Partner Service synchron anzufragen, liest er aus seiner lokalen `policyholder_view` – die durch ECST immer aktuell ist.

#### Neuer Service startet

Ein grosser Vorteil von ECST: Wenn ein neuer Service gestartet wird, liest er `person.v1.state` von Anfang an – und erhält sofort den **aktuellen Stand aller Personen**, ohne die gesamte Mutations-History (`person.v1.created`, `person.v1.updated`) wiedergeben zu müssen.

```
Neuer Service startet:
  person.v1.state lesen (kompaktierter Topic)
  → Nur neueste Version per personId
  → Nach wenigen Sekunden: lokale DB = aktueller Zustand aller Personen
```

### 5.3 Consumer-seitige Read Models

**Read Models** sind lokale Kopien von Daten anderer Domains, aufgebaut aus konsumierten Kafka-Events.

#### Anti-Corruption Layer (ACL)

Der **Anti-Corruption Layer** ist die Schicht, die fremde Event-Formate in das eigene Domainmodell übersetzt. Ohne ACL würde das Domainmodell eines Services direkt von den Datenstrukturen anderer Domains abhängen.

```java
// PolicyEventTranslator (Anti-Corruption Layer im Billing Service)
public record PolicyIssuedData(
    String policyId,
    String policyNumber,
    String partnerId,
    BigDecimal premium) {}

public Optional<PolicyIssuedData> translatePolicyIssued(String rawJson) {
    // JSON parsen und in eigenes Format übersetzen
    // Fehlerhafte Events werden abgefangen, nicht weiterpropagiert
}
```

Wenn der Policy Service sein Event-Format ändert (z.B. ein Feld umbenennt), muss **nur der ACL** angepasst werden – das Domainmodell des Billing Service bleibt unberührt.

### 5.4 Dead Letter Queue (DLQ)

Eine **Dead Letter Queue** fängt Events auf, die nach mehreren Verarbeitungsversuchen fehlschlagen.

In dieser Plattform gibt es DLQ-Topics für die wichtigsten Billing-Eingaben:

```
policy.v1.issued → Billing Consumer → Fehler nach 3 Versuchen → policy.v1.issued.dlq
```

DLQ-Events können manuell inspiziert, korrigiert und re-published werden. Ohne DLQ würde ein «giftiges» Event den Consumer dauerhaft blockieren – kein weiteres Event würde verarbeitet.

---

## 6. Analytics Platform – Data Outside

Die Analytics Platform demonstriert, wie Data Mesh **domainübergreifende Analysen** ermöglicht, ohne die Datensouveränität der einzelnen Domains zu verletzen.

```
Domains          Data Outside         Analytics
──────────        ────────────         ─────────
partner ─────────► person.v1.*    ─┐
product ─────────► product.v1.*  ─┼──► Platform Consumer ──► platform_db
policy  ─────────► policy.v1.*   ─┤       ↓
billing ─────────► billing.v1.*  ─┘    dbt (SQL-Transformation)
                                         ↓
                                    Mart-Modelle
                                         ↓
                                    Data Portal / Airflow
```

Kritisch: Die Analytics Platform **liest nie direkt** aus `partner_db`, `policy_db` etc. Sie konsumiert ausschliesslich die offiziellen Kafka-Topics (Data Outside).

### 6.1 Platform Consumer – Landing Zone

Der **Platform Consumer** ist ein Python-Service, der alle Domain-Events aus Kafka in die `platform_db` schreibt – unverändert («raw»).

```python
# Vereinfachte Logik
for message in kafka_consumer:
    db.execute("""
        INSERT INTO partner_raw.person_events
          (event_id, event_type, payload, consumed_at)
        VALUES (%s, %s, %s, NOW())
        ON CONFLICT (event_id) DO NOTHING
    """, [message.id, message.type, message.value])
```

Wichtige Designentscheidungen:
- **ON CONFLICT DO NOTHING**: Idempotent – dasselbe Event zweimal verarbeiten schadet nicht (at-least-once)
- **Unverändert speichern**: Keine Transformation hier. Die rohen Events werden so gespeichert, wie sie aus Kafka kommen – einschliesslich aller Fehlern. dbt transformiert erst nachgelagert.
- **Separate Schemata pro Domain**: `partner_raw`, `product_raw`, `policy_raw`, `billing_raw` – klare Eigentümerschaft, keine Namenskonflikte

**State-Topic (ECST) = UPSERT:**

```python
# Für kompaktierte State-Topics: UPSERT statt INSERT
db.execute("""
    INSERT INTO partner_raw.person_state (person_id, payload, updated_at)
    VALUES (%s, %s, NOW())
    ON CONFLICT (person_id) DO UPDATE SET
        payload = EXCLUDED.payload,
        updated_at = NOW()
""", [key, value])
```

### 6.2 dbt – Transformation in Schichten

**dbt** (data build tool) führt SQL-Transformationen in `platform_db` durch und baut saubere Analysemodelle aus den rohen Event-Daten.

#### Schichten-Architektur

```
platform_db
├── partner_raw.person_events        ← Platform Consumer (raw JSON)
├── product_raw.product_events
├── policy_raw.policy_events
├── billing_raw.billing_events
│
├── analytics.stg_person_events      ← dbt Staging (VIEWS – JSON → Spalten)
├── analytics.stg_product_events
├── analytics.stg_policy_events
├── analytics.stg_billing_events
│
└── analytics.                       ← dbt Marts (TABLES – aggregiert, joinbar)
    ├── dim_partner                  Dimension: aktueller Zustand aller Personen
    ├── dim_product                  Dimension: aktueller Produktkatalog
    ├── fact_policies                Fakt: eine Zeile pro Police, aktueller Status
    ├── fact_invoices                Fakt: eine Zeile pro Rechnung
    ├── mart_portfolio_summary       Aggregat: Policen pro Produktlinie
    ├── mart_financial_summary       Aggregat: Finanzstatus (Policy + Billing Join)
    └── mart_management_kpi          KPI-Dashboard-Daten
```

#### Staging Layer – JSON parsen

Rohe Event-Payloads sind JSONB. Der Staging Layer extrahiert typisierte Spalten:

```sql
-- stg_policy_events.sql (vereinfacht)
SELECT
  payload->>'policyId'     AS policy_id,
  payload->>'policyNumber' AS policy_number,
  payload->>'partnerId'    AS partner_id,
  payload->>'productId'    AS product_id,
  (payload->>'annualPremium')::numeric AS annual_premium,
  event_type,
  event_at
FROM policy_raw.policy_events
```

#### Mart Layer – Business-Logik

```sql
-- fact_policies.sql (vereinfacht)
-- Last-Write-Wins: neueste Event-Version pro Policy ist der aktuelle Status
WITH ranked AS (
  SELECT *,
    ROW_NUMBER() OVER (
      PARTITION BY policy_id
      ORDER BY event_at DESC
    ) AS rn
  FROM analytics.stg_policy_events
)
SELECT
  policy_id,
  policy_number,
  CASE
    WHEN event_type = 'PolicyCancelled' THEN 'CANCELLED'
    ELSE 'ACTIVE'
  END AS status,
  annual_premium,
  first_issued_at
FROM ranked
WHERE rn = 1
```

#### Cross-Domain Mart – das Data-Mesh-Versprechen

```sql
-- mart_financial_summary.sql
-- JOIN über Domaingrenzen – möglich weil beide Domains ihre Daten als Events geteilt haben
SELECT
  p.policy_number,
  p.annual_premium,
  i.total_amount,
  i.status AS invoice_status,
  CASE
    WHEN i.status = 'PAID' THEN 'CURRENT'
    WHEN i.status = 'OVERDUE' THEN 'AT_RISK'
    ELSE 'PENDING'
  END AS collection_status
FROM analytics.fact_policies p
LEFT JOIN analytics.fact_invoices i ON p.policy_id = i.policy_id
```

Dieser JOIN war in einer klassischen Architektur schwierig: Policy und Billing hatten getrennte Datenbanken, und ein zentrales DWH brauchte Monate Aufwand für ETL-Pipelines. Im Data-Mesh-Ansatz ist es ein einfacher SQL-JOIN – weil beide Domains ihre Daten als saubere Kafka-Events publiziert haben.

### 6.3 Spark Structured Streaming

**Apache Spark Structured Streaming** ergänzt dbt für komplexe, zustandsbehaftete Analysen:

| Anwendungsfall | dbt | Spark |
|---|---|---|
| Tabellarische SQL-Transformationen | ✓ | möglich, aber overkill |
| Zeitfenster-Aggregationen (Rolling 7-Tage) | schwierig | ✓ |
| Stateful Stream Processing | nicht möglich | ✓ |
| Machine-Learning-Features | nicht möglich | ✓ (MLlib) |
| Fraudmuster-Erkennung | nicht möglich | ✓ |

Spark liest direkt aus Kafka (kein Umweg über Platform Consumer) und schreibt Ergebnisse in `platform_db` oder ein Spark Delta Lake Warehouse.

### 6.4 Apache Airflow – Orchestrierung

**Apache Airflow** orchestriert die Ausführung von dbt-Modellen und Qualitätsprüfungen.

#### Das Problem ohne Airflow

Ohne Orchestrierung läuft dbt nur einmal beim Container-Start. Daten werden veraltet. In der Produktion müssen Transformationen regelmässig, verlässlich und überwacht ausgeführt werden.

#### DAG – Directed Acyclic Graph

Ein DAG beschreibt die Ausführungsreihenfolge und -abhängigkeiten von Tasks:

```
dbt_daily_run DAG:
  check_platform_consumer_healthy
    → dbt_run_staging          (erst Staging-Modelle)
      → dbt_run_marts          (dann Mart-Modelle, die auf Staging basieren)
        → data_quality_check   (Qualitätsprüfung nach erfolgreicher Transformation)
          → notify_on_failure  (Alert wenn Qualität nicht erfüllt)
```

Airflow überwacht jeden Task, loggt stdout/stderr, ermöglicht manuelle Neuauslösung bei Fehlern und schickt Alerts.

---

## 7. Data Governance – Vertrauen durch Kontrolle

### 7.1 Open Data Contract (ODC)

Ein **Open Data Contract** ist ein YAML-Dokument, das einen Kafka-Topic als Datenprodukt formal beschreibt. Jeder Topic in dieser Plattform hat ein ODC.

#### Inhalt eines ODC

```yaml
# billing.v1.invoice-created.odcontract.yaml (vereinfacht)

apiVersion: v2.2.2
kind: DataContract
id: billing.v1.invoice-created

info:
  title: Invoice Created
  version: 1.0.0
  description: Published when a new billing invoice is created for a policy premium
  owner: billing-team@css.ch
  domain: billing

servers:
  kafka:
    type: kafka
    url: kafka:29092
    topic: billing.v1.invoice-created

terms:
  sla:
    freshness: PT5M         # Daten nicht älter als 5 Minuten
    availability: "99.5%"  # Verfügbarkeit
    qualityScore: "98%"    # Mindest-Quality-Score

schema:
  type: avro
  path: billing.v1.invoice-created.avsc

quality:
  type: SodaCL
  specification:
    checks for billing_raw.billing_events:
      - missing_count(invoice_id) = 0    # invoice_id nie null
      - duplicate_count(event_id) = 0    # event_id eindeutig
```

#### Warum ODCs?

Ohne formale Kontrakte:
- Consumer wissen nicht, welche Felder garantiert vorhanden sind
- Producer können Felder umbenennen ohne Consumer zu informieren
- Qualitätsprobleme werden erst beim Consumer-Team sichtbar, nicht beim Produzenten-Team

Mit ODCs:
- Schema ist **versioniert und registriert** (Schema Registry)
- Qualitätsregeln sind **automatisch überprüfbar** (SodaCL)
- SLA ist **messbar** (Freshness-Check)
- Ownership ist **klar dokumentiert** (owner-Feld)

### 7.2 Governance-Container – Automatisierte Qualitätstore

Der **Governance-Container** läuft beim Stack-Start einmalig und prüft drei Dinge:

#### 1. ODC-Linting (`lint-contracts.py`)

Prüft jede `*.odcontract.yaml`-Datei auf Pflichtfelder:

| Pflichtfeld | Warum |
|---|---|
| `owner` | Wer ist verantwortlich? |
| `domain` | Welchem Team gehört das Produkt? |
| `outputPort` | Wie wird konsumiert (Kafka, REST...)? |
| `sla.freshness` | Wie aktuell müssen die Daten sein? |
| `sla.availability` | Wie zuverlässig ist das Produkt? |
| `sla.qualityScore` | Welcher Mindest-Quality-Score gilt? |
| `tags` | Metadaten für Discovery |

Schlägt ein Check fehl, bricht der Container mit Exit Code 1 ab – und `podman compose up` startet nicht komplett. Das ist **Governance as Code**: Unvollständige Kontrakte verhindern das Deployment.

#### 2. Schema-Kompatibilitätsprüfung (`schema-compat-check.sh`)

Lädt alle aktuellen Avro-Schemas aus der Schema Registry und prüft, ob sie kompatibel mit der vorherigen Version sind:

```bash
# Vereinfacht:
for schema in $(schema-registry list-subjects); do
  latest=$(schema-registry get-schema $schema --version latest)
  previous=$(schema-registry get-schema $schema --version latest-1)
  if ! is-compatible $latest $previous; then
    echo "BREAKING CHANGE: $schema"
    exit 1
  fi
done
```

#### 3. Freshness-Check (`check-freshness.py`)

Prüft, ob in letzter Zeit Events auf den erwarteten Topics angekommen sind. Wenn `person.v1.created` seit 6 Stunden keine neuen Einträge hat, obwohl laut ODC die Freshness 5 Minuten beträgt, ist das ein SLA-Verletzungs-Alert.

### 7.3 Data Product Portal

**URL:** http://localhost:8090

Das **Data Product Portal** ist die Self-Service-Oberfläche für Datenkonsumenten. Es aggregiert Informationen aus drei Quellen:

- **ODC-Contracts** (YAML-Dateien): Beschreibungen, Owner, SLAs
- **Schema Registry**: Registrierte Avro-Schemas
- **platform_db**: Aktuelle Analytics-Daten

#### Was das Portal zeigt

| Seite | Inhalt |
|---|---|
| **Katalog** (/) | Alle Data Products mit Quality Score, Schema-Status, Domain-Filter |
| **Demo** (/demo) | Live-Abfrage der `mart_portfolio_summary` |
| **Governance** (/governance) | Schema Registry Status, ODC Quality Scores, Architekturregeln |

---

## 8. DataHub – Unternehmensweiter Metadaten-Katalog

**URL:** http://localhost:9002 · **Login:** `datahub` / `datahub`

**DataHub** ist eine Open-Source-Plattform für Enterprise Data Discovery. Im Gegensatz zum projektspezifischen Data Product Portal ist DataHub eine vollwertige, skalierbare Lösung für grössere Organisationen.

#### Was DataHub von unserem Portal unterscheidet

| Feature | Data Product Portal | DataHub |
|---|---|---|
| Schema-Extraktion | Manuell via ODC | **Automatisch** aus Schema Registry |
| Zugriffskontrollen | Keine | **Fein granular** (Rolle, Team, Entity-Typ) |
| Suchindex | Einfach | **Elasticsearch-powered** |
| Integrationen | ODC + Schema Registry | Kafka, dbt, Airflow, Spark, S3, ... |
| Setup-Aufwand | Leicht | Komplex (mehrere Container) |

#### DataHub-Ingestion

Beim Stack-Start führt `datahub-ingest` automatisch Ingestion-Recipes aus:

1. **Kafka-Ingestion**: Liest alle Topic-Namen und Avro-Schemas aus der Schema Registry → erstellt DataSet-Entitäten in DataHub
2. **ODC-Ingestion**: Liest ODC YAML-Dateien und reichert DataSet-Entitäten mit Beschreibungen, Owner, Tags und SLAs an

```python
# datahub-ingest script (vereinfacht)
kafka_source = KafkaSource(bootstrap_servers="kafka:29092",
                           schema_registry_url="http://schema-registry:8081")
for topic, schema in kafka_source.get_topics_and_schemas():
    datahub.emit_dataset(topic, schema)

for contract in glob("**/contracts/*.odcontract.yaml"):
    odc = parse_odc(contract)
    datahub.add_metadata(odc.topic, owner=odc.owner, tags=odc.tags, ...)
```

#### Typischer DataHub-Workflow

1. **Discovery**: Suche nach «invoice» → alle billing-Topics erscheinen mit Beschreibung
2. **Schema**: Tab «Schema» eines Topics → alle Avro-Felder mit Typen
4. **Ownership**: Wer owned diesen Dataset? (Owner-Feld aus ODC)

---

## 9. End-to-End Durchlauf – Ein Geschäftsvorfall durch alle Systeme

Dieser Abschnitt beschreibt, was **technisch** passiert, wenn eine Hausratsversicherungs-Police für Anna Müller angelegt und aktiviert wird.

### Phase 1 – Person erfassen

```
[Nutzer] → HTTP POST /api/persons
  → REST-Adapter (Quarkus)
    → PersonCommandService.createPerson(command)
      → Person-Aggregat erstellen (personId = UUID.randomUUID())
        → PersonRepository.save(person)     ← JPA in partner_db
        → OutboxRepository.save(outboxEntry) ← SELBE Transaktion
      COMMIT

[Debezium, async, ~100ms]
  → WAL-Änderung lesen (outbox-Tabelle in partner_db)
    → kafka.publish(topic="person.v1.created", key=personId, value=event)
    → kafka.publish(topic="person.v1.state",   key=personId, value=fullState)
```

**Datenbanken nach Phase 1:**
- `partner_db.persons`: Neue Zeile mit Anna Müller
- `partner_db.outbox`: Eintrag (wird nach CDC-Publish gelöscht/markiert)
- `person.v1.created`: Neues Event in Kafka
- `person.v1.state`: State von Anna Müller (kompaktiert, Key = personId)

### Phase 2 – Read Models aktualisieren (async)

Kafka-Events aus Phase 1 werden von mehreren Consumern parallel verarbeitet:

```
person.v1.state → policy-service (Consumer Group: policy-service)
  → PartnerStateConsumer.process(event)
    → PartnerView upserten in policy_db

person.v1.state → billing-service (Consumer Group: billing-service)
  → PartnerStateConsumer.process(event)
    → PolicyholderView upserten in billing_db

person.v1.created → platform-consumer (Consumer Group: platform-consumer)
  → INSERT INTO partner_raw.person_events (event_id, payload, consumed_at)
```

**Datenbanken nach Phase 2:**
- `policy_db.partner_view`: Anna Müller eingetragen → erscheint jetzt in Policy-Suche
- `billing_db.policyholder_view`: Anna Müller eingetragen
- `platform_db.partner_raw.person_events`: Roher Event gespeichert

### Phase 3 – Produkt anlegen und Police erstellen

Analog zu Phase 1 für `product.v1.defined`. Nach Policy-Erstellung im ENTWURF-Status:

```
[Nutzer] → POST /api/policies/{id}/activate
  → PolicyCommandService.activatePolicy(policyId)
    → Policy-Aggregat.activate()        ← Invarianten prüfen (muss ENTWURF sein)
      → PolicyRepository.save(policy)   ← JPA in policy_db
        → EventPublisher.publishPolicyIssued(policy)  ← Quarkus @Transactional
          → kafka.publish("policy.v1.issued", policyId, event)
      COMMIT
```

### Phase 4 – Billing erstellt automatisch Rechnung

```
policy.v1.issued → billing-service (Consumer Group: billing-service)
  → PolicyEventConsumer.onPolicyIssued(event)
    → PolicyEventTranslator.translate(event)  ← Anti-Corruption Layer
      → InvoiceCommandService.createInvoiceForPolicy(policyId, premium=480)
        → Invoice-Aggregat erstellen (BILL-2026-00001, status=OPEN, amount=480.00)
          → InvoiceRepository.save(invoice)
          → OutboxRepository.save(outboxEntry)
        COMMIT

[Debezium billing-outbox-connector, async, ~100ms]
  → billing.v1.invoice-created publizieren

[Hibernate Envers, synchron]
  → invoice_aud: INSERT (rev=1, status=OPEN)
```

### Phase 5 – Analytics Platform verarbeitet Events

```
policy.v1.issued → platform-consumer
  → INSERT INTO policy_raw.policy_events

billing.v1.invoice-created → platform-consumer
  → INSERT INTO billing_raw.billing_events

[dbt, nächster Scheduled Run via Airflow]
  → dbt run --models stg_policy_events fact_policies mart_portfolio_summary
    → analytics.fact_policies: neue Zeile (POL-2026-000001, status=ACTIVE, premium=480)
    → analytics.fact_invoices: neue Zeile (BILL-2026-00001, status=OPEN, amount=480)
    → analytics.mart_financial_summary: neuer Eintrag (ACTIVE + PENDING)
```

### Phase 6 – Sichtbar im Portal

```
[Analyst] → http://localhost:8090/demo
  → SELECT * FROM analytics.mart_portfolio_summary
    → HOUSEHOLD | Hausrat Basic | 1 Police | CHF 480 | CHF 480
```

### Zusammenfassung: Welche Systeme interagieren?

```
Nutzer-UI → partner/product/policy/billing-service → Kafka → [alle Consumer]
                                                            ↓
                                               policy-service (PartnerView)
                                               billing-service (PolicyholderView)
                                               platform-consumer (platform_db)
                                                            ↓
                                                     dbt → Mart-Modelle
                                                            ↓
                                                     Data Product Portal
                                                     DataHub
                                                     Airflow-Monitoring
```

---

## 10. Konzept-Glossar

| Begriff | Bedeutung |
|---|---|
| **ADR** | Architecture Decision Record – dokumentiert eine Architekturentscheidung und ihre Begründung |
| **Aggregate** | DDD-Muster: eine Gruppe zusammengehöriger Objekte mit einer klaren Grenze und einem Root-Objekt. Alle Änderungen laufen über das Aggregate Root. |
| **Anti-Corruption Layer (ACL)** | Übersetzungsschicht zwischen zwei Bounded Contexts. Verhindert, dass fremde Domainkonzepte das eigene Modell «verunreinigen». |
| **At-Least-Once Delivery** | Kafka-Garantie: Eine Nachricht wird mindestens einmal geliefert. Consumer müssen idempotent sein. |
| **Avro** | Binäres Serialisierungsformat mit Schema-Definition. Kompakter als JSON, Schema-validiert. |
| **Bounded Context** | DDD-Begriff: Ein klar abgegrenzter Bereich, in dem Begriffe eine eindeutige Bedeutung haben. |
| **CDC (Change Data Capture)** | Mechanismus, um Datenbankänderungen in Echtzeit zu lesen und weiterzuleiten (hier: via Debezium + PostgreSQL WAL). |
| **Consumer Group** | Gruppe von Kafka-Consumern, die gemeinsam einen Topic verarbeiten. Jede Partition wird von genau einem Consumer der Group verarbeitet. |
| **Data Mesh** | Paradigma der verteilten Dateneigentümerschaft: Teams besitzen ihre Daten als Produkt, nicht ein zentrales Data-Team. |
| **Data Outside** | Daten, die ein Service offiziell teilt – in dieser Plattform: Kafka-Topics. |
| **Data Inside** | Private Daten eines Services – in dieser Plattform: die eigene PostgreSQL-Datenbank. |
| **dbt** | Data Build Tool. SQL-Framework für schichtweise Datentransformation in einem Data Warehouse. |
| **Dead Letter Queue (DLQ)** | Kafka-Topic, auf das Events geleitet werden, die nach mehreren Versuchen nicht verarbeitet werden konnten. |
| **Debezium** | Open-Source CDC-Plattform. Liest PostgreSQL WAL und publiziert Änderungen als Kafka-Events. |
| **DDD** | Domain-Driven Design. Methodik zur Ausrichtung von Software-Design an fachlichen Domänen. |
| **ECST** | Event-Carried State Transfer. Muster, bei dem ein Service seinen vollständigen aktuellen Zustand als kompaktierten Kafka-Event publiziert. |
| **Hexagonale Architektur** | Auch «Ports & Adapters». Trennt Domain-Logik von technischen Details durch definierte Schnittstellen (Ports). |
| **Idempotenz** | Eigenschaft einer Operation: mehrfache Ausführung mit denselben Inputs hat dasselbe Ergebnis wie einmalige Ausführung. Wichtig bei at-least-once delivery. |
| **KRaft** | Kafka Raft Metadata Mode. Ersetzt ZooKeeper in modernen Kafka-Installationen. |
| **ODC** | Open Data Contract. YAML-Spezifikation für ein Datenprodukt mit Schema, SLA, Qualitätsregeln und Ownership. |
| **Offset** | Position einer Nachricht innerhalb einer Kafka-Partition. Consumer-Fortschritt wird als Offset gespeichert. |
| **Outbox Pattern** | Muster zur sicheren Event-Publication: Events werden in derselben DB-Transaktion wie Domainänderungen gespeichert; CDC publiziert sie asynchron nach Kafka. |
| **Partition** | Untereinheit eines Kafka-Topics. Nachrichten mit demselben Key landen in derselben Partition (garantierte Reihenfolge). |
| **Port** | In der hexagonalen Architektur: ein Interface, das beschreibt, was eine Komponente braucht oder anbietet. |
| **Read Model** | Lokale Kopie von Fremddaten, aufgebaut aus konsumierten Events. Macht Services resilient gegen Ausfälle anderer Services. |
| **Schema Registry** | Confluent-Dienst zur Versionierung und Kompatibilitätsprüfung von Kafka-Schemas. |
| **SCS** | Self-Contained System. Autonomer Service mit eigener UI, eigener DB und eigener Business-Logik. |
| **SodaCL** | Soda Checks Language. Deklarative Sprache für Datenqualitätsprüfungen (NULL-Checks, Duplikate, Ranges). |
| **Transactional Outbox** | Siehe «Outbox Pattern». |
| **Ubiquitous Language** | DDD-Begriff: Einheitliches Vokabular, das von Entwicklern und Fachexperten gleichermassen verwendet wird. |
| **WAL** | Write-Ahead Log. PostgreSQL-Mechanismus: Jede Schreiboperation wird zuerst im WAL protokolliert. Debezium liest den WAL für CDC. |

---

## 11. Organisation

### 11.1 Team-Struktur und Conway's Law

> *«Any organization that designs a system will produce a design whose structure is a copy of the organization's communication structure.»* – Melvin Conway

Die Architektur dieser Plattform ist eine **bewusste Anwendung von Conway's Law**: Die Bounded Contexts des DDD werden direkt auf autonome Teams abgebildet. Jedes Team besitzt einen Service vollständig – vom Code über die Datenbank bis zum Kafka-Topic.

Das bedeutet: Wer die Teams richtig schneidet, schneidet automatisch die Services richtig. Und umgekehrt: Wer Service-Grenzen verändern will, muss Team-Grenzen mitdenken.

Die Plattform folgt den Prinzipien der **Team Topologies**:

| Team-Typ | Beschreibung | Teams in dieser Plattform |
|---|---|---|
| **Stream-Aligned Team** | Liefert kontinuierlich Wert entlang eines Geschäfts-Streams | Vertrag, Inkasso, Verkauf, Produkt, Schaden, Partner |
| **Platform Team** | Stellt die interne Plattform bereit, reduziert kognitive Last | Team Plattform |
| **Enabling Team** | Unterstützt andere Teams bei neuen Praktiken | Team Architektur (optional) |

---

### 11.2 Teams und ihre Zuständigkeiten

#### Team Produkt

**Domain:** Product Management (Core Domain)

| Aspekt | Details |
|---|---|
| **Service** | `product-service` (Port 9081) |
| **Datenbank** | `product_db` |
| **Kafka-Topics (Owner)** | `product.v1.defined` |
| **Nutzer** | Underwriter (Produktpflege) |

Das Team Produkt verwaltet den Versicherungsprodukt-Katalog. Es ist **upstream** für fast alle anderen Domains – Änderungen an Produkten strahlen via Events in Policy, Claims und Analytics aus. Das Team braucht keine Koordination mit anderen Teams, um Produkte zu deployen.

---

#### Team Vertrag

**Domain:** Policy Management (Core Domain)

| Aspekt | Details |
|---|---|
| **Service** | `policy-service` (Port 9082) |
| **Datenbank** | `policy_db` |
| **Kafka-Topics (Owner)** | `policy.v1.issued`, `policy.v1.cancelled` |
| **Konsumiert** | `product.v1.defined`, `partner.v1.state`, `sales.v1.offer_accepted` |
| **Nutzer** | Underwriter, Versicherungsnehmer |

Team Vertrag trägt die höchste fachliche Verantwortung: Der Policen-Lebenszyklus ist das Herzstück der Sachversicherung. Das Team hält **lokale Read Models** der Produktdaten und Partnerdaten und ist damit auch dann arbeitsfähig, wenn Team Produkt oder Team Partner gerade deployen.

---

#### Team Inkasso

**Domain:** Billing & Collection (Supporting Domain)

| Aspekt | Details |
|---|---|
| **Service** | `billing-service` (Port 9084) |
| **Datenbank** | `billing_db` |
| **Kafka-Topics (Owner)** | `billing.v1.invoice_created`, `billing.v1.payment_received`, `billing.v1.payment_overdue` |
| **Konsumiert** | `policy.v1.issued`, `policy.v1.cancelled`, `claims.v1.settled` |
| **Nutzer** | Sachbearbeiter (Zahlungsverkehr) |

Team Inkasso reagiert auf Geschäftsvorfälle anderer Teams (Police ausgestellt → Rechnung erstellen; Schaden reguliert → Auszahlung). Es initiiert selbst keine Policen oder Schäden – es ist rein **reaktiv und downstream**.

---

#### Team Verkauf

**Domain:** Sales & Distribution (Supporting Domain)

| Aspekt | Details |
|---|---|
| **Service** | `sales-service` *(geplant)* |
| **Datenbank** | `sales_db` *(geplant)* |
| **Kafka-Topics (Owner)** | `sales.v1.offer_accepted`, `sales.v1.offer_expired` |
| **Konsumiert** | `product.v1.defined`, `partner.v1.state` |
| **Nutzer** | Broker/Agent, Versicherungsnehmer (Onlineangebot) |

Team Verkauf verwaltet den Angebotsprozess. Sobald ein Angebot akzeptiert wird, publiziert das Team ein `OfferAccepted`-Event – Team Vertrag konvertiert dieses in eine Police. Die beiden Teams sind damit **vollständig entkoppelt**: Verkauf weiss nicht, wie eine Police ausgestellt wird; Vertrag weiss nicht, woher der Auftrag kommt.

---

#### Team Schaden *(empfohlen)*

**Domain:** Claims Management (Core Domain)

| Aspekt | Details |
|---|---|
| **Service** | `claims-service` (Port 9083, aktuell Stub) |
| **Datenbank** | `claims_db` |
| **Kafka-Topics (Owner)** | `claims.v1.opened`, `claims.v1.assessed`, `claims.v1.settled` |
| **Konsumiert** | `policy.v1.issued` (Read Model für Deckungsprüfung) |
| **Nutzer** | Schadensachbearbeiter (Claims Agent) |

Claims Management ist eine **Core Domain mit hoher Komplexität** – Schadenbewertung, Regulierung, Gutachten, Teilzahlungen. Ein eigenständiges Team Schaden ist zwingend. Die einzige Ausnahme von der Async-Regel: Während der Schadenerfassung (FNOL) wird die aktuelle Deckung synchron via REST beim Policy Service abgefragt (ADR-003). Dies ist die einzige direkte Service-zu-Service-Abhängigkeit der gesamten Plattform.

---

#### Team Partner *(empfohlen)*

**Domain:** Partner/Customer Management (Supporting Domain)

| Aspekt | Details |
|---|---|
| **Service** | `partner-service` (Port 9080) |
| **Datenbank** | `partner_db` |
| **Kafka-Topics (Owner)** | `partner.v1.created`, `partner.v1.updated`, `partner.v1.state` |
| **Nutzer** | Alle Teams (lesend via Read Models) |

Ein Partner-Team mag klein sein, aber es ist **foundational**: Ohne valide Personen können keine Policen ausgestellt werden. Das Team pflegt die Goldquelle für alle natürlichen Personen. Alle anderen Teams führen nur Read-Only-Kopien (Read Models) der Partnerdaten.

---

#### Team Plattform *(empfohlen)*

**Domain:** Generische Domäne / Enabling Infrastructure

| Aspekt | Details |
|---|---|
| **Verantwortlich für** | Kafka, Schema Registry, Debezium, DataHub, Analytics Platform, Keycloak |
| **Kunden** | Alle Stream-Aligned Teams |

Das Platform Team stellt die **interne Entwicklungsplattform** bereit: Self-Service-Kafka-Topic-Provisioning, Schema-Registry-Governance, CDC-Pipelines, Observability. Ohne Platform Team werden Betrieb und Weiterentwicklung der Infrastruktur zum Flaschenhals – jedes Stream-Aligned Team würde anfangen, eigene Lösungen zu bauen.

---

### 11.3 Datenprodukt-Ownership

Im Data Mesh ist **jedes Team der Data Product Owner** seiner publizierten Kafka-Topics. Das ist eine explizite Verantwortung – nicht nur für das Schema, sondern für Qualität, SLA und Dokumentation.

| Team | Datenprodukte (Kafka-Topics) | Konsumenten |
| --- | --- | --- |
| **Team Partner** | `partner.v1.created` · `partner.v1.updated` · `partner.v1.state` | Vertrag, Schaden, Inkasso, Analytics |
| **Team Produkt** | `product.v1.defined` | Vertrag, Analytics |
| **Team Vertrag** | `policy.v1.issued` · `policy.v1.cancelled` | Schaden, Inkasso, Analytics |
| **Team Schaden** | `claims.v1.opened` · `claims.v1.settled` | Inkasso, Analytics |
| **Team Inkasso** | `billing.v1.invoice_created` · `billing.v1.payment_received` | Analytics |
| **Team Verkauf** | `sales.v1.offer_accepted` · `sales.v1.offer_expired` | Vertrag, Analytics |

**Was Data Product Ownership konkret bedeutet:**

1. **ODC pflegen** – jedes Topic hat ein `{topic}.odcontract.yaml` mit aktuellem Schema, SLA-Angaben und SodaCL-Qualitätschecks.
2. **Breaking Changes ankündigen** – Schemaänderungen, die Konsumenten brechen würden, erfordern ein neues Topic (`policy.v2.issued`). Das alte Topic wird weitergeführt bis alle Konsumenten migriert sind.
3. **Qualität sicherstellen** – der Governance-Container läuft im eigenen Service und prüft automatisch Null-Raten, Duplikate und Wertebereiche.
4. **Erreichbarkeit** – Das Team ist für Konsumenten-Fragen erreichbar und dokumentiert Breaking Changes frühzeitig.

---

### 11.4 Assessment: Unterstützt die Architektur maximale Unabhängigkeit?

#### ✅ Was gut funktioniert

| Aspekt | Bewertung |
|---|---|
| **Getrennte Datenbanken** | Jede Domain hat ihre eigene PostgreSQL-Instanz. Kein Team kann die Daten eines anderen Teams direkt lesen oder verändern. |
| **Async-First via Kafka** | Das primäre Integrationsmuster entkoppelt Teams temporal: Team Vertrag kann deployen, auch wenn Team Inkasso gerade nicht verfügbar ist. |
| **Outbox Pattern** | Verhindert Dual-Write-Probleme. Events werden in derselben Transaktion wie Domainänderungen gespeichert – kein inkonsistenter Zustand möglich. |
| **Read Models** | Jeder Service hält lokale Kopien der benötigten Fremddaten. Fällt Team Partner aus, kann Team Vertrag weiterhin Policen ausstellen. |
| **ODC als Data Contracts** | Schema-Änderungen sind sichtbar und versioniert. Konsumenten können testen, ob ihr Code noch kompatibel ist. |
| **Hexagonale Architektur** | Domain-Logik ist vollständig isoliert von Frameworks. Teams können Infrastruktur-Abhängigkeiten austauschen, ohne Domain-Logik anzufassen. |
| **Unabhängige Deployments** | Kein gemeinsamer Release-Zug. Jedes Team deployt, wenn es bereit ist. |

#### ⚠️ Einschränkungen und Verbesserungspotenzial

#### 1. Synchroner REST-Call: Claims → Policy (ADR-003)

Dies ist die **einzige echte Laufzeit-Kopplung** der Plattform. Während der Schadenerfassung fragt der Claims Service synchron beim Policy Service nach, ob die Police Deckung bietet.

*Folge:* Wenn der Policy Service nicht verfügbar ist, kann kein Schaden erfasst werden.

*Empfehlung:* Circuit Breaker ist Pflicht (SmallRye Fault Tolerance). Mittelfristig könnte Team Schaden ein Read Model der aktiven Policen führen (`policy.v1.issued` konsumieren) und die Deckungsprüfung lokal durchführen. Dann entfällt die synchrone Abhängigkeit vollständig.

---

#### 2. Keycloak (IAM) als Single Point of Failure

Alle Services verlassen sich zur Laufzeit auf Keycloak für die Token-Validierung. Ist Keycloak nicht erreichbar, können sich keine Benutzer anmelden.

*Empfehlung:* Token-Introspection mit lokalem Caching (JWKS-Caching ist in Quarkus OIDC bereits eingebaut). Offline-Token-Validierung via Public Key macht die Services resilienter.

---

#### 3. Schema Registry als geteilte Infrastruktur

Alle Teams nutzen dieselbe Schema Registry. Eine falsch registrierte Schema-Version kann Kafka-Produzenten anderer Teams blockieren.

*Empfehlung:* Team Plattform muss die Schema Registry mit strikten Kompatibilitäts-Policies absichern (`BACKWARD_TRANSITIVE`). Kein Team darf Schemas eines anderen Teams verändern.

---

#### 4. Fehlende Consumer-driven Contract Tests

Aktuell gibt es keine automatisierten Tests, die prüfen ob ein Konsument noch kompatibel mit dem Schema eines Produzenten ist – ausser den ODC-Qualitätschecks zur Laufzeit.

*Empfehlung:* Pact oder eine ähnliche Consumer-driven Contract Testing Library einführen. Team Inkasso definiert, welche Felder es von `policy.v1.issued` benötigt – Team Vertrag testet automatisch, ob sein Event diese Anforderungen erfüllt.

---

#### 5. Analytics Platform als geteilte Ressource

Die Analytics Platform (dbt, Spark, Airflow, DataHub) wird heute zentral betrieben. Das ist pragmatisch für den Anfang, aber erzeugt mit der Zeit eine Abhängigkeit auf Team Plattform für jede neue Analytik-Anforderung.

*Empfehlung:* Mittelfristig sollten Stream-Aligned Teams ihre eigenen dbt-Modelle in einem **Self-Service Data Platform**-Modell pflegen. Team Plattform stellt die Infrastruktur bereit; Teams deployen ihre Transformationen selbständig.

---

#### Gesamtfazit

Die Architektur ist **bereits sehr gut auf Unabhängigkeit ausgelegt** und besser als die meisten produktiven Versicherungssysteme. Die identifizierten Schwachstellen sind bekannt und adressierbar:

| Priorität | Massnahme |
| --- | --- |
| Hoch | Read Model für Deckungsprüfung in Claims → synchronen REST-Call eliminieren |
| Mittel | Consumer-driven Contract Tests einführen |
| Mittel | Schema Registry Governance via Team Plattform formalisieren |
| Niedrig | Self-Service Analytics für Stream-Aligned Teams |

Mit diesen Massnahmen wäre die Plattform in der Lage, dass **sechs Teams vollständig unabhängig entwickeln, testen und deployen** – ohne Koordination, ohne Release-Züge, ohne gemeinsame Deploymentfenster.

---

*Zuletzt aktualisiert: März 2026*
