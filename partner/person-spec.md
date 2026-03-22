# Spezifikation – Personenverwaltung mit temporaler Adresse & Audit

**Stand:** 2026-03-12
**Domäne:** Partner / Kundenmanagement
**Ziel:** Natürliche Personen mit zeitlich gültigen Adressen verwalten; vollständige Audithistorie via Hibernate Envers.

---

## 1. Domänenmodell

### 1.1 Aggregate Root: `Person`

```java
// Datei: domain/model/Person.java
String personId;          // UUID, Primärschlüssel (immutable)
String name;              // Nachname (Pflichtfeld)
String vorname;           // Vorname (Pflichtfeld)
Geschlecht geschlecht;    // Enum: MAENNLICH | WEIBLICH | DIVERS
LocalDate geburtsdatum;   // Pflichtfeld
AhvNummer ahvNummer;      // Value Object, Pflichtfeld, unique

List<Adresse> adressen;   // Owned Entities (nicht geteilt)
```

**Invarianten:**
- `name`, `vorname`, `geburtsdatum`, `ahvNummer` sind Pflichtfelder.
- `ahvNummer` ist eindeutig im System (Unique Constraint auf DB + Domänenvalidierung).
- Pro `AdressTyp` darf zu jedem Zeitpunkt **genau eine** Adresse gültig sein.

**Methoden:**
```java
Person(name, vorname, geschlecht, geburtsdatum, ahvNummer)
void updatePersonalien(name, vorname, geschlecht, geburtsdatum)
void addAdresse(AdressTyp, strasse, hausnummer, plz, ort, land, gueltigVon, gueltigBis)
void updateAdressGueltigkeit(adressId, gueltigVon, gueltigBis)
void removeAdresse(adressId)
Adresse getAktuelleAdresse(AdressTyp)      // gueltigVon ≤ heute ≤ gueltigBis (oder unbefristet)
List<Adresse> getAdressverlauf(AdressTyp)  // alle, chronologisch
```

---

### 1.2 Value Object: `AhvNummer`

```java
// Datei: domain/model/AhvNummer.java
// Format: 756.XXXX.XXXX.XX
// Validierung: EAN-13 Prüfziffer nach CH-AHV-Standard
// Darstellung: "756.1234.5678.97"
String value;

AhvNummer(String raw)           // normalisiert und validiert
String formatted()              // "756.1234.5678.97"
boolean equals(AhvNummer other)
```

**Validierungsregel:**
- Muss mit `756` beginnen.
- 13 Ziffern gesamt (Punkte werden bei Validierung ignoriert).
- EAN-13 Prüfziffer (letztes Digit) muss stimmen.

---

### 1.3 Enum: `Geschlecht`

```java
// Datei: domain/model/Geschlecht.java
MAENNLICH,   // Männlich
WEIBLICH,    // Weiblich
DIVERS       // Divers
```

---

### 1.4 Entity: `Adresse`

```java
// Datei: domain/model/Adresse.java
String adressId;          // UUID, Primärschlüssel (immutable)
String personId;          // FK zur Person
AdressTyp adressTyp;      // Enum (Pflichtfeld)
String strasse;           // Pflichtfeld
String hausnummer;        // Pflichtfeld
String plz;               // Schweizer PLZ: 4 Stellen (Pflichtfeld)
String ort;               // Pflichtfeld
String land;              // Default: "Schweiz"
LocalDate gueltigVon;     // Pflichtfeld (fachliche Gültigkeit ab)
LocalDate gueltigBis;     // nullable = unbefristet gültig

// Derived:
boolean isAktuell()       // gueltigVon ≤ today ≤ gueltigBis (oder gueltigBis == null)
```

**Invarianten:**
- `gueltigVon` darf nicht nach `gueltigBis` liegen.
- Zwei Adressen desselben `AdressTyp` dürfen sich im Gültigkeitszeitraum **nicht überschneiden**.
  - Überlappungsprüfung: `A.gueltigVon ≤ B.gueltigBis AND A.gueltigBis ≥ B.gueltigVon` (null = ∞).
  - Geschäftsregel wird im Aggregate durchgesetzt, **nicht** nur in der DB.

---

### 1.5 Enum: `AdressTyp`

