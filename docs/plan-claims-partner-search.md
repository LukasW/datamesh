# Umsetzungsplan: Policensuche via Partner-Attribute im Claims-Service

> **Version:** 1.0.0 · **Datum:** 2026-03-22  
> **Basis:** [`docs/analysis-claims-policy-search.md`](analysis-claims-policy-search.md) (Architekturanalyse v1.1.0)  
> **Pattern-Referenz:** Bestehende `PolicySnapshot`-Implementierung in Claims + `PartnerView` in Policy + `PolicyholderView` in Billing

---

## Mengengerüst & Designentscheide

| Parameter | Wert | Design-Implikation |
|---|---|---|
| Partner | 10 Mio. | `pg_trgm` GIN-Index zwingend; `ILIKE` ohne Index = 2–5s |
| Policen | 15 Mio. | `policy_snapshot` existiert bereits; Index auf `partner_id` vorhanden |
| FNOL pro Tag | 3'500 | ~7'000–17'500 Suchanfragen/Tag → triviale DB-Last |
| Bootstrap | ~5–15 Min. | Einmalig; `person.v1.state` (compacted) mit `auto.offset.reset=earliest` |
| Read-Model-Felder | `partnerId`, `lastName`, `firstName`, `dateOfBirth`, `socialSecurityNumber` | Datensparsamkeit (Art. 5 DSGVO): nur Felder, die für eine eindeutige Identifikation zwingend nötig sind. Geburtsdatum und AHV-Nr. ermöglichen dem Sachbearbeiter eine Kontrolle bei häufigen Namen (z.B. "Müller") |

---

## Phasenübersicht

| Phase | Beschreibung | Aufwand | Abhängigkeiten |
|---|---|---|---|
| **1** | Data Layer (Domain Model + Persistence) | ~3h | – |
| **2** | Kafka Consumer (`person.v1.state`) | ~2h | Phase 1 |
| **3** | Application Service (Suchlogik) | ~1h | Phase 1 |
| **4** | UI (Controller + Templates) | ~3h | Phase 3 |
| **5** | Tests (Unit + Integration + UI) | ~3h | Phase 1–4 |
| **6** | Contracts, Quality & Observability | ~2h | Phase 2, 4 |
| | **Total** | **~14h** | |

---

## Phase 1: Data Layer (Domain + Persistence)

### 1.1 Flyway-Migration: `partner_search_view`-Tabelle

**Datei:** `claims/src/main/resources/db/migration/V6__create_partner_search_view.sql`

```sql
-- Local read model for Partner data, materialized from person.v1.state Kafka events.
-- Used for FNOL partner search without a synchronous call to Partner or Policy service.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE partner_search_view
(
    partner_id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    last_name               VARCHAR(255) NOT NULL,
    first_name              VARCHAR(255) NOT NULL,
    date_of_birth           DATE,
    social_security_number  VARCHAR(16),
    upserted_at             TIMESTAMP    NOT NULL DEFAULT now()
);

-- Trigram GIN index for performant ILIKE search on 10M+ rows.
-- Enables autocomplete queries in <30ms (vs. 2-5s sequential scan).
CREATE INDEX idx_partner_search_trgm
    ON partner_search_view
    USING GIN ((last_name || ' ' || first_name) gin_trgm_ops);

-- B-Tree index for exact AHV-Nummer lookup (unique identifier).
CREATE INDEX idx_partner_search_ssn
    ON partner_search_view (social_security_number)
    WHERE social_security_number IS NOT NULL;

-- B-Tree index for date-of-birth filter (common secondary filter after name search).
CREATE INDEX idx_partner_search_dob
    ON partner_search_view (date_of_birth);
```

**Begründung `last_name` + `first_name` statt `name` (wie Policy-Service):**
- Policy-Service speichert `name` als konkatenierten Fullname → kein Zugriff auf Einzelfelder
- Claims benötigt getrennte Felder für die Ergebnisanzeige ("**Müller**, Hans")
- `person.v1.state` liefert `name` (= Nachname) und `firstName` separat

---

### 1.2 Domain Model: `PartnerSearchView`

**Datei:** `claims/src/main/java/ch/yuno/claims/domain/model/PartnerSearchView.java`

