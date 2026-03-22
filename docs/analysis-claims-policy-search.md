# Architekturanalyse: Policensuche via Partner-Attribute im Claims-Service

> **Version:** 1.1.0 · **Datum:** 2026-03-22  
> **Scope:** Greenfield-Analyse (bestehende ADRs bewusst ignoriert)  
> **Autor:** Architecture Review

### Mengengerüst

| Kennzahl | Wert |
|---|---|
| Partner (Personen) | **10 Mio.** |
| Policen | **15 Mio.** |
| Schadenmeldungen pro Tag | **3'500** (≈ 440/Stunde, ≈ 7/Minute in Bürozeiten) |
| Geschätzte Suchvorgänge pro FNOL | 2–5 (Autocomplete-Iterationen) |
| **Erwartete Suchanfragen pro Tag** | **7'000–17'500** |

---

## 1. Problemstellung

Ein Schadensachbearbeiter möchte bei der Erfassung einer Schadenmeldung (FNOL) die betroffene Police finden – nicht über eine technische UUID, sondern über **Partner-Attribute** (Name, Vorname, Geburtsdatum, AHV-Nummer). Dafür muss die Claims-App auf Personen- und Policendaten zugreifen, die in zwei anderen Bounded Contexts liegen (Partner, Policy).

### Ist-Zustand

| Aspekt | Status |
|---|---|
| Claims-DB hat `policy_snapshot` | ✅ Enthält `policyId`, `policyNumber`, `partnerId`, `productId`, `coverageStartDate`, `premium` |
| Claims-DB hat Partner-Daten | ❌ Keine Partner-Attribute (kein Name, keine Adresse) |
| FNOL-Formular | Manuelle Eingabe einer `policyId` (UUID) – extrem schlechte UX |

**Fazit:** Aktuell muss der Sachbearbeiter die Policy-UUID aus einem anderen System kopieren. Für eine praxistaugliche Lösung muss die Claims-App eine Suche über Personen-Attribute anbieten und die zugehörigen Policen anzeigen.

---

## 2. Lösungsansätze

### Ansatz A: Microfrontend-Integration

Die Partner- und Policensuche wird als eigenständiges UI-Fragment bereitgestellt – entweder vom **Policy-Service** (der bereits ein `PartnerView`-Read-Model besitzt und eine Partner-Picker-Suche implementiert hat) oder als dediziertes Microfrontend.

```
┌──────────────────── Claims UI ────────────────────┐
│                                                    │
│  ┌──────────────────────────────────────────────┐  │
│  │  << Microfrontend / iframe / htmx-include >> │  │
│  │  Policy Service: Partner-Picker + Policen    │  │
│  │  → Suche nach Name/Vorname/AHV/Geb.datum    │  │
│  │  → Zeigt Policen des Partners                │  │
│  │  → Callback: policyId an Claims-Formular     │  │
│  └──────────────────────────────────────────────┘  │
│                                                    │
│  Claims FNOL-Formular (eigenes UI)                 │
│  policyId = [vorausgefüllt aus Picker]             │
│  Schadensdatum, Beschreibung …                     │
└────────────────────────────────────────────────────┘
```

**Integrationsvarianten:**

| Variante | Technik | Komplexität |
|---|---|---|
| **A1: htmx-Fragment** | Policy-Service liefert ein HTML-Fragment (`/policen/fragments/partner-picker`), Claims lädt es via `hx-get` | Niedrig |
| **A2: iframe** | Policy-Service UI in iframe eingebettet, `postMessage` für Callback | Mittel |
| **A3: Web Component** | Eigenständige Web Component, deployed als JS-Bundle | Hoch |

### Ansatz B: Datenreplikation (Event-Carried State Transfer)

Die Claims-App baut **eigene Read-Models** für Partner- und Policendaten auf, indem sie die bestehenden Kafka-Events konsumiert. Die Suche läuft vollständig lokal.

```
person.v1.state (Kafka, compacted)
  → Claims Consumer → partner_view (Claims DB)

policy.v1.issued (Kafka)
  → Claims Consumer → policy_snapshot (Claims DB, existiert bereits)

Sachbearbeiter sucht "Müller" → Query gegen partner_view
  → JOIN/Lookup gegen policy_snapshot (partnerId)
  → Anzeige: Policen von "Hans Müller"
  → Auswahl → policyId vorausgefüllt im FNOL-Formular
```

