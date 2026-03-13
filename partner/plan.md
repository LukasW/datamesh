# Implementation Plan – Partner Service Erweiterung

**Stand:** 2026-03-12
**Ziel:** Erweiterung des Partner-Domänenmodells um Kontaktpersonen, Verträge und Interaktionen; CRUD-UI mit Qute + Bootstrap + htmx; neue Kafka-Events.

---

## Übersicht der Änderungen

| Bereich | Art | Aufwand |
|---------|-----|---------|
| Domänenmodell (4 Entitäten) | Neu/Umbau | Hoch |
| DB-Schema (Neuerstellung, 1 initiale Migration) | Umbau | Niedrig |
| Ports & Application Service | Erweiterung | Mittel |
| Infrastructure (JPA, Kafka) | Erweiterung | Mittel |
| REST API (neue Endpoints) | Erweiterung | Mittel |
| Qute-UI (CRUD mit htmx) | Neu | Hoch |
| Kafka-Events + ODC (4 neue Topics) | Neu | Mittel |
| Tests | Erweiterung | Mittel |
| README-Update | Aktualisierung | Niedrig |

---

## Phase 1 – Domänenmodell

### 1.1 Partner-Aggregate aktualisieren

**Datei:** `src/main/java/ch/css/partner/domain/model/Partner.java`

Änderungen:
- Feld `name` → `firmenname`
- Feld `hausnummer` zu Adresse hinzufügen (bisher fehlend)
- Feld `website` (String, URL) hinzufügen
- `email` und `phone` werden auf `Kontaktperson` verschoben (bleiben optional im Aggregate für Rückwärtskompatibilität bis v2)
- Methoden: `updateDetails()`, `activate()`, `setInactive()`, `addToLead()`

**Datei:** `src/main/java/ch/css/partner/domain/model/PartnerType.java`

Neue Enum-Werte (ersetzen bisherige):
```
VERTRIEBSPARTNER  // Vertriebspartner / Sales Partner
LIEFERANT         // Lieferant / Supplier
TECHNOLOGIEPARTNER // Technologiepartner / Technology Partner
```
> **Breaking Change:** `PartnerType` ändert sich von `CUSTOMER/BROKER/AGENT/SUPPLIER` zu neuen Werten.
> Kafka-Topic wechselt von `partner.v1.created` zu `partner.v2.created`.

**Datei:** `src/main/java/ch/css/partner/domain/model/PartnerStatus.java`

Neue Enum-Werte:
```
LEAD     // Interessent, noch kein aktiver Partner
AKTIV    // Aktiver Partner
INAKTIV  // Deaktivierter Partner
```

### 1.2 Neue Entität: Kontaktperson

**Datei:** `src/main/java/ch/css/partner/domain/model/Kontaktperson.java`

```java
// Felder
String kontaktId;       // UUID, Primärschlüssel
String partnerId;       // FK zum Partner
String vorname;         // Pflichtfeld
String nachname;        // Pflichtfeld
String rolle;           // z.B. "Key Account Manager", "Geschäftsführer"
String email;           // Direkte E-Mail
String telefon;         // Direkte Durchwahl

// Methoden
Kontaktperson(partnerId, vorname, nachname, rolle, email, telefon)
update(vorname, nachname, rolle, email, telefon)
```

### 1.3 Neue Entität: Vertrag

**Datei:** `src/main/java/ch/css/partner/domain/model/Vertrag.java`

```java
// Felder
String vertragsId;         // UUID, Primärschlüssel
String partnerId;          // FK zum Partner
VertragsTyp vertragsTyp;   // NDA, RAHMENVERTRAG, RESELLER_VERTRAG
LocalDate startdatum;      // Gültig ab
LocalDate enddatum;        // Gültig bis (nullable)
VertragsStatus status;     // IN_VERHANDLUNG, UNTERZEICHNET, ABGELAUFEN

// Methoden
Vertrag(partnerId, vertragsTyp, startdatum, enddatum)
unterzeichnen()
ablaufen()
```