```java
/**
 * Read Model – materialized view of a Partner, built from person.v1.state events.
 * Used by the Claims domain for FNOL partner search.
 * Contains only the fields needed for identification and search (GDPR data minimization).
 * dateOfBirth and socialSecurityNumber allow agents to verify identity
 * when multiple partners share the same name (e.g. "Müller").
 */
public record PartnerSearchView(
    String partnerId,
    String lastName,
    String firstName,
    LocalDate dateOfBirth,           // nullable – not all partners have it
    String socialSecurityNumber      // nullable – AHV-Nr. (756.XXXX.XXXX.XX)
) {
    // Compact constructor with validation
    // fullName() convenience method for display
    // formattedDateOfBirth() for UI display (dd.MM.yyyy)
    // maskedSocialSecurityNumber() → "756.****.****.97" for UI display
}
```

**Regeln:**
- Pure Java Record (kein Framework-Import) → `domain/model/`
- `partnerId` required, non-blank
- `lastName` required, non-blank
- `firstName` required, non-blank
- `dateOfBirth` optional (nullable) – nicht alle Partner haben ein erfasstes Geburtsdatum
- `socialSecurityNumber` optional (nullable) – AHV-Nummer ist freiwillig
- `maskedSocialSecurityNumber()` maskiert die Mitte: `756.1234.5678.97` → `756.****.****.97` (Datensparsamkeit in der Anzeige)

---

### 1.3 Outbound Port: `PartnerSearchViewRepository`

**Datei:** `claims/src/main/java/ch/yuno/claims/domain/port/out/PartnerSearchViewRepository.java`  
_(Ersetzt die bestehende leere Datei `PartnerViewRepository.java`)_

```java
public interface PartnerSearchViewRepository {
    void upsert(PartnerSearchView view);
    void deleteByPartnerId(String partnerId);
    List<PartnerSearchView> searchByName(String query, int maxResults);
    Optional<PartnerSearchView> findBySocialSecurityNumber(String ssn);
    Optional<PartnerSearchView> findByPartnerId(String partnerId);
}
```

| Methode | Verwendung |
|---|---|
| `upsert` | Kafka Consumer (Create/Update) |
| `deleteByPartnerId` | Kafka Consumer (Tombstone → DSGVO Art. 17) |
| `searchByName` | Application Service → UI-Autocomplete (Name/Vorname) |
| `findBySocialSecurityNumber` | Application Service → Direktsuche per AHV-Nummer (exakter Match) |
| `findByPartnerId` | Zukunftssicherung (Detail-Anzeige) |

---

### 1.4 JPA Entity: `PartnerSearchViewEntity`

**Datei:** `claims/src/main/java/ch/yuno/claims/infrastructure/persistence/PartnerSearchViewEntity.java`

Analog zu `PolicySnapshotEntity`:
- `@Entity`, `@Table(name = "partner_search_view")`
- `partner_id` als `@Id`
- `last_name`, `first_name` (VARCHAR, NOT NULL)
- `date_of_birth` (DATE, nullable)
- `social_security_number` (VARCHAR(16), nullable)
- `@PrePersist`/`@PreUpdate` setzt `upserted_at`
- Getter/Setter für alle Felder

---

### 1.5 JPA Adapter: `PartnerSearchViewJpaAdapter`

**Datei:** `claims/src/main/java/ch/yuno/claims/infrastructure/persistence/PartnerSearchViewJpaAdapter.java`

Analog zu `PolicySnapshotJpaAdapter`:

| Methode | Implementierung |
|---|---|
| `upsert` | `em.find()` → `em.merge()` (identisch zu `PolicySnapshotJpaAdapter`); alle 5 Felder setzen |
| `deleteByPartnerId` | `em.createNativeQuery("DELETE FROM partner_search_view WHERE partner_id = :id")` |
| `searchByName` | **Native SQL** (nicht JPQL!) → nutzt `pg_trgm` GIN-Index |
| `findBySocialSecurityNumber` | Native SQL auf `idx_partner_search_ssn`-Index (exakter B-Tree Match) |
| `findByPartnerId` | `em.find()` → `toDomain()` |

