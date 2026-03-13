# Partner Management Service – v2

Self-Contained System (SCS) für Partnerverwaltung (Vertriebspartner, Lieferanten, Technologiepartner).

## Domänenmodell

| Entität | Beschreibung |
|---------|-------------|
| `Partner` | Aggregate Root – Firmenname, Typ, Status, Adresse, Website |
| `Kontaktperson` | Ansprechpartner beim Partner (Vorname, Nachname, Rolle, E-Mail, Telefon) |
| `Vertrag` | NDA / Rahmenvertrag / Reseller-Vertrag mit Status-Lifecycle |
| `Interaktion` | Protokoll von Kontakten (E-Mail, Telefonat, Meeting) |

### PartnerType (neu in v2)

| Wert | Beschreibung |
|------|-------------|
| `VERTRIEBSPARTNER` | Vertriebspartner / Sales Partner |
| `LIEFERANT` | Lieferant / Supplier |
| `TECHNOLOGIEPARTNER` | Technologiepartner / Technology Partner |

### PartnerStatus

| Wert | Beschreibung |
|------|-------------|
| `LEAD` | Interessent, noch nicht aktiv |
| `AKTIV` | Aktiver Partner |
| `INAKTIV` | Deaktivierter Partner |

## Architektur

```
┌──────────────────────────────────────────────────────────────┐
│  UI-Schicht: Qute + Bootstrap + htmx  GET/POST /partners/..  │
├──────────────────────────────────────────────────────────────┤
│  REST API: JSON  GET/POST/PUT/DELETE /api/partners/...       │
└──────────────────────┬───────────────────────────────────────┘
                       │
         ┌─────────────▼──────────────────────┐
         │       PartnerApplicationService    │
         │  Partner / Kontakt / Vertrag /     │
         │  Interaktion CRUD + Events         │
         └──────┬─────────────────────┬───────┘
                │                     │
     ┌──────────▼──────┐   ┌──────────▼──────────────┐
     │  JPA Adapters   │   │  Kafka Producer Adapter  │
     │  PostgreSQL     │   │  partner.v2.* / v1.*     │
     └─────────────────┘   └──────────────────────────┘
```

## REST API Endpoints

### Partner

| Method | Pfad | Beschreibung |
|--------|------|-------------|
| `GET` | `/api/partners` | Alle Partner (optional `?name=`) |
| `GET` | `/api/partners/{id}` | Partner-Detail |
| `POST` | `/api/partners` | Partner erstellen |
| `PUT` | `/api/partners/{id}` | Partner aktualisieren |
| `DELETE` | `/api/partners/{id}` | Partner löschen |

### Kontaktpersonen

| Method | Pfad | Beschreibung |
|--------|------|-------------|
| `GET` | `/api/partners/{id}/kontakte` | Alle Kontaktpersonen |
| `POST` | `/api/partners/{id}/kontakte` | Kontaktperson hinzufügen |
| `PUT` | `/api/partners/{id}/kontakte/{kid}` | Kontaktperson aktualisieren |
| `DELETE` | `/api/partners/{id}/kontakte/{kid}` | Kontaktperson löschen |

### Verträge

| Method | Pfad | Beschreibung |
|--------|------|-------------|
| `GET` | `/api/partners/{id}/vertraege` | Alle Verträge |
| `POST` | `/api/partners/{id}/vertraege` | Vertrag erstellen |
| `PUT` | `/api/partners/{id}/vertraege/{vid}` | Vertrag aktualisieren |
| `DELETE` | `/api/partners/{id}/vertraege/{vid}` | Vertrag löschen |

### Interaktionen

| Method | Pfad | Beschreibung |
|--------|------|-------------|
| `GET` | `/api/partners/{id}/interaktionen` | Alle Interaktionen |
| `POST` | `/api/partners/{id}/interaktionen` | Interaktion protokollieren |
| `PUT` | `/api/partners/{id}/interaktionen/{iid}` | Interaktion aktualisieren |
| `DELETE` | `/api/partners/{id}/interaktionen/{iid}` | Interaktion löschen |

## Kafka Topics & ODC

| Topic | Version | Beschreibung | Breaking? |
|-------|---------|-------------|-----------|
| `partner.v2.created` | v2 | Partner erstellt (neue PartnerType-Werte, website, hausnummer) | **Ja** |
| `partner.v1.updated` | v1 | Partner aktualisiert | Nein |
| `partner.v1.deleted` | v1 | Partner gelöscht | Nein |
| `partner.v1.contact-added` | v1 | Kontaktperson hinzugefügt | Neu |
| `partner.v1.contract-created` | v1 | Vertrag erstellt | Neu |
| `partner.v1.interaction-logged` | v1 | Interaktion protokolliert | Neu |

ODC-Dateien: `src/main/resources/contracts/`

## UI

Die Web-Oberfläche ist unter `/partners` erreichbar und nutzt:
- **Qute** – Server-Side Rendering
- **Bootstrap 5** – Styling
- **htmx** – Partial-Page-Updates ohne JavaScript-Framework

## Getting Started

```bash
# Infrastruktur starten (PostgreSQL + Kafka)
docker-compose up -d

# Tests ausführen
mvn test

# Dev-Modus (Auto-Reload, H2 in-memory)
mvn quarkus:dev

# Produktion
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar
```

## Umgebungsvariablen

```
DATABASE_URL=jdbc:postgresql://localhost:5432/partner_db
DATABASE_USER=partner_user
DATABASE_PASSWORD=partner_pass
KAFKA_BROKERS=localhost:9092
```

## Roadmap

- ✅ **Phase 1:** Partner CRUD (Walking Skeleton)
- ✅ **Phase 2:** Erweitertes Domänenmodell (Kontakt, Vertrag, Interaktion)
- ✅ **Phase 3:** Vollständige REST API (CRUD + Sub-Ressourcen)
- ✅ **Phase 4:** Qute UI mit htmx + Bootstrap
- ✅ **Phase 5:** 6 Kafka Topics mit ODC
- [ ] **Phase 6:** Outbox Pattern (ADR-001 Compliance)
- [ ] **Phase 7:** Keycloak OIDC-Integration (RBAC)
- [ ] **Phase 8:** gRPC-Integration für Policy-Domäne

## Lizenz

Intern – Sachversicherung Datamesh Platform