**Neue Enums:**
- `VertragsTyp`: `NDA`, `RAHMENVERTRAG`, `RESELLER_VERTRAG`
- `VertragsStatus`: `IN_VERHANDLUNG`, `UNTERZEICHNET`, `ABGELAUFEN`

### 1.4 Neue Entität: Interaktion

**Datei:** `src/main/java/ch/css/partner/domain/model/Interaktion.java`

```java
// Felder
String interaktionsId;    // UUID, Primärschlüssel
String partnerId;         // FK zum Partner
LocalDateTime datum;      // Zeitpunkt des Kontakts
InteraktionsArt art;      // E_MAIL, TELEFONAT, MEETING
String beschreibung;      // Inhalt (Pflichtfeld)
String naechsteSchritte;  // Offene Maßnahmen (nullable)

// Methoden
Interaktion(partnerId, datum, art, beschreibung, naechsteSchritte)
```

**Neuer Enum:**
- `InteraktionsArt`: `E_MAIL`, `TELEFONAT`, `MEETING`

---

## Phase 2 – Datenbankschema (Neuerstellung)

> Die Datenbank wird neu erstellt – keine inkrementellen Migrationen nötig. Die bestehende `V1__Create_Partner_Table.sql` wird durch eine einzige neue initiale Migration ersetzt.

### 2.1 Migration V1 ersetzen (vollständiges Schema)

**Datei:** `src/main/resources/db/migration/V1__Create_Schema.sql`

```sql
-- Partner
CREATE TABLE partner (
  partner_id    VARCHAR(36) PRIMARY KEY,
  firmenname    VARCHAR(255) NOT NULL,
  partner_type  VARCHAR(50) NOT NULL CHECK (partner_type IN ('VERTRIEBSPARTNER','LIEFERANT','TECHNOLOGIEPARTNER')),
  status        VARCHAR(50) NOT NULL DEFAULT 'LEAD' CHECK (status IN ('LEAD','AKTIV','INAKTIV')),
  strasse       VARCHAR(255),
  hausnummer    VARCHAR(20),
  plz           VARCHAR(10),
  ort           VARCHAR(100),
  land          VARCHAR(100),
  website       VARCHAR(500),
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Kontaktperson
CREATE TABLE kontaktperson (
  kontakt_id    VARCHAR(36) PRIMARY KEY,
  partner_id    VARCHAR(36) NOT NULL REFERENCES partner(partner_id) ON DELETE CASCADE,
  vorname       VARCHAR(100) NOT NULL,
  nachname      VARCHAR(100) NOT NULL,
  rolle         VARCHAR(100),
  email         VARCHAR(255),
  telefon       VARCHAR(50),
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Vertrag
CREATE TABLE vertrag (
  vertrags_id   VARCHAR(36) PRIMARY KEY,
  partner_id    VARCHAR(36) NOT NULL REFERENCES partner(partner_id) ON DELETE CASCADE,
  vertrags_typ  VARCHAR(50) NOT NULL CHECK (vertrags_typ IN ('NDA','RAHMENVERTRAG','RESELLER_VERTRAG')),
  startdatum    DATE NOT NULL,
  enddatum      DATE,
  status        VARCHAR(50) NOT NULL DEFAULT 'IN_VERHANDLUNG' CHECK (status IN ('IN_VERHANDLUNG','UNTERZEICHNET','ABGELAUFEN')),
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Interaktion
CREATE TABLE interaktion (
  interaktions_id   VARCHAR(36) PRIMARY KEY,
  partner_id        VARCHAR(36) NOT NULL REFERENCES partner(partner_id) ON DELETE CASCADE,
  datum             TIMESTAMP NOT NULL,
  art               VARCHAR(50) NOT NULL CHECK (art IN ('E_MAIL','TELEFONAT','MEETING')),
  beschreibung      TEXT NOT NULL,
  naechste_schritte TEXT,
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Audit
CREATE TABLE partner_audit (
  audit_id    BIGSERIAL PRIMARY KEY,
  partner_id  VARCHAR(36) NOT NULL REFERENCES partner(partner_id) ON DELETE CASCADE,
  action      VARCHAR(50),
  old_values  JSONB,
  new_values  JSONB,
  changed_by  VARCHAR(255),
  changed_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indices
CREATE INDEX idx_partner_firmenname      ON partner(firmenname);
CREATE INDEX idx_partner_type            ON partner(partner_type);
CREATE INDEX idx_partner_status          ON partner(status);
CREATE INDEX idx_kontaktperson_partner   ON kontaktperson(partner_id);
CREATE INDEX idx_vertrag_partner         ON vertrag(partner_id);
CREATE INDEX idx_interaktion_partner     ON interaktion(partner_id);
CREATE INDEX idx_interaktion_datum       ON interaktion(datum);
CREATE INDEX idx_partner_audit_partner   ON partner_audit(partner_id);
```