```java
// Datei: domain/model/AdressTyp.java
WOHNADRESSE,           // Domizil
KORRESPONDENZADRESSE,  // Für Post / Bescheide
ZUSTELLADRESSE         // Temporäre Lieferadresse
```

---

### 1.6 Domain Exception

```java
// Datei: domain/service/PersonNotFoundException.java
// Datei: domain/service/AdresseNotFoundException.java
// Datei: domain/service/AdressUeberschneidungException.java
//   → Wird geworfen, wenn eine neue/geänderte Adress-Gültigkeit mit einer bestehenden überlappt.
```

---

## 2. Datenbankschema

```sql
-- Datei: src/main/resources/db/migration/V2__Create_Person_Schema.sql

-- Person
CREATE TABLE person (
  person_id       VARCHAR(36)  PRIMARY KEY,
  name            VARCHAR(100) NOT NULL,
  vorname         VARCHAR(100) NOT NULL,
  geschlecht      VARCHAR(10)  NOT NULL CHECK (geschlecht IN ('MAENNLICH','WEIBLICH','DIVERS')),
  geburtsdatum    DATE         NOT NULL,
  ahv_nummer      VARCHAR(16)  NOT NULL UNIQUE,   -- "756.1234.5678.97"
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Adresse (temporal, owned by Person)
CREATE TABLE adresse (
  adress_id       VARCHAR(36)  PRIMARY KEY,
  person_id       VARCHAR(36)  NOT NULL REFERENCES person(person_id) ON DELETE CASCADE,
  adress_typ      VARCHAR(25)  NOT NULL CHECK (adress_typ IN ('WOHNADRESSE','KORRESPONDENZADRESSE','ZUSTELLADRESSE')),
  strasse         VARCHAR(255) NOT NULL,
  hausnummer      VARCHAR(20)  NOT NULL,
  plz             VARCHAR(4)   NOT NULL,
  ort             VARCHAR(100) NOT NULL,
  land            VARCHAR(100) NOT NULL DEFAULT 'Schweiz',
  gueltig_von     DATE         NOT NULL,
  gueltig_bis     DATE,        -- NULL = unbefristet
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indices
CREATE INDEX idx_person_name           ON person(name, vorname);
CREATE INDEX idx_person_ahv            ON person(ahv_nummer);
CREATE INDEX idx_adresse_person        ON adresse(person_id);
CREATE INDEX idx_adresse_typ_gueltig   ON adresse(person_id, adress_typ, gueltig_von, gueltig_bis);

-- Hibernate Envers Audit-Tabellen (werden automatisch durch Envers erzeugt,
-- hier zur Dokumentation):
--   person_aud          (person_id, REV, REVTYPE, name, vorname, ...)
--   adresse_aud         (adress_id, REV, REVTYPE, ...)
--   revinfo             (REV BIGSERIAL, REVTSTMP BIGINT)
```

> **Hinweis:** Die `*_aud`-Tabellen werden durch Hibernate Envers beim Start automatisch
> erstellt (DDL auto) oder können über `quarkus.hibernate-orm.database.generation=update`
> hinzugefügt werden. Für Produktiv-Migrationen sind explizite Flyway-Skripte vorzuziehen.

---

## 3. Audit mit Hibernate Envers

### 3.1 Maven-Dependency

```xml
<!-- pom.xml -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-hibernate-envers</artifactId>
</dependency>
```

### 3.2 Annotationen auf JPA-Entities

```java
// PersonEntity.java
@Entity
@Table(name = "person")
@Audited                           // ← alle Felder werden auditiert
@EntityListeners(AuditingEntityListener.class)
public class PersonEntity { ... }

// AdresseEntity.java
@Entity
@Table(name = "adresse")
@Audited
public class AdresseEntity { ... }
```

### 3.3 application.yml Envers-Konfiguration

```yaml
quarkus:
  hibernate-orm:
    database:
      generation: validate        # kein Drop-Create in Prod!
  hibernate-envers:
    store-data-at-delete: true    # gelöschte Werte werden aufgezeichnet
    audit-table-suffix: _aud
    revision-field-name: rev
    revision-type-field-name: rev_type
```