**Kritischer Punkt – Search Query:**

```sql
SELECT * FROM partner_search_view
WHERE (last_name || ' ' || first_name) ILIKE :term
   OR (first_name || ' ' || last_name) ILIKE :term
ORDER BY last_name, first_name
LIMIT :maxResults
```

- **Native SQL zwingend**, da JPQL die `|| ILIKE`-Kombination mit GIN nicht optimal auflöst
- `:term` = `'%' + userInput + '%'` (vom Adapter gesetzt, nicht vom User)
- `LIMIT` als Safety Net (max. 20 Ergebnisse)
- `ORDER BY` für konsistente Ergebnisreihenfolge

---

### 1.6 Erweitere `PolicySnapshotRepository` um `findByPartnerId`

**Datei:** `claims/src/main/java/ch/yuno/claims/domain/port/out/PolicySnapshotRepository.java`

Neue Methode:
```java
List<PolicySnapshot> findByPartnerId(String partnerId);
```

**Datei:** `claims/src/main/java/ch/yuno/claims/infrastructure/persistence/PolicySnapshotJpaAdapter.java`

Implementierung:
```java
@Override
public List<PolicySnapshot> findByPartnerId(String partnerId) {
    return em.createQuery(
            "SELECT e FROM PolicySnapshotEntity e WHERE e.partnerId = :pid", 
            PolicySnapshotEntity.class)
        .setParameter("pid", partnerId)
        .getResultList()
        .stream().map(this::toDomain).toList();
}
```

Index auf `partner_id` existiert bereits (`V4__create_policy_snapshot_table.sql`):
```sql
CREATE INDEX idx_policy_snapshot_partner ON policy_snapshot (partner_id);
```

---

## Phase 2: Kafka Consumer

### 2.1 Anti-Corruption Layer: `PersonStateEventTranslator`

**Datei:** `claims/src/main/java/ch/yuno/claims/infrastructure/messaging/acl/PersonStateEventTranslator.java`

Isoliert die Claims-Domain von Änderungen am Partner-Event-Schema.

```java
/**
 * ACL: Translates person.v1.state JSON into Claims domain model.
 * Isolates the Claims domain from Partner schema changes.
 */
public class PersonStateEventTranslator {
    
    sealed interface TranslationResult {
        record PartnerUpsert(PartnerSearchView view) implements TranslationResult {}
        record PartnerDeletion(String partnerId) implements TranslationResult {}
    }
    
    TranslationResult translate(String payload) throws Exception { ... }
}
```

**JSON-Felder aus `person.v1.state`** (gemäss Partner-ODC / `person.v1.state.avsc`):

| JSON-Feld | Mapping | Typ | Verwendung |
|---|---|---|---|
| `personId` | `partnerId` | String (UUID) | Primary Key |
| `name` | `lastName` | String | Nachname (im Partner-Schema heisst es `name`) |
| `firstName` | `firstName` | String | Vorname |
| `dateOfBirth` | `dateOfBirth` | String → `LocalDate` | Geburtsdatum (ISO-8601, nullable) – zur Identifikationskontrolle |
| `socialSecurityNumber` | `socialSecurityNumber` | String (nullable) | AHV-Nr. `756.XXXX.XXXX.XX` – zur eindeutigen Identifikation |
| `deleted` | → `PartnerDeletion` | boolean | DSGVO-Tombstone |

**Referenz-Pattern:** `policy/.../acl/PartnerEventTranslator.java` + `billing/.../PartnerStateConsumer.java`

---

### 2.2 Kafka Consumer: `PersonStateConsumer`

**Datei:** `claims/src/main/java/ch/yuno/claims/infrastructure/messaging/PersonStateConsumer.java`

```java
@ApplicationScoped
public class PersonStateConsumer {
    @Inject PartnerSearchViewRepository repository;
    
    @Incoming("partner-state-in")
    @Transactional
    public void onPersonState(String payload) {
        // 1. Translate via ACL
        // 2. Match on sealed interface:
        //    - PartnerUpsert → repository.upsert(view)
        //    - PartnerDeletion → repository.deleteByPartnerId(id)
        // 3. Log result
    }
}
```