---

## Phase 3 – Domain Services & Ports

### 3.1 Neue Output Ports

**Neue Dateien:**

- `src/main/java/ch/css/partner/domain/port/out/KontaktpersonRepository.java`
  - `save(Kontaktperson)`, `findById(id)`, `findByPartnerId(partnerId)`, `delete(id)`

- `src/main/java/ch/css/partner/domain/port/out/VertragRepository.java`
  - `save(Vertrag)`, `findById(id)`, `findByPartnerId(partnerId)`, `delete(id)`

- `src/main/java/ch/css/partner/domain/port/out/InteraktionRepository.java`
  - `save(Interaktion)`, `findById(id)`, `findByPartnerId(partnerId)`, `delete(id)`

### 3.2 PartnerEventPublisher erweitern

**Datei:** `src/main/java/ch/css/partner/domain/port/out/PartnerEventPublisher.java`

Neue Methoden:
```java
void publishPartnerCreatedV2(String partnerId, String firmenname, PartnerType partnerType, String website);
void publishKontaktpersonHinzugefuegt(String partnerId, String kontaktId, String vorname, String nachname);
void publishVertragErstellt(String partnerId, String vertragsId, VertragsTyp typ, LocalDate startdatum);
void publishInteraktionProtokolliert(String partnerId, String interaktionsId, InteraktionsArt art, LocalDateTime datum);
```

### 3.3 PartnerApplicationService erweitern

**Datei:** `src/main/java/ch/css/partner/domain/service/PartnerApplicationService.java`

Neue Use Cases:
```java
// Partner
String createPartner(String firmenname, PartnerType typ, String website, /* Adresse */);
void updatePartner(String partnerId, String firmenname, PartnerType typ, String website, /* Adresse */);
void deletePartner(String partnerId);
List<Partner> findAll();

// Kontaktpersonen
String addKontaktperson(String partnerId, String vorname, String nachname, String rolle, String email, String telefon);
void updateKontaktperson(String kontaktId, String vorname, String nachname, String rolle, String email, String telefon);
void deleteKontaktperson(String kontaktId);
List<Kontaktperson> getKontaktpersonen(String partnerId);

// Verträge
String createVertrag(String partnerId, VertragsTyp typ, LocalDate startdatum, LocalDate enddatum);
void updateVertrag(String vertragsId, VertragsTyp typ, LocalDate startdatum, LocalDate enddatum, VertragsStatus status);
void deleteVertrag(String vertragsId);
List<Vertrag> getVertraege(String partnerId);

// Interaktionen
String logInteraktion(String partnerId, InteraktionsArt art, String beschreibung, String naechsteSchritte);
void updateInteraktion(String interaktionsId, InteraktionsArt art, String beschreibung, String naechsteSchritte);
void deleteInteraktion(String interaktionsId);
List<Interaktion> getInteraktionen(String partnerId);
```

**Neue Domain-Exceptions:**
- `KontaktpersonNotFoundException`
- `VertragNotFoundException`
- `InteraktionNotFoundException`

---

## Phase 4 – Infrastructure Adapters

### 4.1 JPA Entities