### 3.4 Custom RevisionEntity (optional, empfohlen)

```java
// Datei: infrastructure/persistence/audit/CustomRevisionEntity.java
@Entity
@RevisionEntity(CustomRevisionListener.class)
@Table(name = "revinfo")
public class CustomRevisionEntity extends DefaultRevisionEntity {
    @Column(name = "changed_by")
    private String changedBy;    // Benutzername aus Security-Context
}
```

### 3.5 Was wird auditiert

| Tabelle        | Audit-Tabelle    | Erfasste Ereignisse               |
|----------------|------------------|-----------------------------------|
| `person`       | `person_aud`     | INSERT (REV=0), UPDATE (REV=1), DELETE (REV=2) |
| `adresse`      | `adresse_aud`    | INSERT, UPDATE (gueltig_von/bis), DELETE |

---

## 4. Domain Ports

### 4.1 Output Port: `PersonRepository`

```java
// Datei: domain/port/out/PersonRepository.java
Optional<Person> findById(String personId);
Optional<Person> findByAhvNummer(AhvNummer ahvNummer);
List<Person> search(String name, String vorname, AhvNummer ahvNummer, LocalDate geburtsdatum);
Person save(Person person);
void delete(String personId);
boolean existsByAhvNummer(AhvNummer ahvNummer);
```

### 4.2 Output Port: `PersonEventPublisher`

```java
// Datei: domain/port/out/PersonEventPublisher.java
void publishPersonErstellt(String personId, String name, String vorname, AhvNummer ahv, LocalDate geburtsdatum);
void publishPersonAktualisiert(String personId, String name, String vorname);
void publishPersonGeloescht(String personId);
void publishAdresseHinzugefuegt(String personId, String adressId, AdressTyp typ, LocalDate gueltigVon);
```

---

## 5. Application Service

```java
// Datei: domain/service/PersonApplicationService.java

// --- Personenverwaltung ---

String createPerson(
    String name, String vorname, Geschlecht geschlecht,
    LocalDate geburtsdatum, String ahvNummer
);
// Prüft: ahvNummer eindeutig; publiziert person.v1.created

void updatePersonalien(
    String personId,
    String name, String vorname, Geschlecht geschlecht, LocalDate geburtsdatum
);
// Pflichtfelder dürfen nicht leer sein

void deletePerson(String personId);
// Publiziert person.v1.deleted; cascaded delete auf Adressen

Person findById(String personId);

List<Person> searchPersonen(String name, String vorname, String ahvNummer, LocalDate geburtsdatum);
// Mindestens ein Suchfeld muss befüllt sein

// --- Adressverwaltung ---

String addAdresse(
    String personId,
    AdressTyp adressTyp,
    String strasse, String hausnummer, String plz, String ort, String land,
    LocalDate gueltigVon, LocalDate gueltigBis
);
// Überlappungsprüfung im Aggregate → wirft AdressUeberschneidungException
// Publiziert person.v1.address-added

void updateAdressGueltigkeit(
    String personId, String adressId,
    LocalDate gueltigVon, LocalDate gueltigBis
);
// Überlappungsprüfung gegen andere Adressen desselben Typs
// Publiziert person.v1.address-updated

void deleteAdresse(String personId, String adressId);
```

---

## 6. REST API

```
# Personen CRUD
GET    /api/personen                              → List<PersonDto>  (Suchparameter: name, vorname, ahv, geburtsdatum)
GET    /api/personen/{id}                         → PersonDto (inkl. alle Adressen)
POST   /api/personen                              → PersonDto (201)
PUT    /api/personen/{id}                         → PersonDto
DELETE /api/personen/{id}                         → 204

# Adressen (Sub-Ressource)
GET    /api/personen/{id}/adressen                → List<AdresseDto>
GET    /api/personen/{id}/adressen?typ=WOHNADRESSE&aktuell=true  → aktuelle Adresse des Typs
POST   /api/personen/{id}/adressen                → AdresseDto (201)
PUT    /api/personen/{id}/adressen/{aid}          → AdresseDto  (nur gueltigVon/gueltigBis änderbar)
DELETE /api/personen/{id}/adressen/{aid}          → 204

# Audit-History (lesend)
GET    /api/personen/{id}/history                 → List<PersonRevisionDto>
GET    /api/personen/{id}/adressen/{aid}/history  → List<AdresseRevisionDto>
```