**Fehlerbehandlung:** DLQ via SmallRye (`failure-strategy=dead-letter-queue`)

---

### 2.3 Kafka-Channel Konfiguration

**Datei:** `claims/src/main/resources/application.properties`

```properties
# ── Incoming: person.v1.state → local PartnerSearchView read model ────────────
mp.messaging.incoming.partner-state-in.connector=smallrye-kafka
mp.messaging.incoming.partner-state-in.topic=person.v1.state
mp.messaging.incoming.partner-state-in.value.deserializer=org.apache.kafka.common.serialization.StringDeserializer
mp.messaging.incoming.partner-state-in.group.id=claims-service-partner
mp.messaging.incoming.partner-state-in.auto.offset.reset=earliest
mp.messaging.incoming.partner-state-in.failure-strategy=dead-letter-queue
mp.messaging.incoming.partner-state-in.dead-letter-queue.topic=claims-partner-state-dlq
```

**Datei:** `claims/src/test/resources/application.properties`

```properties
# Disable partner-state channel in unit tests (analog zu policy-issued-in)
%test.mp.messaging.incoming.partner-state-in.enabled=false
```

---

## Phase 3: Application Service

### 3.1 Partner-Suche + Policy-Lookup

**Datei:** `claims/src/main/java/ch/yuno/claims/domain/service/ClaimApplicationService.java`

Neue Dependencies + Methoden:

```java
// Neue Dependency im Konstruktor:
private final PartnerSearchViewRepository partnerSearchViewRepository;

// Neue Methoden:

@RolesAllowed({"CLAIMS_AGENT", "UNDERWRITER"})
public List<PartnerSearchView> searchPartners(String nameQuery) {
    if (nameQuery == null || nameQuery.isBlank()) return List.of();
    return partnerSearchViewRepository.searchByName(nameQuery.trim(), 20);
}

@RolesAllowed({"CLAIMS_AGENT", "UNDERWRITER"})
public Optional<PartnerSearchView> findPartnerBySocialSecurityNumber(String ssn) {
    if (ssn == null || ssn.isBlank()) return Optional.empty();
    return partnerSearchViewRepository.findBySocialSecurityNumber(ssn.trim());
}

@RolesAllowed({"CLAIMS_AGENT", "UNDERWRITER"})
public List<PolicySnapshot> findPoliciesForPartner(String partnerId) {
    return policySnapshotRepository.findByPartnerId(partnerId);
}
```

**Kein neuer Service nötig** – die bestehende `ClaimApplicationService` wird erweitert, da die Suche direkt dem FNOL-Use-Case dient.

**Suchstrategie für den Sachbearbeiter:**
1. **AHV-Nummer bekannt** → `findPartnerBySocialSecurityNumber()` → exakter Treffer → direkt Policen anzeigen
2. **Nur Name bekannt** → `searchPartners()` → Trefferliste mit Geburtsdatum + maskierter AHV zur Verifizierung → Partner auswählen → Policen

---

## Phase 4: UI (Controller + Templates)

### 4.1 Neue Controller-Endpunkte

**Datei:** `claims/src/main/java/ch/yuno/claims/infrastructure/web/ClaimUiController.java`

| Endpunkt | Methode | Rückgabe | htmx-Trigger |
|---|---|---|---|
| `GET /claims/fragments/partner-search?q={term}` | `partnerSearch` | HTML-Fragment (Tabelle) | `hx-trigger="keyup changed delay:300ms"` |
| `GET /claims/fragments/partner-search?ahv={ssn}` | `partnerSearchByAhv` | HTML-Fragment (Tabelle, 0-1 Treffer) | Blur/Enter auf AHV-Feld |
| `GET /claims/fragments/partner/{partnerId}/policies` | `partnerPolicies` | HTML-Fragment (Policy-Liste) | Klick auf Partner-Zeile |

Der `partner-search`-Endpunkt prüft zuerst, ob `ahv` gesetzt ist (exakter Match), sonst fällt er auf `q` (Trigram-Suche) zurück.

Beide Endpunkte geben `TemplateInstance` zurück (Qute-Fragmente).

---

### 4.2 Neue Qute-Templates

#### `schaeden/fragments/partner-search-results.html`