---

## 3. Tabellarische Gegenüberstellung

| Kriterium | Ansatz A: Microfrontend | Ansatz B: Datenreplikation | Gewichtung |
|---|---|---|---|
| **User Experience** | | | **Hoch** |
| Antwortzeiten | ⚠️ Abhängig von Netzwerk-Latenz zum Policy-Service; bei iframe zusätzlicher Overhead; htmx-Fragment ~50-200ms RTT | ✅ Lokale DB-Query, <10ms; identische Performance wie bestehende Claims-Suche | |
| Visuelle Konsistenz | ⚠️ Risiko von CSS-Konflikten (iframe isoliert, aber fremd; htmx-Fragment teilt Styles, aber Layout-Abweichungen möglich) | ✅ Volle Kontrolle über Look & Feel; einheitliches Design mit Claims-UI | |
| Offline-/Ausfallverhalten | ❌ Bei Policy-Service-Ausfall ist die Partner-Suche nicht verfügbar → FNOL blockiert | ✅ Suche funktioniert auch bei Policy-/Partner-Service-Ausfall (lokale Daten) | |
| **Entkopplung vs. Konsistenz** | | | **Hoch** |
| Datenkonsistenz | ✅ Immer aktuell (Single Source of Truth, Live-Query) | ⚠️ Eventual Consistency (Delay typisch <1s bei Debezium CDC); bei Neustart Bootstrap via State-Topic | |
| Laufzeit-Entkopplung | ❌ Synchrone Abhängigkeit: Claims → Policy (und transitiv Policy → Partner) zur Laufzeit | ✅ Vollständig entkoppelt; kein Cross-Service-Call zur Laufzeit | |
| Datensouveränität | ✅ Partner-Daten verlassen den Partner-Kontext nicht (nur im Policy-Service vorhanden) | ⚠️ Partner-PII wird in eine weitere Datenbank repliziert (DSGVO-Implikation: weitere Löschpflicht, Crypto-Shredding-Scope erweitert sich) | |
| **Komplexität** | | | **Mittel** |
| Implementierungsaufwand | ⚠️ htmx-Fragment: niedrig (Policy-Service hat Partner-Picker bereits); iframe/WebComponent: mittel bis hoch | ⚠️ Kafka-Consumer + DB-Tabelle + UI-Logik: mittlerer Aufwand, aber bewährtes Pattern (Policy-Service macht das bereits für PartnerView) | |
| Infrastruktur-Overhead | ⚠️ CORS-Konfiguration, ggf. Auth-Token-Forwarding (OIDC), Shared Session | ✅ Kein zusätzlicher Infrastruktur-Overhead (Kafka + DB vorhanden) | |
| Cross-Team-Koordination | ❌ Policy-Team muss ein stabiles Fragment-API bereitstellen und wartbar halten; Vertragsabhängigkeit auf UI-Ebene | ✅ Nur ODC-Vertrag auf Kafka-Ebene nötig (existiert bereits für `person.v1.state`) | |
| **Wartbarkeit** | | | **Hoch** |
| Schema-Änderungen (Partner) | ✅ Transparent – Claims-App ist nicht betroffen, solange Policy-Service sein Fragment aktualisiert | ⚠️ Claims-Consumer und Read-Model-Schema müssen angepasst werden (aber: ODC-Versionierung handhabt das) | |
| Schema-Änderungen (Policy) | ⚠️ Betrifft das Fragment-API (neues Feld = Fragment-Update nötig) | ⚠️ Bestehender `policy_snapshot`-Consumer muss erweitert werden (gleicher Aufwand) | |
| UI-Änderungen | ❌ Fragmentiertes Styling: Claims-Team kann Partner-Picker nicht eigenständig anpassen | ✅ Volle Kontrolle über alle UI-Komponenten; einheitliche Weiterentwicklung | |
| Testbarkeit | ⚠️ Integration-Tests benötigen laufenden Policy-Service oder aufwändige Mocks | ✅ Unit- und Integrationstests rein lokal (Testcontainers für Postgres + Kafka) | |
| **Skalierbarkeit** | | | **Hoch** |
| Last-Verteilung | ⚠️ Policy-Service wird zum Bottleneck: 7'000–17'500 zusätzliche Suchanfragen/Tag aus Claims on top der eigenen Last | ✅ Last verteilt sich auf Claims-eigene DB-Instanz; keine Mehrbelastung des Policy-Service | |
| Horizontale Skalierung | ⚠️ Policy-Service muss mitskalieren, wenn Claims-Suchvolumen steigt; skaliert man Claims horizontal, multipliziert sich die Last auf Policy | ✅ Claims-Service skaliert unabhängig; lokale DB skaliert vertikal oder via Read-Replicas | |
| Speicherbedarf Read-Model | n/a | ⚠️ 10 Mio. Partner-Rows (~500 MB) + 15 Mio. Policy-Snapshots (~2 GB) in Claims-DB; handhabbar für PostgreSQL | |
| Bootstrap-Zeit (Cold Start) | n/a | ⚠️ State-Topic mit 10 Mio. Einträgen: ~3–10 Minuten (abhängig von Netzwerk/Consumer-Throughput); nicht Sekunden | |
| Suchperformance bei 10 Mio. Partnern | ⚠️ Policy-Service-DB muss ebenfalls Volltextsuche auf 10 Mio. Rows bewältigen | ⚠️ Erfordert spezialisierten Index (`pg_trgm` GIN-Index); naives `ILIKE '%müller%'` ist bei 10 Mio. Rows zu langsam | |

