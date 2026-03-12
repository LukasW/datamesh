# Partner/Customer Management Service

**Walking Skeleton** – Self-Contained System (SCS) für Partner- und Kundenverwaltung

## Übersicht

Dieses Service implementiert die Partner/Customer Management Domäne des Sachversicherungs-Datamesh auf Basis von:

- **Quarkus** (Java 21+, Virtual Threads)
- **Hexagonal Architecture** (Ports & Adapters)
- **Domain-Driven Design** (Bounded Context)
- **Event-Driven Architecture** via Apache Kafka
- **PostgreSQL** für persistente Daten
- **Open Data Contracts** (ODC) für Kafka-Events

## Architektur

```
┌─────────────────────────────────────────────────────────────┐
│                   REST API (Driving Adapter)                │
│        GET /api/partners/search?name=...                    │
│        GET /api/partners/{id}                               │
│        POST /api/partners                                   │
└──────────────────────┬──────────────────────────────────────┘
                       │
         ┌─────────────▼──────────────┐
         │  PartnerApplicationService │
         │   (Domain Use Cases)       │
         │  - searchByName()          │
         │  - createPartner()         │
         │  - getPartner()            │
         └──────────┬────────┬────────┘
                    │        │
        ┌───────────▼─┐  ┌──▼────────────────┐
        │ JPA Adapter │  │ Kafka Producer    │
        │ PostgreSQL  │  │ partner.v1.*      │
        │             │  │                   │
        └─────────────┘  └───────────────────┘
```

## Project Structure

```
partner-service/
├── pom.xml                                  # Maven Build
├── README.md                                # This file
│
├── src/main/java/com/insurance/partner/
│   ├── domain/
│   │   ├── model/
│   │   │   ├── Partner.java                 # Aggregate Root
│   │   │   ├── PartnerType.java             # Enum
│   │   │   └── PartnerStatus.java           # Enum
│   │   ├── service/
│   │   │   ├── PartnerApplicationService.java  # Use Cases
│   │   │   └── PartnerNotFoundException.java   # Domain Exception
│   │   └── port/
│   │       ├── PartnerRepository.java       # Output Port (Persistence)
│   │       ├── PartnerEventPublisher.java   # Output Port (Events)
│   │       └── SearchPartnerPort.java       # Input Port (Use Case)
│   └── infrastructure/
│       ├── persistence/
│       │   └── PartnerJpaAdapter.java       # JPA/PostgreSQL Adapter
│       ├── messaging/
│       │   └── PartnerKafkaAdapter.java     # Kafka Producer Adapter
│       └── web/
│           └── PartnerRestAdapter.java      # REST Adapter
│
├── src/main/resources/
│   ├── application.yml                      # Quarkus Configuration
│   ├── db/migration/
│   │   └── V1__Create_Partner_Table.sql     # Flyway DB Schema
│   └── contracts/
│       ├── partner.v1.created.odcontract.yaml   # ODC
│       └── partner.v1.updated.odcontract.yaml   # ODC
│
└── src/test/java/com/insurance/partner/
    └── PartnerRestAdapterTest.java          # Integration Tests
```

## Verwendete Ports (Walking Skeleton)

### REST API

- **GET /api/partners/search?name={fragment}** – Partner nach Name suchen
- **GET /api/partners/{partnerId}** – Partner Details abrufen
- **POST /api/partners** – Neuer Partner erstellen

### Kafka Topics

- **partner.v1.created** – Kafka Event bei Partner-Erstellung (Output)
- **partner.v1.updated** – Kafka Event bei Partner-Update (Output)
- **partner.v1.deleted** – Kafka Event bei Partner-Löschung (Output)

Jedes Topic hat einen definierten **Open Data Contract (ODC)** unter `src/main/resources/contracts/`.

## Getting Started

### Voraussetzungen

- Java 21+
- Maven 3.9+
- Docker (für PostgreSQL + Kafka)
- PostgreSQL 15+
- Apache Kafka (mit Schema Registry)

### 1. Development Environment starten