```html
<!-- Ergebnistabelle der Partner-Suche (htmx-Fragment) -->
{#if partners.isEmpty}
<div class="text-muted p-2">Keine Partner gefunden.</div>
{#else}
<table class="table table-hover table-sm">
  <thead>
    <tr>
      <th>Name</th>
      <th>Vorname</th>
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
</table>
{/if}
```

**UX-Begründung für Geburtsdatum + maskierte AHV:** Bei 10 Mio. Partnern liefert eine Suche nach „Müller" potenziell hunderte Treffer. Das Geburtsdatum und die letzten Stellen der AHV-Nummer ermöglichen dem Sachbearbeiter eine sofortige visuelle Identifikation, ohne weitere Systeme konsultieren zu müssen.

#### `schaeden/fragments/policy-picker.html`

```html
<!-- Policen eines Partners (htmx-Fragment) -->
{#for policy in policies}
<div class="card mb-2 policy-card" 
     onclick="document.getElementById('policyId').value='{policy.policyId}'; ...">
  <div class="card-body p-2">
    <strong>{policy.policyNumber}</strong>
    <span class="text-muted ms-2">ab {policy.coverageStartDate}</span>
    <span class="badge bg-secondary ms-2">{policy.premium} CHF/Jahr</span>
  </div>
</div>
{/for}
```

---

### 4.3 FNOL-Formular anpassen

**Datei:** `claims/src/main/resources/templates/schaeden/form.html`

**Änderungen:**

| Vorher | Nachher |
|---|---|
| `<input type="text" name="policyId" placeholder="z.B. a1b2c3d4-e5f6-…">` | Partner-Suchfeld + Policy-Picker + verstecktes `<input name="policyId">` |

**Neues Layout:**

```
┌─────────────────────────────────────────────────┐
│  1. Partner suchen                              │
│                                                  │
│  ┌─ Tab: Name ─┬─ Tab: AHV-Nr. ────────────┐  │
│  │ [Name/Vorname eingeben...      ]          │  │
│  └───────────────────────────────────────────┘  │
│  oder                                            │
│  ┌─ Tab: AHV-Nr. ───────────────────────────┐  │
│  │ [756.____.____.__ ]  [Suchen]             │  │
│  └───────────────────────────────────────────┘  │
│                                                  │
│  ┌─ Suchergebnisse (htmx) ──────────────────┐  │
│  │ Name     Vorname  Geb.datum  AHV-Nr.     │  │
│  │ Müller   Hans     15.03.1975 756.****..97 │  │
│  │ Müller   Anna     22.08.1982 756.****..43 │  │
│  └───────────────────────────────────────────┘  │
│                                                  │
│  2. Police auswählen                            │
│  ┌─ Policy-Picker (htmx) ───────────────────┐  │
│  │ ✅ POL-00042  ab 01.01.2025  1200 CHF    │  │
│  │    POL-00089  ab 15.06.2024   800 CHF    │  │
│  └───────────────────────────────────────────┘  │
│                                                  │
│  ▸ Direkte Police-ID Eingabe (Accordion)        │
│                                                  │
│  3. Schadensdatum     [____________]            │
│  4. Beschreibung      [____________]            │
│                                                  │
│  [Schadenmeldung erfassen]  [Abbrechen]         │
└─────────────────────────────────────────────────┘
```

**Wichtig:**
- **Zwei Suchmodi** (Bootstrap Tabs oder Radio-Toggle):
  - **Tab „Name":** Autocomplete-Suche per Name/Vorname (`hx-trigger="keyup changed delay:300ms"`, Mindestlänge 2 Zeichen)
  - **Tab „AHV-Nr.":** Exakte Suche per AHV-Nummer (`hx-trigger="keyup changed delay:500ms"` oder Submit-Button). Liefert 0 oder 1 Treffer.
- Ergebnistabelle zeigt **Geburtsdatum** und **maskierte AHV-Nr.** zur Identifikationskontrolle
- Manuelles UUID-Eingabefeld bleibt als **Accordion/Fallback** ("Direkte Police-ID Eingabe") erhalten
- `<input type="hidden" name="policyId" id="policyId">` wird durch Policy-Picker gefüllt
- Mindestlänge: 2 Zeichen bei Namenssuche, vollständige AHV-Nr. bei AHV-Suche