---

## 4. Detailanalyse der kritischen Aspekte

### 4.1 UX-Perspektive

Die **Schadenmeldung ist ein zeitkritischer Prozess** – ein Sachbearbeiter hat den Kunden oft am Telefon. Bei **3'500 FNOL pro Tag** (≈ 7/Minute in Bürozeiten) ist dies ein Massengeschäft mit hoher Taktung.

| UX-Szenario | Microfrontend | Datenreplikation |
|---|---|---|
| Sachbearbeiter tippt „Müller" → Autocomplete (10 Mio. Partner) | 200-500ms (Netzwerk + DB-Query auf 10 Mio. Rows im Policy-Service) | 5-30ms (lokaler `pg_trgm`-Index auf 10 Mio. Rows) |
| 440 Sachbearbeiter suchen gleichzeitig (Stosszeit) | ⚠️ Policy-Service unter Concurrent Load: eigene UI + Claims-Suchen; Antwortzeiten degradieren | ✅ Last auf Claims-eigener DB; keine Konkurrenz mit Policy-Service-Nutzern |
| Policy-Service ist gerade im Deployment | ❌ Suche nicht verfügbar → Sachbearbeiter muss warten | ✅ Suche funktioniert unverändert |
| Partner wurde vor 2 Sekunden erfasst | ✅ Sofort sichtbar | ⚠️ Sichtbar nach <1s (Debezium CDC + Kafka → Consumer) |

Die Eventual-Consistency-Lücke bei Ansatz B ist in der Praxis **vernachlässigbar**: Eine Schadenmeldung referenziert eine *bestehende* Police eines *bestehenden* Partners – beide existieren typischerweise seit Tagen/Wochen/Jahren. Bei 10 Mio. Partnern ist die Wahrscheinlichkeit, dass ein Sachbearbeiter einen Partner sucht, der vor weniger als einer Sekunde angelegt wurde, praktisch null.

### 4.2 DSGVO / Datenschutz

Ansatz B repliziert PII (Name, Vorname, ggf. Geburtsdatum) in die Claims-DB. Das hat Konsequenzen:

| Aspekt | Implikation |
|---|---|
| Verarbeitungsverzeichnis (Art. 30 DSGVO) | Claims-Service muss als weiterer Verarbeiter eingetragen werden |
| Recht auf Löschung (Art. 17) | Crypto-Shredding (ADR-009) muss Claims-DB einschliessen; `person.v1.state`-Tombstone → Claims-Consumer löscht lokalen Partner-View |
| Datensparsamkeit (Art. 5 Abs. 1c) | Nur die für die Suche und **eindeutige Identifikation** zwingend notwendigen Felder replizieren: Name, Vorname, Geburtsdatum, AHV-Nummer. Keine Adresse, kein Geschlecht. AHV-Nummer wird in der UI maskiert dargestellt (`756.****.****.97`). |