**DTOs:**

```java
// PersonDto
String personId, name, vorname;
String geschlecht;            // "MAENNLICH" | "WEIBLICH" | "DIVERS"
LocalDate geburtsdatum;
String ahvNummer;             // formatiert: "756.1234.5678.97"
List<AdresseDto> adressen;

// AdresseDto
String adressId, personId;
String adressTyp;             // "WOHNADRESSE" | ...
String strasse, hausnummer, plz, ort, land;
LocalDate gueltigVon, gueltigBis;
boolean aktuell;              // computed: ist heute im Gültigkeitsbereich

// PersonRevisionDto
long revisionNummer;
String revisionTyp;           // "INSERT" | "UPDATE" | "DELETE"
LocalDateTime geaendertAm;
String geaendertVon;          // aus CustomRevisionEntity
PersonDto zustand;            // Snapshot zum Revisionszeitpunkt

// AdresseRevisionDto  (analog)
```

---

## 7. UI-Screens

### 7.1 Screen 1: Personensuche (`/personen`)

**Layout:** Vollseite mit Bootstrap 5, Navbar, htmx.

```
┌─────────────────────────────────────────────────────────────────┐
│ Personenverwaltung                          [+ Neue Person]     │
├─────────────────────────────────────────────────────────────────┤
│ Suche:  [Name______] [Vorname___] [AHV-Nr.__________] [Suchen] │
├────┬───────────┬──────────┬──────────────┬───────────┬──────────┤
│ #  │ Name      │ Vorname  │ Geburtsdatum │ AHV       │ Aktionen │
├────┼───────────┼──────────┼──────────────┼───────────┼──────────┤
│ 1  │ Muster    │ Hans     │ 12.05.1978   │ 756.…97   │ ✏️ 🗑️  │
│ 2  │ Müller    │ Anna     │ 03.09.1990   │ 756.…42   │ ✏️ 🗑️  │
└────┴───────────┴──────────┴──────────────┴───────────┴──────────┘
```

**htmx-Verhalten:**
- Suchfeld: `hx-get="/personen/fragments/list?name=..."` mit `hx-trigger="keyup changed delay:300ms"` — ersetzt nur `<tbody id="personen-tabelle">`.
- `[+ Neue Person]`-Button: `hx-get="/personen/fragments/neu"` → öffnet Modal `#modal-container`.
- Formular-Submit: `hx-post="/personen/fragments"` → `hx-target="#personen-tabelle"` `hx-swap="afterbegin"`.
- Löschen (🗑️): `hx-delete="/api/personen/{id}"` mit `hx-confirm="Person wirklich löschen?"` → `hx-target="closest tr"` `hx-swap="outerHTML swap:0.3s"`.
- Bearbeiten (✏️): Navigiert zu `/personen/{id}/edit` (Full-Page).

**Validation:**
- Server gibt `422 Unprocessable Entity` mit Fehlerdetails zurück; htmx zeigt Bootstrap-Alerts im Modal.

---

### 7.2 Screen 2: Person bearbeiten (`/personen/{id}/edit`)

**Layout:** Zweispaltig (Personendaten links, Adressliste rechts) oder einspaltig gestapelt.

```
┌─────────────────────────────────────────────────────────────────┐
│ ← Zurück zur Liste                                              │
│ Person bearbeiten: Hans Muster                                  │
├───────────────────────────┬─────────────────────────────────────┤
│ PERSONALIEN               │ ADRESSEN                            │
│                           │                                     │
│ Nachname: [Muster______]  │ [+ Adresse hinzufügen]              │
│ Vorname:  [Hans________]  │                                     │
│ Geschlecht: (●M ○W ○D)   │ ┌─────────────────────────────────┐ │
│ Geburtsdatum: [12.05.1978]│ │ WOHNADRESSE (aktuell)           │ │
│ AHV-Nr: [756.1234.5678.97]│ │ Musterstr. 1, 8001 Zürich       │ │
│                           │ │ Gültig: 01.01.2020 – ∞          │ │
│ [Speichern]               │ │ [Gültigkeit bearbeiten] [Löschen]│ │
│                           │ ├─────────────────────────────────┤ │
│                           │ │ WOHNADRESSE (abgelaufen)        │ │
│                           │ │ Altstr. 5, 3000 Bern            │ │
│                           │ │ Gültig: 01.01.2015 – 31.12.2019 │ │
│                           │ │                         [Löschen]│ │
│                           │ ├─────────────────────────────────┤ │
│                           │ │ KORRESPONDENZADRESSE (aktuell)  │ │
│                           │ │ Postfach 99, 8001 Zürich        │ │
│                           │ │ Gültig: 15.03.2021 – ∞          │ │
│                           │ │ [Gültigkeit bearbeiten] [Löschen]│ │
│                           │ └─────────────────────────────────┘ │
└───────────────────────────┴─────────────────────────────────────┘
```