---

## Phase 5: Tests

### 5.1 Unit Tests

| Test | Datei | Prüft |
|---|---|---|
| `PartnerSearchViewTest` | `test/domain/model/PartnerSearchViewTest.java` | Record-Validierung (null, blank); nullable `dateOfBirth`/`socialSecurityNumber`; `maskedSocialSecurityNumber()` → `756.****.****.97`; `formattedDateOfBirth()` |
| `PersonStateEventTranslatorTest` | `test/infrastructure/messaging/acl/PersonStateEventTranslatorTest.java` | JSON-Parsing inkl. `dateOfBirth` + `socialSecurityNumber` (present/null), Tombstone, malformed Input |
| `ClaimApplicationServiceTest` (erweitern) | `test/domain/service/ClaimApplicationServiceTest.java` | `searchPartners()`, `findPartnerBySocialSecurityNumber()`, `findPoliciesForPartner()` mit Mocks |

### 5.2 Integration Tests

| Test | Datei | Setup | Prüft |
|---|---|---|---|
| `PartnerSearchViewJpaAdapterIT` | `test/infrastructure/persistence/PartnerSearchViewJpaAdapterIT.java` | `@QuarkusTest` + Testcontainers PG | UPSERT (inkl. dateOfBirth, SSN), Trigram-Suche ("Müller"), AHV-Suche (exakt), Delete, max Results, Umlaute, null-Felder |
| `PersonStateConsumerIT` | `test/infrastructure/messaging/PersonStateConsumerIT.java` | `@QuarkusTest` + InMemory Kafka Channel | Event mit allen Feldern → Upsert, Event ohne dateOfBirth/SSN → Upsert mit null, Tombstone → Delete |

### 5.3 Playwright UI Tests

| Test | Datei | Prüft |
|---|---|---|
| `PartnerSearchUiTest` | `test/infrastructure/web/playwright/PartnerSearchUiTest.java` | Name-Suche → Ergebnisse mit Geb.datum + AHV angezeigt → Partner auswählen → Policen → Submit; AHV-Suche → Einzeltreffer → Policen → Submit |

**Page Objects:**
- `PartnerSearchWidget` (neues Page Object)
- `ClaimFormPage` (bestehend, erweitern um Policy-Picker-Interaktionen)

---

## Phase 6: Contracts, Quality & Observability

### 6.1 ODC Consumer Contract

**Datei:** `claims/src/main/resources/contracts/person.v1.state.consumer.odcontract.yaml`

```yaml
apiVersion: v2.3.0
kind: DataContract
id: claims-person-state-consumer-v1
version: 1.0.0
info:
  title: Person State Consumer (Claims)
  description: >
    Claims service consumes person.v1.state (compacted topic) to materialize
    a local partner_search_view for FNOL partner search.
    Consumed fields: personId, name, firstName, dateOfBirth,
    socialSecurityNumber, deleted. dateOfBirth and socialSecurityNumber
    are needed for agent-side identity verification in the FNOL search UI.
  owner: claims-team@insurance.example.com
  domain: claims
  tags: ["claims", "partner", "read-model", "ecst"]
```

### 6.2 Soda Core Check

**Datei:** `infra/soda/checks/claims-partner-search.yml`

```yaml
checks for partner_search_view:
  - row_count > 0:
      name: partner_search_view is not empty
  - missing_count(partner_id) = 0
  - duplicate_count(partner_id) = 0
  - freshness(upserted_at) < 1440  # 24h in minutes
```

### 6.3 Grafana Monitoring

**Datei:** `infra/grafana/dashboards/claims-partner-search.json`

| Panel | Metrik | Alert-Schwelle |
|---|---|---|
| Consumer Lag | `kafka_consumer_lag{group="claims-service-partner"}` | > 10'000 |
| Search Latenz p99 | `http_server_requests_seconds{uri="/claims/fragments/partner-search"}` | > 200ms |
| Row Count | `pg_stat_user_tables_n_live_tup{relname="partner_search_view"}` | < 1'000 (nach Bootstrap) |
| DLQ Messages | `kafka_topic_partition_offset{topic="claims-partner-state-dlq"}` | > 0 |