**Bewertung:** Handhabbar. Die zusätzlichen Felder (Geburtsdatum, AHV-Nr.) sind durch den Use Case gerechtfertigt: Bei 10 Mio. Partnern liefert eine Namenssuche nach „Müller" hunderte Treffer. Ohne Identifikationsmerkmale kann der Sachbearbeiter den richtigen Partner nicht verifizieren. Das Pattern existiert bereits im Policy-Service (`PartnerView`). Der Claims-Service repliziert eine vergleichbare Projektion mit maskierter AHV-Anzeige.

### 4.3 Architektonische Konsistenz

| Prinzip | Microfrontend | Datenreplikation |
|---|---|---|
| Self-Contained System (SCS) | ❌ Verletzt – Claims-UI ist zur Laufzeit von Policy-Service abhängig | ✅ Erfüllt – Claims ist autonom |
| Data Mesh (Datensouveränität) | ✅ Daten bleiben beim Owner | ⚠️ Read-Model ist explizit erlaubt (Event-Carried State Transfer ist ein Data-Mesh-Pattern) |
| Hexagonal Architecture | ⚠️ UI-Schicht ist an externe API gekoppelt | ✅ Saubere Port/Adapter-Trennung |
| Conway's Law | ❌ Claims-Team ist für UI-Feature von Policy-Team abhängig | ✅ Claims-Team kann Feature eigenständig liefern |

### 4.4 Skalierungsanalyse (10 Mio. Partner / 15 Mio. Policen)

Die Grössenordnung hat substanzielle Auswirkungen auf beide Ansätze – aber **deutlich unterschiedliche**.

#### Speicher & Storage

| Datensatz | Zeilen | Ø Zeilengrösse | Geschätztes Tabellenvolumen | Index-Overhead |
|---|---|---|---|---|
| `partner_search_view` (Claims) | 10 Mio. | ~110 Bytes (UUID + 2× varchar + date + varchar(16)) | ~1.1 GB | ~1.8 GB (B-Tree + GIN `pg_trgm` + B-Tree SSN + B-Tree DOB) |
| `policy_snapshot` (Claims, existiert) | 15 Mio. | ~120 Bytes | ~1.8 GB | ~600 MB (B-Tree auf `partner_id`) |
| **Total zusätzlicher Claims-DB-Bedarf** | | | **~5.3 GB** | |

**Bewertung:** 5–6 GB sind für eine PostgreSQL-Instanz trivial. Selbst mit Headroom (WAL, VACUUM, Bloat) bleibt der Platzbedarf unter 25 GB – kein relevanter Kostenfaktor.

#### Suchperformance (kritisch!)

Ein naives `ILIKE '%müller%'` auf 10 Mio. Rows erzeugt einen **Sequential Scan** – bei ~800 MB Tabellenvolumen dauert das 2–5 Sekunden. Das ist **inakzeptabel** für Autocomplete.

**Lösung: `pg_trgm` GIN-Index (PostgreSQL Trigram Extension)**

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_partner_search_trgm 
    ON partner_search_view 
    USING GIN ((last_name || ' ' || first_name) gin_trgm_ops);