```bash
# PostgreSQL (Docker)
docker run -d \
  --name partner-postgres \
  -e POSTGRES_USER=partner_user \
  -e POSTGRES_PASSWORD=partner_pass \
  -e POSTGRES_DB=partner_db \
  -p 5432:5432 \
  postgres:15

# Kafka + ZooKeeper + Schema Registry (via Docker Compose)
docker-compose -f ../docker-compose.yml up -d
```

### 2. Service bauen und starten

```bash
cd partner-service

# Build
mvn clean package

# Run im Dev-Modus (mit Auto-Reload)
mvn quarkus:dev
```

Der Service läuft dann auf `http://localhost:8080`

### 3. API testen

**Partner suchen:**

```bash
curl "http://localhost:8080/api/partners/search?name=ACME"
```

**Partner erstellen:**

```bash
curl -X POST http://localhost:8080/api/partners \
  -H "Content-Type: application/json" \
  -d '{
    "name": "ACME Insurance GmbH",
    "email": "contact@acme.ch",
    "phone": "+41 44 123 45 67",
    "partnerType": "CUSTOMER"
  }'
```

**Partner Details abrufen:**

```bash
curl "http://localhost:8080/api/partners/{partnerId}"
```

### 4. Tests ausführen

```bash
mvn test
```

## Nächste Schritte (Roadmap)

- ✅ **Phase 1 (Walking Skeleton):** Partner-Suche + CRUD
- [ ] **Phase 2:** Event Sourcing + Audit Log
- [ ] **Phase 3:** REST API für intra-domain Kommunikation
- [ ] **Phase 4:** Advanced Search (Elasticsearch)
- [ ] **Phase 5:** UI mit Qute Templates

## Hexagonal Architecture Details

### Ports (Interfaces)

Das Service definiert folgende Ports:

**Input Ports (Use Cases):**

- `SearchPartnerPort` – Use Case: Partner nach Name suchen

**Output Ports (Abhängigkeiten):**

- `PartnerRepository` – Persistierung (aktuell PostgreSQL/JPA)
- `PartnerEventPublisher` – Event-Publikation (aktuell Kafka)

### Adapter (Implementierungen)

- `PartnerJpaAdapter` → `PartnerRepository` (JPA/PostgreSQL)
- `PartnerKafkaAdapter` → `PartnerEventPublisher` (Kafka Producer)
- `PartnerRestAdapter` → REST Endpoints (HTTP)

Die Domain-Logik in `PartnerApplicationService` ist **vollkommen framework-agnostisch** und nutzt nur die Port-Interfaces.

## Open Data Contracts (ODC)

Jedes publizierte Kafka-Topic hat einen ODC:

```yaml
# partner.v1.created.odcontract.yaml
apiVersion: v2.3.0
kind: DataContract
id: partner-created-v1
version: 1.0.0
name: Partner Created Event
schema:
  type: avro
  # ... Avro Schema Definition
quality:
  type: SodaCL
  # ... Data Quality Rules
serviceLevel:
  availability: "99.9%"
  retention: "7 years"
```

ODCs sind der **verbindliche Vertrag** zwischen Producer (Partner Service) und Consumers (andere Domänen).

## Configuration (application.yml)

Wichtige Umgebungsvariablen:

```yaml
DATABASE_URL=jdbc:postgresql://localhost:5432/partner_db
DATABASE_USER=partner_user
DATABASE_PASSWORD=partner_pass

KAFKA_BROKERS=localhost:9092
KAFKA_SCHEMA_REGISTRY=http://localhost:8081
```

Siehe `src/main/resources/application.yml` für alle Optionen.

## Observability

### Logging

Logs werden als JSON ausgegeben (strukturiert):

```json
{
  "timestamp": "2026-03-12T10:30:00Z",
  "level": "INFO",
  "message": "Partner created",
  "partnerId": "550e8400-e29b-41d4-a716-446655440000",
  "class": "ch.css.partner.domain.service.PartnerApplicationService"
}
```

## Lizenz

Intern – Sachversicherung Datamesh Platform