### 6.4 Business Spec aktualisieren

**Datei:** `claims/specs/business_spec.md`

Ergänzungen:
- Neues Consumed Topic: `person.v1.state`
- Neues Read-Model: `partner_search_view`
- Aktualisierte Web UI-Beschreibung (Partner-Suche im FNOL-Formular)
- Aktualisierte Integration Points-Tabelle

---

## Dateiübersicht (alle Änderungen)

### Neue Dateien

| # | Datei | Phase |
|---|---|---|
| 1 | `claims/src/main/resources/db/migration/V6__create_partner_search_view.sql` | 1 |
| 2 | `claims/src/main/java/ch/yuno/claims/domain/model/PartnerSearchView.java` | 1 |
| 3 | `claims/src/main/java/ch/yuno/claims/infrastructure/persistence/PartnerSearchViewEntity.java` | 1 |
| 4 | `claims/src/main/java/ch/yuno/claims/infrastructure/persistence/PartnerSearchViewJpaAdapter.java` | 1 |
| 5 | `claims/src/main/java/ch/yuno/claims/infrastructure/messaging/acl/PersonStateEventTranslator.java` | 2 |
| 6 | `claims/src/main/java/ch/yuno/claims/infrastructure/messaging/PersonStateConsumer.java` | 2 |
| 7 | `claims/src/main/resources/templates/schaeden/fragments/partner-search-results.html` | 4 |
| 8 | `claims/src/main/resources/templates/schaeden/fragments/policy-picker.html` | 4 |
| 9 | `claims/src/test/java/ch/yuno/claims/domain/model/PartnerSearchViewTest.java` | 5 |
| 10 | `claims/src/test/java/ch/yuno/claims/infrastructure/messaging/acl/PersonStateEventTranslatorTest.java` | 5 |
| 11 | `claims/src/test/java/ch/yuno/claims/infrastructure/persistence/PartnerSearchViewJpaAdapterIT.java` | 5 |
| 12 | `claims/src/test/java/ch/yuno/claims/infrastructure/messaging/PersonStateConsumerIT.java` | 5 |
| 13 | `claims/src/test/java/ch/yuno/claims/infrastructure/web/playwright/PartnerSearchUiTest.java` | 5 |
| 14 | `claims/src/test/java/ch/yuno/claims/infrastructure/web/playwright/pages/PartnerSearchWidget.java` | 5 |
| 15 | `claims/src/main/resources/contracts/person.v1.state.consumer.odcontract.yaml` | 6 |
| 16 | `infra/soda/checks/claims-partner-search.yml` | 6 |
| 17 | `infra/grafana/dashboards/claims-partner-search.json` | 6 |

### Geänderte Dateien

| # | Datei | Änderung | Phase |
|---|---|---|---|
| 1 | `claims/src/main/java/ch/yuno/claims/domain/port/out/PartnerViewRepository.java` | Rename → `PartnerSearchViewRepository.java`; Interface-Methoden definieren | 1 |
| 2 | `claims/src/main/java/ch/yuno/claims/domain/port/out/PolicySnapshotRepository.java` | `findByPartnerId(String)` hinzufügen | 1 |
| 3 | `claims/src/main/java/ch/yuno/claims/infrastructure/persistence/PolicySnapshotJpaAdapter.java` | `findByPartnerId` implementieren | 1 |
| 4 | `claims/src/main/java/ch/yuno/claims/domain/service/ClaimApplicationService.java` | `searchPartners()` + `findPoliciesForPartner()` + neue Dependency | 3 |
| 5 | `claims/src/main/java/ch/yuno/claims/infrastructure/web/ClaimUiController.java` | 2 neue Fragment-Endpunkte + Template-Injection | 4 |
| 6 | `claims/src/main/resources/templates/schaeden/form.html` | Partner-Suche + Policy-Picker statt UUID-Eingabe | 4 |
| 7 | `claims/src/main/resources/application.properties` | Kafka-Channel `partner-state-in` hinzufügen | 2 |
| 8 | `claims/src/test/resources/application.properties` | `partner-state-in.enabled=false` für Unit-Tests | 2 |
| 9 | `claims/src/test/java/ch/yuno/claims/domain/service/ClaimApplicationServiceTest.java` | Tests für neue Methoden | 5 |
| 10 | `claims/specs/business_spec.md` | Consumed Topics, Read-Model, UI-Beschreibung | 6 |