```

| Szenario | Ohne Index | Mit `pg_trgm` GIN | Verbesserung |
|---|---|---|---|
| `WHERE last_name ILIKE '%müller%'` (10 Mio. Rows) | 2–5s (Seq Scan) | **5–30ms** (Index Scan) | 100–200× |
| Autocomplete „Mül" (Prefix + Trigram) | 3–8s | **2–10ms** | 300× |
| Concurrent 20 Queries (Stosszeit) | DB-CPU-Saturation | Stabil <50ms p99 | ✅ |

**Für Ansatz A (Microfrontend):** Das gleiche Index-Problem existiert in der Policy-Service-DB – dort muss ebenfalls ein `pg_trgm`-Index auf der `PartnerView`-Tabelle erstellt werden. Bei Microfrontend-Architektur kommt die Last allerdings von **zwei Quellen** (Policy-UI + Claims-UI), was den Policy-Service stärker belastet.

#### Bootstrap-Strategie (Cold Start)

Bei 10 Mio. Partnern im `person.v1.state`-Topic ist ein Bootstrap **keine Sekundenangelegenheit** mehr:

| Phase | Dauer (geschätzt) | Beschreibung |
|---|---|---|
| Kafka Read (10 Mio. × ~500 Bytes) | 2–4 Min. | ~5 GB Daten lesen; abhängig von Kafka-Throughput (typisch 50–100 MB/s pro Consumer) |
| DB-Inserts (Batch, 10 Mio. Rows) | 3–8 Min. | Bulk-UPSERT via `INSERT … ON CONFLICT`; abhängig von DB-Performance |
| Index-Rebuild (`pg_trgm` GIN) | 1–3 Min. | GIN-Index-Aufbau auf 10 Mio. Rows |
| **Total Bootstrap-Zeit** | **~5–15 Min.** | Einmalig bei Erstinstallation oder DB-Reset |

**Mitigationen:**
- Bootstrap-Consumer mit **Batch-Size 5'000** und **prepared UPSERT-Statements**
- `UNLOGGED`-Tabelle während Bootstrap, anschliessend `ALTER TABLE … SET LOGGED`
- GIN-Index erst **nach** dem Bulk-Load erstellen (PostgreSQL empfohlene Praxis)
- Incremental Updates nach Bootstrap: ~50–200 Partner-Mutationen/Tag sind trivial

**Für Ansatz A (Microfrontend):** Kein Bootstrap-Problem im Claims-Service – aber der Policy-Service hat das **identische Bootstrap-Problem** für seine eigene `PartnerView`. Das Problem wird nicht vermieden, nur verschoben.

#### Kafka-Durchsatz (Incremental Updates)

| Metrik | Wert |
|---|---|
| Partner-Mutationen pro Tag (geschätzt bei 10 Mio.) | ~1'000–5'000 (Adressänderungen, Namenswechsel) |
| Policy-Events pro Tag | ~2'000–10'000 (Neuabschlüsse, Kündigungen, Änderungen) |
| Claims-Events pro Tag | ~3'500 (FNOL) + ~3'500 (Settlements/Rejections) |
| **Gesamte Eventlast für Claims-Consumer** | **~10'000–20'000 Events/Tag** |

**Bewertung:** 20'000 Events/Tag sind ~0.2 Events/Sekunde – absolut trivial für einen Kafka-Consumer. Selbst 10× mehr wäre kein Problem.

---

## 5. Hybride Alternative (Ansatz C)

Es gibt eine elegante Mittelweg-Variante, die die Stärken beider Ansätze kombiniert:

**Datenreplikation für die Suche + minimaler Datenumfang**

```
person.v1.state → Claims Consumer → partner_search_view
                                     (partnerId, lastName, firstName)

policy_snapshot existiert bereits → (partnerId als FK)

Claims UI:
  Suchfeld "Partner suchen" → lokale DB-Query gegen partner_search_view
  → Treffer zeigt zugehörige Policen (JOIN policy_snapshot ON partnerId)
  → Auswahl → policyId ins FNOL-Formular