**Neue Dateien:**
- `src/main/java/ch/css/partner/infrastructure/persistence/KontaktpersonEntity.java`
- `src/main/java/ch/css/partner/infrastructure/persistence/VertragEntity.java`
- `src/main/java/ch/css/partner/infrastructure/persistence/InteraktionEntity.java`

**PartnerEntity.java aktualisieren:**
- `name` → `firmenname`
- `hausnummer` hinzufügen
- `website` hinzufügen
- `@OneToMany` Beziehungen zu Kontaktpersonen, Verträgen, Interaktionen (LAZY)

### 4.2 JPA Adapters

**Neue Dateien:**
- `KontaktpersonJpaAdapter.java` – implementiert `KontaktpersonRepository`
- `VertragJpaAdapter.java` – implementiert `VertragRepository`
- `InteraktionJpaAdapter.java` – implementiert `InteraktionRepository`

### 4.3 Kafka Adapter erweitern

**Datei:** `src/main/java/ch/css/partner/infrastructure/messaging/PartnerKafkaAdapter.java`

Neue Emitter-Channels:
- `@Channel("partner-created-v2")` – Emitter für partner.v2.created
- `@Channel("partner-contact-added")` – Emitter für partner.v1.contact-added
- `@Channel("partner-contract-created")` – Emitter für partner.v1.contract-created
- `@Channel("partner-interaction-logged")` – Emitter für partner.v1.interaction-logged

### 4.4 REST Adapter erweitern

**Datei:** `src/main/java/ch/css/partner/infrastructure/web/PartnerRestAdapter.java`

Neue Endpoints (für htmx-Fragments):

```
# Partner CRUD
GET    /api/partners                       → List<PartnerDto>
GET    /api/partners/{id}                  → PartnerDto
POST   /api/partners                       → PartnerDto (201)
PUT    /api/partners/{id}                  → PartnerDto
DELETE /api/partners/{id}                  → 204

# Kontaktpersonen
GET    /api/partners/{id}/kontakte         → List<KontaktpersonDto>
POST   /api/partners/{id}/kontakte         → KontaktpersonDto (201)
PUT    /api/partners/{id}/kontakte/{kid}   → KontaktpersonDto
DELETE /api/partners/{id}/kontakte/{kid}   → 204

# Verträge
GET    /api/partners/{id}/vertraege        → List<VertragDto>
POST   /api/partners/{id}/vertraege        → VertragDto (201)
PUT    /api/partners/{id}/vertraege/{vid}  → VertragDto
DELETE /api/partners/{id}/vertraege/{vid}  → 204

# Interaktionen
GET    /api/partners/{id}/interaktionen          → List<InteraktionDto>
POST   /api/partners/{id}/interaktionen          → InteraktionDto (201)
PUT    /api/partners/{id}/interaktionen/{iid}    → InteraktionDto
DELETE /api/partners/{id}/interaktionen/{iid}    → 204
```

---

## Phase 5 – Qute UI (htmx + Bootstrap)

### 5.1 Qute Web Controller

**Neue Datei:** `src/main/java/ch/css/partner/infrastructure/web/PartnerUiController.java`

```java
// Seitenwechsel (Full-Page)
GET /partners                 → partners/list.html
GET /partners/{id}            → partners/detail.html
GET /partners/new             → partners/form.html (Create)
GET /partners/{id}/edit       → partners/form.html (Update)

// htmx-Fragmente (partial renders)
GET  /partners/fragments/list              → fragment: partner-list
GET  /partners/fragments/{id}/kontakte     → fragment: kontakt-table
GET  /partners/fragments/{id}/vertraege    → fragment: vertrag-table
GET  /partners/fragments/{id}/interaktionen → fragment: interaktion-table
POST /partners/fragments                   → Erstellt Partner + rendert neuen Listeneintrag
POST /partners/fragments/{id}/kontakte     → Erstellt Kontakt + rendert Tabellenzeile
etc.
```

### 5.2 Qute Templates

