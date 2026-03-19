# Testleitfaden – Sachversicherung Datamesh Platform

> Schritt-für-Schritt-Anleitung zum manuellen Testen aller Benutzeroberflächen der Plattform – von der fachlichen Dateneingabe bis zu den Administrations- und Auswertungs-UIs.

---

## Inhaltsverzeichnis

1. [Voraussetzungen & Stack starten](#1-voraussetzungen--stack-starten)
2. [Partner Service – Personenverwaltung](#2-partner-service--personenverwaltung)
3. [Product Service – Produktverwaltung](#3-product-service--produktverwaltung)
4. [Policy Service – Policenverwaltung](#4-policy-service--policenverwaltung)
5. [REST-APIs – Swagger UI](#5-rest-apis--swagger-ui)
6. [AKHQ – Kafka-Inspektion](#6-akhq--kafka-inspektion)
7. [Data Product Portal](#7-data-product-portal)
8. [Airflow – Scheduling & Orchestrierung](#8-airflow--scheduling--orchestrierung)
9. [DataHub – Metadaten-Katalog](#9-datahub--metadaten-katalog)
10. [Debezium Connect – CDC-Status](#10-debezium-connect--cdc-status)
11. [Durchgehendes Testszenario](#11-durchgehendes-testszenario)

---

## 1. Voraussetzungen & Stack starten

### Stack komplett starten

```zsh
./build.sh        # Baut alle Services, erstellt Images, startet podman-compose
# oder, wenn Images bereits vorhanden sind:
podman compose up -d
```

### Bereitschaft prüfen

Alle Services sind bereit, wenn folgende URLs antworten:

| Service | URL | Erwartete Antwort |
|---|---|---|
| Partner Service | <http://localhost:9080> | UI lädt |
| Product Service | <http://localhost:9081> | UI lädt |
| Policy Service | <http://localhost:9082> | UI lädt |
| AKHQ | <http://localhost:8085> | Topic-Liste sichtbar |
| Data Product Portal | <http://localhost:8090> | Katalog-Übersicht |
| Airflow | <http://localhost:8091> | Login-Seite |
| DataHub | <http://localhost:9002> | Login-Seite |

Der Stack braucht beim ersten Start ca. 2–3 Minuten, bis alle Abhängigkeiten (Kafka, Datenbanken, Debezium) hochgefahren sind.

---

## 2. Partner Service – Personenverwaltung

**URL:** <http://localhost:9080>

**Zweck:** Der Partner Service ist das zentrale Personenregister der Plattform. Er verwaltet natürliche Personen (Versicherungsnehmer) mit ihren Stammdaten und Adressen. Jede Person erhält eine eindeutige `personId`, die von allen anderen Services (insbesondere Policy) als stabiler Fremdschlüssel verwendet wird. Der Partner Service ist der einzige Service, der Personendaten schreiben darf – alle anderen Services lesen diese Daten ausschliesslich aus dem Kafka-Eventstream (Daten-Souveränität nach Data Mesh).

### 2.1 Personenliste & Suche

1. <http://localhost:9080> aufrufen – die Seite leitet automatisch auf **«Personenverwaltung»** weiter.
2. Die Tabelle zeigt alle erfassten Personen (beim ersten Start leer, ausser Dev-Daten sind aktiv).
3. **Suche testen:** Einen Namen in das Feld «Name» eingeben – die Liste filtert sofort (Live-Suche via htmx, 300 ms Debounce, kein Seitenneuladen).
4. AHV-Nummer suchen: Format `756.XXXX.XXXX.XX` eingeben – die Suche filtert nach Sozialversicherungsnummer.
5. **Paginierung:** Bei mehr als 20 Einträgen erscheint eine Seitennavigation am Tabellenende.

### 2.2 Neue Person erfassen

1. Schaltfläche **«+ Neue Person»** klicken – ein Modal-Fenster öffnet sich.
2. Felder ausfüllen:
   - **Nachname** (Pflicht)
   - **Vorname** (Pflicht)
   - **Geschlecht:** Männlich / Weiblich / Divers
   - **Geburtsdatum** (Pflicht, Datepicker)
   - **AHV-Nummer** (optional, Format: `756.1234.5678.90`)
3. **«Speichern»** klicken.
4. Die neue Person erscheint sofort in der Tabelle (kein Seitenneuladen).

> **Fachliche Regel:** Die AHV-Nummer ist ein unveränderliches Identifikationsmerkmal einer natürlichen Person. Sie kann nach der Ersterstellung nicht mehr geändert werden und erscheint im Bearbeitungsformular daher als schreibgeschütztes Feld.

### 2.3 Person bearbeiten

1. In der Tabelle **«Bearbeiten»** klicken – öffnet die Detail-Seite `/persons/{id}/edit`.
2. **Personalien-Karte (links):**
   - Nachname, Vorname, Geschlecht, Geburtsdatum ändern
   - **«Speichern»** – nur die Karte wird per htmx aktualisiert, eine Erfolgsmeldung erscheint kurz
3. **Adressen-Karte (rechts):**
   - **«+ Adresse hinzufügen»** – ein Formular erscheint direkt in der Karte (kein Modal)
   - Felder: Strasse, PLZ, Ort, Land, Adresstyp (Hauptwohnsitz / Postadresse / Geschäftsadresse)
   - **Gültigkeitszeitraum (von / bis):** Adressen sind zeitlich versioniert. Wird eine neue Adresse desselben Typs mit einem späteren Startdatum erfasst, wird die bestehende automatisch auf das Vortags-Datum zugeschnitten. So bleibt die vollständige Adresshistorie erhalten – relevant für DSGVO-Auskunftspflichten und Audit-Trails.
   - Bestehende Adressen können einzeln bearbeitet oder gelöscht werden

### 2.4 Person löschen

1. In der Tabelle **«Löschen»** klicken.
2. Browser-Bestätigungsdialog erscheint: «Person 'Vorname Name' wirklich löschen?»
3. Bei Bestätigung verschwindet die Zeile mit Animation aus der Tabelle.

> **Hinweis:** Das Löschen erzeugt einen `PersonDeleted`-Event in Kafka. Downstream-Services (z.B. Policy) können diesen konsumieren und ihre lokalen Read Models entsprechend aktualisieren.

### 2.5 Was im Hintergrund passiert

Nach jeder Mutation (anlegen / ändern / löschen) schreibt der Partner Service einen Eintrag in die **Outbox-Tabelle** in `partner_db`. Debezium liest diese Einträge via WAL-CDC und publiziert sie auf den entsprechenden Kafka-Topic (`person.v1.created`, `person.v1.updated`, etc.). Dieses Muster – **Transactional Outbox** – stellt sicher, dass DB-Schreibvorgang und Event-Publishing atomar erfolgen: Fällt Kafka kurzzeitig aus, gehen keine Events verloren. Dies kann in AKHQ verifiziert werden (→ [Abschnitt 6](#6-akhq--kafka-inspektion)).

---

## 3. Product Service – Produktverwaltung

**URL:** <http://localhost:9081>

**Zweck:** Der Product Service definiert das Produktkatalog der Versicherung – welche Versicherungsprodukte (z.B. Hausrat, Motorfahrzeug, Haftpflicht) angeboten werden, welche Deckungstypen sie umfassen und zu welcher Basisprämie. Er ist damit die fachliche Grundlage für die Policenverwaltung: Eine Police kann nur für ein aktives Produkt ausgestellt werden. Produktdefinitionen sind Verantwortung des Underwriting-Teams und ändern sich selten – Änderungen an bestehenden Produkten werden als Events publiziert, damit der Policy Service sein lokales Read Model aktuell hält.

### 3.1 Produktliste & Suche

1. <http://localhost:9081> aufrufen – öffnet **«Produktverwaltung»**.
2. Tabellenspalten: Name, Sparte, Basisprämie (CHF), Status (ACTIVE / DEPRECATED).
3. **Suche nach Name:** Freitext-Eingabe, Live-Filter.
4. **Suche nach Sparte:** Dropdown mit allen Produktlinien (z.B. HOUSEHOLD, MOTOR, LIABILITY).

### 3.2 Neues Produkt anlegen

1. **«+ Neues Produkt»** klicken – Modal öffnet sich.
2. Felder ausfüllen:
   - **Produktname** (Pflicht)
   - **Sparte / Produktlinie** (Dropdown, Pflicht) – bestimmt die fachliche Kategorie (z.B. `HOUSEHOLD` für Hausrat)
   - **Basisprämie in CHF** (Pflicht, numerisch) – Ausgangswert für die Prämienberechnung; individuelle Policenprämien können davon abweichen
3. **«Speichern»** – Produkt erscheint sofort in der Liste mit Status **ACTIVE**.

### 3.3 Produkt bearbeiten

1. **«Bearbeiten»** klicken – öffnet Detail-Seite `/products/{id}/edit`.
2. Name, Sparte und Basisprämie können geändert werden.
3. **Produkt veralteten:** Schaltfläche «Veralteten» (Deprecated) setzt den Status auf **DEPRECATED** – das Produkt kann danach nicht mehr für neue Policen verwendet werden. Bestehende Policen auf diesem Produkt bleiben davon unberührt.

### 3.4 Produkt löschen

1. **«Löschen»** in der Tabelle klicken.
2. Bestätigungsdialog: «Produkt 'Name' wirklich löschen?»
3. Bei Bestätigung wird die Zeile animiert entfernt.

> **Hinweis:** Ein Produkt kann nur gelöscht werden, wenn keine aktiven Policen darauf referenzieren.

### 3.5 Was im Hintergrund passiert

Analog zum Partner Service schreibt der Product Service Domain-Events in seine Outbox. Debezium publiziert diese auf `product.v1.defined`, `product.v1.updated` etc. Der Policy Service konsumiert diese Events und baut einen lokalen **ProductView** auf – ohne direkten DB-Zugriff auf `product_db`. Das ist das **Shared-Nothing-Prinzip** (ADR-004): Kein Service liest direkt in der Datenbank eines anderen Services.

---

## 4. Policy Service – Policenverwaltung

**URL:** <http://localhost:9082>

**Zweck:** Der Policy Service ist der fachliche Kern der Plattform. Er verwaltet den vollständigen Lebenszyklus eines Versicherungsvertrags (Police): von der Erstellung als Entwurf über die Aktivierung bis zur Kündigung oder zum Ablauf. Ein Vertrag verbindet immer genau eine Person (Partner) mit genau einem Produkt und enthält eine oder mehrere Deckungen (z.B. Feuerschaden, Wasserschaden). Der Policy Service ist der einzige Service, der Policen-Daten schreiben darf – er hält aber gleichzeitig lokale Read Models von Partner- und Produktdaten, die er aus Kafka bezieht, um keine synchronen Abhängigkeiten zu den anderen Services zu haben.

### 4.1 Policenliste & Suche

1. <http://localhost:9082> aufrufen – öffnet **«Policenverwaltung»**.
2. Tabellenspalten: Policen-Nr., Partner, Produkt, Status, Beginn, Prämie/Jahr.
3. **Status-Dropdown:** Entwurf / Aktiv / Gekündigt / Abgelaufen.
4. **Suche nach Policen-Nummer** oder **Partner-ID** – Live-Filter.

> **Datenverfügbarkeit:** Partner- und Produktnamen werden aus dem lokalen Read Model des Policy Service angezeigt – dieser befüllt sich durch Konsumieren der Kafka-Events. Falls eine UUID statt eines Namens erscheint, hat der Policy Service den entsprechenden Event noch nicht verarbeitet. Kurz warten und Seite neu laden.

### 4.2 Neue Police anlegen (Entwurf)

**Voraussetzung:** Mindestens eine Person (Partner) und ein aktives Produkt müssen existieren.

1. **«+ Neue Police»** klicken – Modal öffnet sich.
2. **Partner suchen:**
   - Im Suchfeld einen Namen eingeben – live Vorschau erscheint (Suche im lokalen PartnerView des Policy Service, nicht im Partner Service selbst).
   - Gewünschte Person aus der Liste auswählen – sie wird als Partner übernommen.
3. **Produkt wählen:** Dropdown mit allen aktiven Produkten (aus dem lokalen ProductView).
4. **Deckungsbeginn** (Datum) eingeben.
5. **«Erstellen»** – Police wird mit Status **ENTWURF** angelegt und erhält eine sequenzielle Policen-Nummer (z.B. `POL-2026-000001`). Im Entwurf-Status sind noch keine Prämien verbindlich und kein Versicherungsschutz besteht.

### 4.3 Police bearbeiten & aktivieren

1. In der Liste **«Details»** klicken – öffnet `/policies/{id}/edit`.
2. **Policendetails-Karte (links):**
   - Partner, Produkt, Deckungsbeginn und Prämie können im ENTWURF-Status geändert werden.
   - Schaltfläche **«Aktivieren»** erscheint nur im ENTWURF-Status. Die Aktivierung ist der rechtlich bindende Akt: Ab diesem Moment besteht Versicherungsschutz, die Prämie wird fällig, und der `policy.v1.issued`-Event wird publiziert – z.B. damit ein zukünftiger Billing Service die Rechnung erstellen kann.
   - Bei Klick: Bestätigungsdialog → Police wechselt zu **AKTIV**.
3. **Deckungen-Karte (rechts):**
   - **«+ Deckung hinzufügen»** – Formular erscheint in der Karte. Eine Deckung definiert konkret, gegen welches Risiko versichert wird (z.B. Feuer, Wasser, Diebstahl), zu welcher Versicherungssumme und mit welchem Selbstbehalt.
   - Deckungen können per Mülleimer-Icon entfernt werden (nur im ENTWURF oder AKTIV-Status).

### 4.4 Police kündigen

1. In der Detailansicht einer **aktiven** Police erscheint die Schaltfläche **«Kündigen»**.
2. Alternativ in der Liste direkt per «Kündigen»-Schaltfläche.
3. Bestätigungsdialog: «Police 'POL-...' kündigen?»
4. Bei Bestätigung: Status wechselt zu **GEKÜNDIGT**, der `policy.v1.cancelled`-Event wird publiziert. Downstream-Services (z.B. Billing) können diesen Event nutzen, um allfällige Rückerstattungen auszulösen.

### 4.5 Was im Hintergrund passiert

Der Policy Service publiziert Events **direkt** zu Kafka – ohne Debezium-Umweg. Das ist möglich, weil Quarkus mit SmallRye Reactive Messaging und dem Transactional Outbox Pattern sicherstellt, dass Events und DB-Writes atomar behandelt werden. Jede Statusänderung erzeugt einen Event auf den Topics `policy.v1.issued`, `policy.v1.changed`, `policy.v1.cancelled`. Diese Events können in AKHQ live verfolgt werden.

---

## 5. REST-APIs – Swagger UI

**Zweck:** Alle drei Domain-Services exponieren neben der Server-seitigen UI auch eine vollständige REST-API. Diese wird primär für maschinelle Kommunikation (Service-zu-Service, externe Systeme) genutzt. Die Swagger UI erlaubt es, diese API interaktiv zu erkunden und zu testen – ohne ein eigenes HTTP-Client-Tool (z.B. curl oder Postman) einrichten zu müssen.

| Service | Swagger URL |
|---|---|
| Partner | <http://localhost:9080/swagger-ui> |
| Product | <http://localhost:9081/swagger-ui> |
| Policy | <http://localhost:9082/swagger-ui> |

### Verwendung

1. Swagger UI aufrufen.
2. Einen Endpunkt aufklappen (z.B. `POST /api/persons`).
3. **«Try it out»** klicken.
4. JSON-Body eingeben und **«Execute»** klicken.
5. Response-Code und Body werden direkt angezeigt.

### Nützliche Endpunkte zum Testen

**Partner Service:**

- `POST /api/persons` – Person anlegen
- `GET /api/persons/{id}` – Person abrufen
- `PATCH /api/persons/{id}` – Personalien aktualisieren
- `POST /api/persons/{id}/addresses` – Adresse hinzufügen

**Product Service:**

- `POST /api/products` – Produkt definieren
- `GET /api/products?productLine=HOUSEHOLD` – Nach Sparte filtern
- `POST /api/products/{id}/deprecate` – Produkt veralteten

**Policy Service:**

- `POST /api/policies` – Police im Entwurf erstellen
- `POST /api/policies/{id}/activate` – Police aktivieren
- `POST /api/policies/{id}/cancel` – Police kündigen
- `POST /api/policies/{id}/coverages` – Deckung hinzufügen
- `GET /api/policies/{id}/coverage-check` – Deckungsprüfung (für Claims): synchroner REST-Call, den der Claims Service beim FNOL verwendet, um zu prüfen ob zum Schadenzeitpunkt eine gültige Deckung bestand

---

## 6. AKHQ – Kafka-Inspektion

**URL:** <http://localhost:8085>

**Zweck:** AKHQ (Another Kafka HQ) ist das Beobachtungswerkzeug für den Kafka-Eventstream. Da alle Domain-Services asynchron via Kafka kommunizieren, ist AKHQ das zentrale Debugging-Instrument: Hier sieht man, ob Events ankommen, ob Consumer-Groups verarbeiten oder zurückfallen, und welche Avro-Schemas registriert sind. AKHQ ist reines Dev/Ops-Tooling und hat keine Funktion im produktiven Datenfluss.

### 6.1 Topics durchsuchen

1. Im linken Menü **«Topics»** wählen.
2. Liste aller Topics erscheint (z.B. `person.v1.created`, `policy.v1.issued`, `product.v1.state`).
3. Topic anklicken → **«Messages»**-Tab → alle publizierten Events mit Timestamp, Key und Payload.

**Empfohlene Topics zum Beobachten:**

| Topic | Wann erscheinen neue Einträge |
|---|---|
| `person.v1.created` | Nach Anlage einer Person |
| `person.v1.updated` | Nach Personenmutation |
| `person.v1.state` | Kompaktierter State – immer neuester Zustand pro Person |
| `product.v1.defined` | Nach Produktanlage |
| `product.v1.state` | Kompaktierter State – immer neuester Produktzustand |
| `policy.v1.issued` | Nach Policen-Aktivierung |
| `policy.v1.cancelled` | Nach Kündigung |

> **Kompaktierte Topics (`*.state`):** Im Gegensatz zu Delta-Topics, die jede einzelne Änderung enthalten, speichert ein kompaktierter Topic pro Key (= `personId` bzw. `productId`) immer nur den **neuesten** Zustand. Das ermöglicht neuen Consumern, den aktuellen Stand aller Datensätze zu lesen, ohne die gesamte Eventhistorie wiedergeben zu müssen – ähnlich einem Snapshot. Dieses Muster heisst **Event-Carried State Transfer (ECST)**.

### 6.2 Consumer Groups überwachen

1. **«Consumer Groups»** im Menü.
2. Hier ist z.B. `policy-service` als Consumer-Group sichtbar – sie konsumiert `partner.v1.*` und `product.v1.*`.
3. **Lag** zeigt an, wie viele Events der Consumer noch nicht verarbeitet hat. Ein Lag von 0 bedeutet, dass der Service auf dem neuesten Stand ist. Ein wachsender Lag weist auf Verarbeitungsprobleme hin.

### 6.3 Schemas anzeigen

1. **«Schema Registry»** im Menü.
2. Alle registrierten Avro-Schemas der Topics sind hier aufgelistet mit Versionsverlauf.
3. Schema-Details anklicken → JSON-Darstellung des Avro-Schemas.

> **Warum Schema Registry?** Avro-Schemas garantieren, dass Producer und Consumer dasselbe Datenformat verstehen. Die Schema Registry verhindert «Schema-Breaking-Changes»: Ein neues Schema wird nur akzeptiert, wenn es rückwärtskompatibel mit dem vorherigen ist (ADR-002). So werden stille Datenverluste verhindert, wenn ein Consumer ein älteres Schema erwartet.

### 6.4 Typischer Test-Workflow

1. In Partner UI eine neue Person anlegen.
2. In AKHQ den Topic `person.v1.created` aufrufen – der neue Event sollte innerhalb weniger Sekunden erscheinen (via Debezium CDC).
3. Den `person.v1.state` Topic prüfen – hier sollte dieselbe Person als aktueller State erscheinen (Key = `personId`).

---

## 7. Data Product Portal

**URL:** <http://localhost:8090>

**Zweck:** Das Data Product Portal ist die Self-Service-Plattform für alle, die Daten konsumieren wollen – Analysten, andere Entwicklungsteams, der CDO. Es beantwortet die Fragen: Welche Datensätze gibt es? Wer besitzt sie? Wie gut ist ihre Qualität? Wie kann ich darauf zugreifen? Das Portal ist kein Eingabe-UI, sondern ein reines Lesewerkzeug – es aggregiert Informationen aus den ODC-Contracts, der Schema Registry und der Analytics-Datenbank.

### 7.1 Datenprodukt-Katalog (Startseite)

**URL:** <http://localhost:8090>

Die Startseite zeigt vier Kennzahlen:

- **Anzahl Data Products** (alle ODC-beschriebenen Topics) – jeder Kafka-Topic ist ein «Data Product» im Data-Mesh-Sinne: ein klar beschriebenes, mit SLO versehenes, eigenverantwortetes Datenangebot
- **Anzahl Domains** – zeigt, wie viele autonome Teams aktiv Daten publizieren
- **Topics mit PII-Daten** (personenbezogen, DSGVO-relevant) – für Compliance-Monitoring wichtig; PII-Topics erfordern besondere Zugriffskontrollen
- **Topics mit SLA ≥ 95 %** – misst, wie viele Datenprodukte ihre zugesagte Verfügbarkeitsquote erfüllen

**Tabelle der Data Products:**

- Spalten: Topic, Domain, Owner, Output Port, Felder, Tags, Quality Score, Schema ✓
- **Domain-Filter:** Schaltflächen oben (Alle / partner / product / policy) filtern die Tabelle.
- **Quality Score:** Basiert auf den SodaCL-Qualitätsprüfungen im ODC-Contract (z.B. Null-Checks, Duplikatsprüfungen). Grün ≥ 98 %, Gelb ≥ 95 %, Rot < 95 %.
- **Schema ✓:** Zeigt an, ob das Avro-Schema in der Schema Registry registriert ist – Voraussetzung für typsicheren Konsum.

### 7.2 Einzelnes Datenprodukt

1. Topic-Link in der Tabelle anklicken (z.B. `person.v1.created`).
2. Detailseite zeigt: Beschreibung, Owner, Tags, Felddefinitionen aus dem ODC, Zugriffsmuster.

> **Open Data Contract (ODC):** Jedes Kafka-Topic wird durch eine YAML-Datei (`*.odcontract.yaml`) formal beschrieben. Das ODC enthält: Felder und Typen, Owner, SLAs, Datenschutzklassifikationen (PII, GDPR) und SodaCL-Qualitätsprüfungen. Es ist der verbindliche «API-Vertrag» zwischen Datenproduzenten und -konsumenten (ADR-002).

### 7.3 Cross-Domain Analytics Demo

**URL:** <http://localhost:8090/demo>

**Zweck:** Diese Ansicht demonstriert den Data-Mesh-«Wow-Moment»: Eine einzige SQL-Abfrage auf `analytics.mart_portfolio_summary` verbindet Daten aus drei unabhängigen Domain-Teams (Partner, Produkt, Police) – ohne ETL-Ticket, ohne Schema-Verhandlung, ohne direkten DB-Zugriff. Die Daten sind in der Analytics-Datenbank verfügbar, weil jedes Team seine Daten als Kafka-Events publiziert hat und der Platform Consumer diese gesammelt hat.

**Tabelle `analytics.mart_portfolio_summary`:**

- Spalten: Produktlinie, Produkt, Aktive Policen, Total Prämie (CHF), Ø Prämie (CHF)

> **Voraussetzung für Daten:** Platform Consumer und dbt müssen laufen und mindestens eine aktivierte Police muss existieren. Wenn «Noch keine Daten verfügbar» angezeigt wird: Zuerst eine Person, ein Produkt und eine aktive Police in den Domain-UIs anlegen.

### 7.4 Cross-Domain Lineage

**URL:** <http://localhost:8090/lineage>

**Zweck:** Die Lineage-Ansicht zeigt, woher Daten kommen und wohin sie fliessen – als interaktiver Graph. Das ist für Entwickler wichtig, wenn ein Fehler in einem Analysebericht gesucht wird («Woher kommt dieser Wert?»), und für Compliance («Welche Systeme verarbeiten personenbezogene Daten?»).

Zeigt:

- Welche Kafka-Topics von welchem Service produziert werden
- Welche Services welche Topics konsumieren
- Datenprodukt-zu-Datenprodukt-Abhängigkeiten

### 7.5 Governance Dashboard

**URL:** <http://localhost:8090/governance>

**Zweck:** Das Governance Dashboard zeigt, ob die Plattform ihre eigenen Architekturregeln einhält – automatisiert, ohne manuelle Prüfung. Es ist das Werkzeug für Architekten und Data Engineers, um die Datenqualität und Schema-Gesundheit auf einen Blick zu beurteilen.

Zeigt den automatisierten Governance-Status:

1. **Architekturregeln** (oben): Drei grüne Karten bestätigen die eingehaltenen ADRs (Shared Nothing, Kafka als einziger Ausgang, Governance as Code).
2. **Schema Registry:** Tabelle aller registrierten Avro-Schemas mit Versions- und Kompatibilitätsstatus.
   - «Connected» = Schema Registry läuft und ist erreichbar.
   - Spalte «Status»: OK (grün), Partial (gelb, nur eine Version vorhanden → Kompatibilität noch nicht prüfbar), Weak (rot, NONE-Kompatibilität → Breaking Changes sind erlaubt, was ein Risiko darstellt).
3. **ODC Contract Quality:** Quality Score aller Data Products auf einen Blick – basiert auf den im ODC definierten SodaCL-Prüfungen, die gegen die tatsächlichen Kafka-Daten ausgeführt werden.
4. **Access Patterns:** Wie jedes Datenprodukt konsumiert werden kann (Kafka Subscribe, REST).

---

## 8. Airflow – Scheduling & Orchestrierung

**URL:** <http://localhost:8091>

**Login:** `admin` / `admin` (Standardkonfiguration)

**Zweck:** Apache Airflow ist der Prozessautomatisierer der Analytics-Plattform. Er stellt sicher, dass dbt-Transformationen regelmässig ausgeführt werden – nicht nur einmalig beim Container-Start. In einem produktiven Umfeld würde Airflow z.B. nächtlich alle dbt-Modelle neu berechnen, Datenqualitätsprüfungen auslösen und Fehler per E-Mail melden. In dieser Plattform orchestriert Airflow primär den Übergang von Rohdaten (geliefert vom Platform Consumer) zu aufbereiteten Analysemodellen (erstellt durch dbt).

### 8.1 DAG-Übersicht

**DAG (Directed Acyclic Graph)** ist die Airflow-Bezeichnung für einen Workflow – eine Abfolge von Aufgaben mit definierten Abhängigkeiten.

1. Nach Login erscheint die **DAG-Liste**.
2. Vorhandene DAGs (abhängig vom Airflow-Image):
   - `dbt_daily_run` – Tägliche dbt-Transformation: liest Rohdaten aus `platform_db`, baut Staging- und Mart-Modelle (z.B. `mart_portfolio_summary`)
   - `data_quality_check` – Führt ODC-Qualitätsprüfungen durch und meldet Abweichungen
3. Toggle (Schalter links) aktiviert/deaktiviert einen DAG.

### 8.2 DAG manuell auslösen

1. DAG-Namen anklicken → DAG-Detailansicht.
2. **«▶ Trigger DAG»** (Play-Button oben rechts) klicken.
3. Optional: Konfigurationsparameter als JSON übergeben.
4. Unter **«Grid»** oder **«Graph»** den Ablauf beobachten – grüne Tasks sind erfolgreich, rote fehlgeschlagen.

### 8.3 Ausführungslog prüfen

1. In der Grid-Ansicht auf ein Task-Kästchen klicken.
2. **«Logs»** wählen – vollständiges stdout/stderr der Task-Ausführung.
3. Bei dbt-Tasks: Hier ist der vollständige dbt-Output mit Modell-Laufzeiten sichtbar.

### 8.4 Verbindungen konfigurieren

**Verbindungen** (Connections) speichern Zugangsdaten zu externen Systemen, so dass DAGs keine Passwörter im Code enthalten müssen.

1. Menü **«Admin → Connections»**.
2. Wichtige Verbindungen:
   - `platform_db` – PostgreSQL-Verbindung zur Analyse-Datenbank (wird von dbt-Tasks verwendet)
   - `kafka_default` – Kafka-Bootstrap-Server (für Monitoring-Tasks)

---

## 9. DataHub – Metadaten-Katalog

**URL:** <http://localhost:9002>

**Login:** `datahub` / `datahub`

**Zweck:** DataHub ist der Enterprise-Metadaten-Katalog. Während das Data Product Portal ein leichtgewichtiges, projektspezifisches Portal ist, bietet DataHub eine umfassendere Lösung: automatische Schema-Extraktion aus der Schema Registry, vollständige Datenlinage (von Kafka-Topic über dbt bis zum Dashboard), fein granulare Zugriffskontrollen und eine durchsuchbare Entitätsdatenbank. DataHub ist das Werkzeug für grössere Organisationen, in denen viele Teams Daten produzieren und konsumieren und die Discoverability von Daten kritisch ist.

### 9.1 Entitäten durchsuchen

1. Suchfeld oben – beliebigen Begriff eingeben (z.B. «person», «policy», «partner»).
2. Filter links: nach Entitätstyp (DataSet, DataFlow, Dashboard, ...) filtern.
3. Topics erscheinen als **DataSets** mit Beschreibung aus dem ODC-Contract.

### 9.2 Schema eines Topics anzeigen

1. Topic-Entität anklicken (z.B. `person.v1.created`).
2. Tab **«Schema»** – alle Avro-Felder mit Typ und Beschreibung, automatisch aus der Schema Registry bezogen.
3. Tab **«Properties»** – ODC-Metadaten (Owner, Domain, Tags, SLAs), die beim Ingestion-Lauf eingelesen wurden.

### 9.3 Lineage visualisieren

1. Entität öffnen → Tab **«Lineage»**.
2. Interaktiver Graph zeigt upstream (Produzent) und downstream (Konsumenten) des Topics.
3. Durch den Graphen navigieren: dbt-Modelle erscheinen als nachgelagerte Entitäten – so ist der Weg von einem Kafka-Event bis zu einem fertigen Analysebericht vollständig nachvollziehbar.

### 9.4 Ingestion neu auslösen

Beim Stack-Start ingested `datahub-ingest` automatisch alle Schemas und ODC-Metadaten. Nach Änderungen muss die Ingestion manuell angestossen werden:

```zsh
# Ingestion-Container manuell starten:
podman compose run --rm datahub-ingest
```

Danach erscheinen die aktualisierten Metadaten in DataHub.

---

## 10. Debezium Connect – CDC-Status

**URL:** <http://localhost:8083/connectors>

**Zweck:** Debezium ist das Herzstück des Transactional-Outbox-Musters. Es liest den PostgreSQL Write-Ahead-Log (WAL) der Domain-Datenbanken und übersetzt DB-Änderungen in Kafka-Events – zuverlässig, ohne Datenverlust auch bei Kafka-Ausfällen. Für Partner- und Product-Service läuft je ein Connector (`partner-outbox-connector`, `product-outbox-connector`), der die Outbox-Tabelle überwacht und neue Einträge als Kafka-Nachrichten veröffentlicht. Fällt Debezium aus, werden keine Events mehr in Kafka publiziert – auch wenn die Domain-UIs weiterhin funktionieren.

> Dies ist eine reine REST-API (JSON), kein grafisches Interface.

### 10.1 Aktive Connectoren abfragen

```zsh
curl http://localhost:8083/connectors | python3 -m json.tool
```

Erwartet werden (je nach Compose-Konfiguration):

- `partner-outbox-connector`
- `product-outbox-connector`

### 10.2 Connector-Status prüfen

```zsh
curl http://localhost:8083/connectors/partner-outbox-connector/status | python3 -m json.tool
```

**Erwarteter Status:**

```json
{
  "name": "partner-outbox-connector",
  "connector": { "state": "RUNNING" },
  "tasks": [{ "id": 0, "state": "RUNNING" }]
}
```

Wenn `state` nicht `RUNNING` ist, läuft kein CDC → neue Events vom Partner Service landen nicht in Kafka, und der Policy Service erhält keine aktualisierten Partner-Daten.

### 10.3 Connector neu registrieren (falls nötig)

```zsh
# Wird automatisch durch debezium-init beim Stack-Start gemacht.
# Manuell:
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @infra/debezium/partner-outbox-connector.json
```

---

## 11. Durchgehendes Testszenario

Dieses Szenario verknüpft alle Services zu einem vollständigen Geschäftsvorfall und verifiziert den gesamten Event-Fluss – von der Dateneingabe in der Domain-UI bis zur Cross-Domain-Auswertung im Portal.

### Ziel

Eine Hausratsversicherungs-Police für eine neue Kundin anlegen, aktivieren und den Event-Flow durch alle Systeme verfolgen.

---

### Schritt 1 – Person erfassen (Partner Service)

1. <http://localhost:9080> → **«+ Neue Person»**
2. Eingaben:
   - Nachname: `Müller`
   - Vorname: `Anna`
   - Geschlecht: `Weiblich`
   - Geburtsdatum: `1985-04-20`
   - AHV-Nummer: `756.1234.5678.90` (optional)
3. **«Speichern»**
4. Die `personId` aus der Tabelle notieren (z.B. `a1b2c3d4-...`).

**Verifizieren in AKHQ** (<http://localhost:8085>):

- Topic `person.v1.created` → neuer Event mit `firstName: "Anna"` sollte erscheinen. Dieser Event entsteht durch Debezium, das die Outbox-Tabelle in `partner_db` liest.
- Topic `person.v1.state` → Key = `personId`, voller Personen-State (kompaktiert).

---

### Schritt 2 – Produkt definieren (Product Service)

1. <http://localhost:9081> → **«+ Neues Produkt»**
2. Eingaben:
   - Name: `Hausrat Basic`
   - Sparte: `HOUSEHOLD`
   - Basisprämie: `480`
3. **«Speichern»**
4. Die `productId` notieren.

**Verifizieren in AKHQ:**

- Topic `product.v1.defined` → neuer Event erscheint. Dieser wird vom Policy Service konsumiert, um seinen lokalen ProductView zu befüllen.

---

### Schritt 3 – Police anlegen (Policy Service)

1. <http://localhost:9082> → **«+ Neue Police»**
2. **Partner suchen:** `Müller` eingeben → Anna Müller aus der Liste wählen.
3. **Produkt wählen:** `Hausrat Basic` aus dem Dropdown.
4. **Deckungsbeginn:** Heutiges Datum.
5. **«Erstellen»** → Police `POL-2026-000001` mit Status `ENTWURF`.

> Falls Anna Müller nicht in der Suche erscheint: Der Policy Service hat den `person.v1.created`-Event noch nicht konsumiert und seinen PartnerView noch nicht aktualisiert. 10 Sekunden warten und es erneut versuchen. In AKHQ (Consumer Groups) kann der Lag des `policy-service` geprüft werden.

---

### Schritt 4 – Deckung hinzufügen

1. **«Details»** der neuen Police klicken.
2. In der Deckungen-Karte: **«+ Deckung hinzufügen»**
3. Eingaben:
   - Deckungstyp: `FIRE`
   - Versicherungssumme: `80000`
   - Selbstbehalt: `500`
4. **«Speichern»**

---

### Schritt 5 – Police aktivieren

1. In der Detailansicht: **«Aktivieren»** klicken → Bestätigungsdialog.
2. Nach Bestätigung: Status wechselt zu **AKTIV**.

**Verifizieren in AKHQ:**

- Topic `policy.v1.issued` → neuer Event mit `policyNumber: "POL-2026-000001"`, `partnerId`, `productId` und den `coverages`. Dieser Event würde in einem vollständigen System vom Billing Service konsumiert, um die erste Prämienrechnung zu erstellen.

---

### Schritt 6 – Cross-Domain Auswertung prüfen

1. <http://localhost:8090/demo> öffnen.
2. In der Tabelle `mart_portfolio_summary` sollte die Produktlinie `HOUSEHOLD` mit 1 aktiver Police und CHF 480 Jahresprämie erscheinen. Diese Zahl wurde von dbt aus den Rohdaten des Platform Consumers berechnet – drei unabhängige Services, eine kohärente Auswertung, kein zentrales Warehouse.

> Falls die Tabelle noch leer ist: dbt läuft nur einmal beim Container-Start. Manuell auslösen:
>
> ```zsh
> podman compose run --rm dbt
> ```
>
> Anschliessend Seite neu laden.

---

### Schritt 7 – Governance prüfen

1. <http://localhost:8090/governance> öffnen.
2. Schema Registry-Panel: Alle drei Domain-Schemas (`person`, `product`, `policy`) sollten Status **OK** haben – d.h. die Avro-Schemas sind registriert und rückwärtskompatibel.
3. ODC Quality: Alle Topics sollten einen Quality Score ≥ 98 % zeigen (grüne Badges) – d.h. die SodaCL-Qualitätsprüfungen (Null-Checks, Duplikate) sind bestanden.

---

### Schritt 8 – Police kündigen

1. In der Policy-Liste oder Detailansicht: **«Kündigen»** klicken.
2. Status wechselt zu **GEKÜNDIGT**.

**Verifizieren in AKHQ:**

- Topic `policy.v1.cancelled` → neuer Event erscheint. In einer vollständigen Plattform würde dieser Event vom Billing Service verarbeitet (allfällige Prämienrückerstattung) und vom Claims Service (keine neuen Schäden mehr zu dieser Police).

---

*Zuletzt aktualisiert: März 2026*