```

Vorteile:
- **Autonomie:** Kein Cross-Service-Call zur Laufzeit
- **Performance:** Lokale Suche in <10ms (mit `pg_trgm`-Index, auch bei 10 Mio. Partnern)
- **Datensparsamkeit:** Nur `partnerId`, `lastName`, `firstName`, `dateOfBirth`, `socialSecurityNumber` (~1.1 GB für 10 Mio. Rows + ~1.8 GB Index). Geburtsdatum und AHV-Nr. sind für die eindeutige Identifikation bei häufigen Namen zwingend nötig.
- **Bewährtes Pattern:** Identisch zum `PartnerView` im Policy-Service
- **DSGVO-konform:** Tombstone aus `person.v1.state` triggert Löschung
- **Testbar:** Rein lokal mit Testcontainers
- **Skalierbar:** Claims-DB trägt eigene Last unabhängig vom Policy-Service

---

## 6. Empfehlung

### 🏆 Empfehlung: **Ansatz B / C – Datenreplikation (Event-Carried State Transfer)**

Die Datenreplikation mit minimalem Read-Model ist der klar überlegene Ansatz für diesen Use Case:

| Entscheidungsfaktor | Begründung |
|---|---|
| **SCS-Autonomie** | Die Claims-App bleibt ein vollständig autonomes, unabhängig deploybares System. Kein Ausfall einer anderen Domäne blockiert die Schadenmeldung. |
| **UX-Performance** | Lokale Suche in 5–30ms (mit `pg_trgm`-Index auf 10 Mio. Rows) vs. 200–500ms Netzwerk-Roundtrip. Bei 3'500 FNOL/Tag und telefonischen Schadenmeldungen ist das ein messbarer Produktivitätsvorteil. |
| **Team-Autonomie** | Das Claims-Team kann Feature, Design und Rollout eigenständig steuern, ohne auf das Policy-Team zu warten. |
| **Bewährtes Pattern** | Der Policy-Service nutzt exakt dasselbe Pattern (`PartnerView` via `person.v1.state`). Die Infrastruktur (Kafka, Debezium, State-Topic) existiert bereits. |
| **Resilienz** | Kein Single Point of Failure. Die Suche funktioniert auch während Deployments oder Ausfällen anderer Services. |
| **Eventual Consistency akzeptabel** | Partner und Policen existieren typischerweise seit Tagen/Wochen. Die Sub-Sekunden-Verzögerung durch Kafka ist für diesen Use Case irrelevant. |

### Gegen Microfrontend

Der Microfrontend-Ansatz wäre in einem SPA-basierten System mit dezentralen Teams und häufigen UI-Änderungen sinnvoll. In dieser **SSR-Architektur (Qute + htmx)** bringt er jedoch erhebliche Nachteile:

- **Laufzeit-Kopplung** widerspricht dem zentralen Architekturprinzip (Autonomie)
- **CSS/HTML-Integration** zwischen Qute-Templates verschiedener Services ist fragil
- **Auth-Token-Forwarding** (OIDC) zwischen Services ist zusätzlicher Infrastruktur-Overhead
- **Kein Team-Vorteil** – bei nur einem Claims-Team und einem Policy-Team entsteht eher Koordinations-Overhead als Wiederverwendung

### Umsetzungsempfehlung (High-Level)

| Schritt | Beschreibung |
|---|---|
| 1. | `partner_search_view`-Tabelle in Claims-DB anlegen (`partner_id PK`, `last_name`, `first_name`); `pg_trgm`-Extension aktivieren |
| 2. | **GIN-Index** auf `(last_name \|\| ' ' \|\| first_name)` mit `gin_trgm_ops` anlegen – zwingend für performante Suche auf 10 Mio. Rows |
| 3. | Kafka-Consumer für `person.v1.state` im Claims-Service implementieren (analog zu `PolicyIssuedConsumer`); **Batch-UPSERT** (5'000er-Batches) für Bootstrap-Phase |
| 4. | `PartnerSearchViewRepository`-Port + JPA-Adapter; Query: `WHERE (last_name \|\| ' ' \|\| first_name) ILIKE :term` (nutzt GIN-Index automatisch) |
| 5. | Claims-UI: htmx-basiertes Suchfeld mit Debounce (300ms), Ergebnisliste mit Partner + zugehörigen Policen (JOIN `policy_snapshot ON partner_id`), max. 20 Ergebnisse |
| 6. | FNOL-Formular: `policyId` wird aus Suchergebnis vorausgefüllt statt manuell eingegeben |
| 7. | ODC-Contract für `person.v1.state` im Claims-Service als Consumer dokumentieren |
| 8. | Soda-Core-Check: Konsistenzprüfung `partner_search_view`-Zählung vs. `person.v1.state`-Topic-Offset |
| 9. | Monitoring: Grafana-Dashboard für Bootstrap-Fortschritt, Consumer-Lag und Query-Latenz (p50/p99) |

---

## 7. Fazit

In einer **Event-Driven, Self-Contained-Systems-Architektur** ist die Datenreplikation via Event-Carried State Transfer das **architektonisch konsistente, performantere und resilientere** Pattern. Die Microfrontend-Integration löst ein organisatorisches Problem (UI-Wiederverwendung), das in diesem Kontext nicht existiert – und erzeugt dafür eine Laufzeit-Kopplung, die dem Kernprinzip der Plattform widerspricht.

> **Kurzformel:** Repliziere Daten, nicht UIs. Jede Domäne besitzt ihre Suchlogik – das ist Data Mesh.