```
src/main/resources/templates/
├── layout/
│   └── base.html           ← Bootstrap 5 Layout, Navbar, htmx CDN
├── partners/
│   ├── list.html           ← Partnerliste mit Suchfeld + "Neu"-Button
│   ├── detail.html         ← Partner-Detail mit Tab-Navigationr
│   ├── form.html           ← Erstell-/Bearbeiten-Formular
│   └── fragments/
│       ├── partner-row.html       ← Einzelne Tabellenzeile (htmx swap)
│       ├── partner-form-modal.html ← Inline-Formular für htmx
│       ├── kontakt-table.html     ← Kontaktpersonen-Tab Inhalt
│       ├── kontakt-row.html       ← Einzelne Zeile
│       ├── kontakt-form.html      ← Inline-Formular
│       ├── vertrag-table.html     ← Verträge-Tab Inhalt
│       ├── vertrag-row.html
│       ├── vertrag-form.html
│       ├── interaktion-table.html ← Interaktionen-Tab Inhalt
│       ├── interaktion-row.html
│       └── interaktion-form.html
```

### 5.3 UI-Flows mit htmx

#### Partnerliste (`/partners`)
- Tabelle mit: Firmenname, Typ, Status, Ort, Website, Aktionen
- Suchfeld: `hx-get="/partners?name=..."` mit `hx-trigger="keyup changed delay:300ms"` → ersetzt nur die Tabelle
- "Neuer Partner"-Button: öffnet Modal mit `hx-get="/partners/fragments/new"` → `hx-target="#modal-container"`
- Formular-Submit: `hx-post="/partners/fragments"` → `hx-target="#partner-table-body"` `hx-swap="afterbegin"`
- Löschen: `hx-delete="/api/partners/{id}"` `hx-confirm="Wirklich löschen?"` → `hx-target="closest tr"` `hx-swap="outerHTML"`

#### Partner-Detail (`/partners/{id}`)
- Kopfbereich: Firmenname, Typ, Status, Adresse, Website; Bearbeiten-Button
- Bootstrap Tab-Navigation:
  - **Kontaktpersonen** – lädt Fragment via `hx-get` beim Tab-Klick
  - **Verträge** – lädt Fragment via `hx-get` beim Tab-Klick
  - **Interaktionen** – lädt Fragment via `hx-get` beim Tab-Klick
- Innerhalb jedes Tabs: Tabelle + "Hinzufügen"-Button (öffnet Inline-Formular via htmx)

### 5.4 pom.xml – Neue Dependency

```xml
<!-- Quarkus Qute (Server-Side Templates) -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-resteasy-reactive-qute</artifactId>
</dependency>
```

---

## Phase 6 – Kafka Events & Open Data Contracts

### 6.1 Neue / aktualisierte Topics

| Topic | Beschreibung | Breaking? |
|-------|-------------|-----------|
| `partner.v2.created` | Partner erstellt (neue Felder: website, hausnummer, neue PartnerType-Werte) | Ja – v2 |
| `partner.v1.contact-added` | Kontaktperson hinzugefügt | Nein – neu |
| `partner.v1.contract-created` | Vertrag erstellt | Nein – neu |
| `partner.v1.interaction-logged` | Interaktion protokolliert | Nein – neu |

> `partner.v1.created` bleibt bestehen (für bestehende Consumer), wird aber als deprecated markiert.

### 6.2 ODC-Dateien

**Neue Dateien:**

- `src/main/resources/contracts/partner.v2.created.odcontract.yaml`
  - Schema: eventId, eventType, partnerId, firmenname, partnerType (neue Werte), website, adresse{strasse, hausnummer, plz, ort, land}, timestamp

- `src/main/resources/contracts/partner.v1.contact-added.odcontract.yaml`
  - Schema: eventId, eventType, partnerId, kontaktId, vorname, nachname, rolle, timestamp

- `src/main/resources/contracts/partner.v1.contract-created.odcontract.yaml`
  - Schema: eventId, eventType, partnerId, vertragsId, vertragsTyp, startdatum, enddatum, status, timestamp