**htmx-Verhalten Personalien:**
- `[Speichern]`: `hx-put="/api/personen/{id}"` → `hx-target="#personalien-form"` `hx-swap="outerHTML"` → ersetzt Formular durch aktuellen Stand + Erfolgsmeldung.
- AHV-Nummer: bei Änderung nur wenn `status != AKTIV` erlaubt (Geschäftsregel).

**htmx-Verhalten Adressen:**
- `[+ Adresse hinzufügen]`: `hx-get="/personen/fragments/{id}/adresse-form"` → rendert Inline-Formular oberhalb der Liste `hx-target="#adress-liste"` `hx-swap="afterbegin"`.
- Adress-Formular `[Speichern]`: `hx-post="/api/personen/{id}/adressen"` → bei Erfolg ersetzt Formular durch neue Adresskarte; bei `409 Conflict` (Überschneidung) zeigt Fehlertext direkt im Formular.
- `[Gültigkeit bearbeiten]`: klappt Inline-Formular mit `gueltigVon` / `gueltigBis` in der Karte auf (`hx-swap="innerHTML"`).
- `[Löschen]`: `hx-delete="/api/personen/{id}/adressen/{aid}"` `hx-confirm="Adresse löschen?"` → entfernt Karte.

**Adresskarte Inline-Formular Gültigkeit:**
```
┌─────────────────────────────────┐
│ WOHNADRESSE                     │
│ Musterstr. 1, 8001 Zürich       │
│                                 │
│ Gültig von: [01.01.2020]        │
│ Gültig bis: [__________] (leer=∞)│
│ [Speichern]  [Abbrechen]        │
└─────────────────────────────────┘
```

**Validierungsfeedback:**
- Wenn `gueltigBis < gueltigVon`: Client-seitig (HTML5 `min`-Attribut) + Server-seitig.
- Wenn Überschneidung: Server gibt `409 Conflict` mit Meldung „Für diesen Adresstyp existiert bereits eine Adresse im Zeitraum [Von] – [Bis]"; htmx rendert Alert im Formular.

---

## 8. Qute-Templates

```
src/main/resources/templates/
├── layout/
│   └── base.html                      ← Bootstrap 5, Navbar, htmx CDN (bereits vorhanden)
└── personen/
    ├── list.html                      ← Vollseite Suche + Tabelle
    ├── edit.html                      ← Vollseite Bearbeiten (Personalien + Adressen)
    └── fragments/
        ├── personen-row.html          ← Eine Tabellenzeile (htmx swap)
        ├── personen-form-modal.html   ← Neu-Anlage-Formular im Modal
        ├── adresse-karte.html         ← Eine Adresskarte (inkl. Inline-Formular)
        └── adresse-form.html          ← Adresse-Anlage-Formular
```

---

## 9. UI Controller (Qute)

```java
// Datei: infrastructure/web/PersonUiController.java

GET  /personen                           → personen/list.html
GET  /personen/{id}/edit                 → personen/edit.html

// htmx-Fragmente
GET  /personen/fragments/list            → Fragment: personen-tabelle (gefiltert)
GET  /personen/fragments/neu             → Fragment: personen-form-modal
POST /personen/fragments                 → Erstellt Person via Service + rendert personen-row
GET  /personen/fragments/{id}/adresse-form  → Fragment: adresse-form (leer)
```

---