---

## Abhängigkeitsgraph

```
Phase 1: Data Layer
  ├── V6 Migration ──────────────────────────────────────┐
  ├── PartnerSearchView (Record) ────────────────────────┤
  ├── PartnerSearchViewRepository (Port) ────────────────┤
  ├── PartnerSearchViewEntity (JPA) ─────────────────────┤
  ├── PartnerSearchViewJpaAdapter ───────────────────────┤
  └── PolicySnapshotRepository.findByPartnerId ──────────┤
                                                         │
Phase 2: Kafka Consumer ◄────────────────────────────────┘
  ├── PersonStateEventTranslator (ACL)
  ├── PersonStateConsumer
  └── application.properties (Kafka Config)
                │
Phase 3: Application Service ◄───────────────────────────
  └── ClaimApplicationService (searchPartners, findPoliciesForPartner)
                │
Phase 4: UI ◄────────────────────────────────────────────
  ├── ClaimUiController (neue Endpunkte)
  ├── partner-search-results.html (Fragment)
  ├── policy-picker.html (Fragment)
  └── form.html (Umbau)
                │
Phase 5: Tests ◄─────────────────────────────────────────
  ├── Unit Tests (parallel zu Phase 1-3)
  ├── Integration Tests (nach Phase 2)
  └── Playwright UI Tests (nach Phase 4)
                │
Phase 6: Contracts & Observability ◄─────────────────────
  ├── ODC Contract
  ├── Soda Core Check
  ├── Grafana Dashboard
  └── Business Spec Update
```

---

## Risiken & Mitigationen

| Risiko | Wahrscheinlichkeit | Auswirkung | Mitigation |
|---|---|---|---|
| `pg_trgm`-Extension nicht in PostgreSQL-Image aktiviert | Mittel | Migration schlägt fehl | `CREATE EXTENSION IF NOT EXISTS pg_trgm;` in Migration; ggf. `shared_preload_libraries` in `postgresql.conf` ergänzen |
| Bootstrap dauert >15 Min. bei 10 Mio. Records | Niedrig | Erster Start ohne Suchfunktion | Readiness-Probe prüft `partner_search_view`-Count > 0; UI zeigt "Daten werden geladen..." |
| `person.v1.state`-Schema ändert sich (Feldnamen) | Niedrig | Consumer-Fehler | ACL (`PersonStateEventTranslator`) isoliert Claims von Schema-Änderungen; DLQ fängt Fehler ab |
| Eventual Consistency: Partner existiert, aber noch nicht im Read-Model | Sehr niedrig | Sachbearbeiter findet Partner nicht | Fallback: Manuelle UUID-Eingabe bleibt als Accordion im Formular |
| GIN-Index-Aufbau blockiert Writes bei grossem Bulk-Load | Niedrig | Langsamer Bootstrap | `CREATE INDEX CONCURRENTLY` in separater Migration (nach Bulk-Load) |

---

## Definition of Done

- [ ] Partner-Suche per Name/Vorname im FNOL-Formular funktioniert (<30ms p99)
- [ ] Policy-Auswahl nach Partner-Selektion funktioniert
- [ ] `policyId` wird automatisch ins Formular eingetragen
- [ ] Manueller UUID-Fallback ist weiterhin verfügbar
- [ ] Tombstone-Events löschen Partner aus Read-Model (DSGVO)
- [ ] Unit Tests: Domain Model, ACL, Application Service
- [ ] Integration Tests: JPA-Adapter (Trigram-Suche), Kafka Consumer
- [ ] Playwright UI Test: End-to-End FNOL mit Partner-Suche
- [ ] ODC Consumer Contract dokumentiert
- [ ] Soda Core Check grün
- [ ] Consumer-Lag-Monitoring in Grafana
- [ ] Business Spec aktualisiert
