- `src/main/resources/contracts/partner.v1.interaction-logged.odcontract.yaml`
  - Schema: eventId, eventType, partnerId, interaktionsId, art, datum, timestamp

### 6.3 application.yml – neue Channels

```yaml
mp:
  messaging:
    outgoing:
      partner-created-v2:
        connector: smallrye-kafka
        topic: partner.v2.created
        value.serializer: org.apache.kafka.common.serialization.StringSerializer
      partner-contact-added:
        connector: smallrye-kafka
        topic: partner.v1.contact-added
        value.serializer: org.apache.kafka.common.serialization.StringSerializer
      partner-contract-created:
        connector: smallrye-kafka
        topic: partner.v1.contract-created
        value.serializer: org.apache.kafka.common.serialization.StringSerializer
      partner-interaction-logged:
        connector: smallrye-kafka
        topic: partner.v1.interaction-logged
        value.serializer: org.apache.kafka.common.serialization.StringSerializer
```

---

## Phase 7 – Tests

### 7.1 Unit-Tests (Domain)

**Neue Dateien in `src/test/java/ch/css/partner/domain/`:**
- `KontaktpersonTest.java` – Erstellen, Aktualisieren, Validierung
- `VertragTest.java` – Statusübergänge (unterzeichnen, ablaufen)
- `InteraktionTest.java` – Erstellen mit verschiedenen Arten
- `PartnerApplicationServiceTest.java` – alle neuen Use Cases

### 7.2 Integration-Tests

**Aktualisieren:** `src/test/java/ch/css/partner/PartnerRestAdapterTest.java`
- Neue Tests für alle CRUD-Endpoints
- Tests für Sub-Ressourcen (Kontakte, Verträge, Interaktionen)

**Neues:** `TestPartnerEventPublisher.java` erweitern um neue Methoden.

---

## Phase 8 – README aktualisieren

**Datei:** `readme.md`

Abschnitte aktualisieren:
- Domänenmodell (4 Entitäten mit Feldern)
- Architekturdiagramm (UI-Schicht hinzufügen)
- REST API Endpoints (alle neuen)
- Kafka Topics (v2.created + 3 neue)
- ODC-Referenzen
- UI-Screenshots / curl-Beispiele
- Roadmap aktualisieren (Phase 5 als erledigt markieren)

---

## Reihenfolge der Implementierung

```
Phase 1 – Domänenmodell
    ↓
Phase 2 – DB-Migrationen
    ↓
Phase 3 – Ports & Application Service
    ↓
Phase 4 – Infrastructure (JPA + Kafka + REST)
    ↓
Phase 5 – Qute UI
    ↓
Phase 6 – ODC-Dateien
    ↓
Phase 7 – Tests
    ↓
Phase 8 – README
```

> Jede Phase kann nach Abschluss einzeln deployed werden. Da die DB neu erstellt wird, gibt es keine Migrationskonflikte.

---

## Kritische Entscheidungen & Risiken

| Risiko | Mitigation |
|--------|------------|
| Breaking Change: PartnerType-Enum | Neues Kafka-Topic `partner.v2.created`; v1 bleibt parallel aktiv |
| Breaking Change: `name` → `firmenname` | Flyway-Migration V2 umbenennt die Spalte; API-DTOs passen sich an |
| Outbox Pattern fehlt noch | Weiterhin direktes Kafka-Publishing; Outbox als separate Issue (ADR-001 verletzt) |
| Keine OIDC-Absicherung der UI | `@RolesAllowed` auf Application Service ist vorbereitet; Keycloak-Integration als nächste Phase |

---

## Offene Fragen

1. Sollen bestehende Daten (PartnerType: CUSTOMER/BROKER → ?) migriert werden? Wenn ja, welches Mapping?
2. Welche RBAC-Rollen sollen welche CRUD-Operationen ausführen dürfen? (UNDERWRITER, BROKER, ADMIN?)
3. Soll die UI mehrsprachig sein (DE/EN) oder nur Deutsch?
4. Soll Enddatum beim Vertrag nullable sein (unbefristeter Vertrag)?