## 10. Kafka Events & ODC

| Topic | Beschreibung |
|-------|-------------|
| `person.v1.created` | Person neu angelegt |
| `person.v1.updated` | Personalien geändert |
| `person.v1.deleted` | Person gelöscht |
| `person.v1.address-added` | Adresse hinzugefügt |
| `person.v1.address-updated` | Adress-Gültigkeit geändert |

**ODC-Dateien:**
- `contracts/person.v1.created.odcontract.yaml`
- `contracts/person.v1.address-added.odcontract.yaml`

Schema `person.v1.created`:
```yaml
fields:
  - eventId: UUID
  - eventType: "PersonCreated"
  - personId: UUID
  - name: string
  - vorname: string
  - ahvNummer: string   # "756.1234.5678.97"
  - geburtsdatum: date  # ISO-8601
  - timestamp: datetime
```

---

## 11. Tests

### 11.1 Domain Unit-Tests (`src/test/domain/`)

| Testklasse | Testet |
|-----------|--------|
| `AhvNummerTest` | Gültige/ungültige AHV-Nummern, EAN-13 Prüfziffer, Formatierung |
| `PersonTest` | Erstellen, Personalien ändern, AHV-Duplikat-Guard |
| `AdresseGueltigkeitTest` | Überlappungslogik: gleichzeitig gültig, aneinanderliegend, Lücken, unbefristet |
| `PersonApplicationServiceTest` | Alle Use Cases, inkl. `AdressUeberschneidungException` |

### 11.2 Integration-Tests (`src/test/java/ch/yuno/partner/`)

- `PersonRestAdapterTest` — alle CRUD-Endpoints + Konflikt (409) bei Adress-Überschneidung
- Kein Mocking von DB oder Kafka; reale PostgreSQL + Kafka via Testcontainers

### 11.3 Audit-Tests

- Nach `updatePersonalien()`: Envers-Query prüft, dass `person_aud` eine neue Revision enthält.
- Nach `deleteAdresse()`: `adresse_aud` enthält `REVTYPE=2` (DELETE) mit `store-data-at-delete=true`.

---

## 12. Reihenfolge der Implementierung

```
Phase 1 – Domain Model
    AhvNummer (Value Object) + Validierung
    Geschlecht, AdressTyp (Enums)
    Adresse (Entity) + Überlappungslogik
    Person (Aggregate) + Geschäftsregeln
    Domain Exceptions
    ↓
Phase 2 – DB-Migration
    V2__Create_Person_Schema.sql
    ↓
Phase 3 – JPA Entities + Envers
    PersonEntity (@Audited)
    AdresseEntity (@Audited)
    CustomRevisionEntity
    PersonJpaAdapter
    ↓
Phase 4 – Ports & Application Service
    PersonRepository (Port)
    PersonEventPublisher (Port)
    PersonApplicationService
    ↓
Phase 5 – Kafka Adapter
    person-created, person-address-added Channels
    ODC-Dateien
    ↓
Phase 6 – REST Adapter
    PersonRestAdapter (CRUD + Sub-Ressource Adressen + /history)
    ↓
Phase 7 – Qute UI
    PersonUiController
    Templates (list, edit, fragments)
    ↓
Phase 8 – Tests
    Unit → Integration → Audit
```

---

## 13. Offene Fragen

| # | Frage | Default-Annahme |
|---|-------|----------------|
| 1 | Soll die AHV-Nummer editierbar sein, oder nur bei Ersterstellung? | Nur bei Ersterstellung |
| 2 | Welche RBAC-Rollen dürfen Personen anlegen/löschen? (`UNDERWRITER`, `ADMIN`?) | Offen |
| 3 | Soll `deletePerson` physisch oder logisch (Soft-Delete via Status) löschen? | Physisch (Cascade) |
| 4 | Soll die Audit-History im UI sichtbar sein, oder nur über API? | Nur API (vorerst) |
| 5 | Müssen abgelaufene Adressen gelöscht werden dürfen, oder sind sie historisch geschützt? | Löschen erlaubt |
| 6 | PLZ: Soll nur CH (4-stellig) oder auch AT/DE erlaubt sein? | Nur CH, Regex `[0-9]{4}` |
