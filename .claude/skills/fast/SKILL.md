---
name: fast
description: Schnellstarter — erstellt Issue, plant und implementiert in einem Durchlauf
argument-hint: <beschreibung>
disable-model-invocation: true
---

Führe die folgenden Schritte nacheinander aus.

**Eingabe:** $ARGUMENTS

## Schritt 0: Duplikat-Prüfung

Bevor etwas erstellt wird, prüfe ob das beschriebene Feature bereits existiert:

1. Suche in der Codebase (Grep, Glob) nach Hinweisen, dass die beschriebene Funktionalität bereits implementiert ist. Beachte die Multi-Module-Struktur: Suche in der betroffenen Domain (`billing`, `claims`, `hr-system`, `hr-integration`, `partner`, `policy`, `product`, `sales`), aber auch in bestehenden Kafka-Events (`*/src/main/resources/contracts/`) und SQLMesh-Modellen (`*/data-product/sqlmesh/`).
2. Prüfe offene und kürzlich geschlossene Issues via MCP (GitHub/GitLab/Gitea), ob ein gleiches oder sehr ähnliches Issue bereits existiert.

**Falls das Feature bereits vorhanden ist oder ein bestehendes Issue existiert:**
Melde dem Benutzer klar, was gefunden wurde (Datei/Modul/ODC-Contract bzw. Issue-Link), und brich ab. Erstelle KEIN neues Issue.

## Schritt 1: Issue erstellen via /task

Führe `/task $ARGUMENTS` aus. Merke dir die Issue-Nummer aus der Ausgabe (z.B. `#42` oder `US-42`).

Falls während der Issue-Erstellung Unklarheiten bestehen (z.B. mehrdeutige Anforderungen, fehlender Kontext, unklar ob Use Case oder technischer Task, unklar welche Domain zuständig ist), stelle dem Benutzer gezielt Rückfragen und warte auf Antwort, bevor du weitermachst.

## Schritt 2: Implementieren via /implement

Führe `/implement <issue-nummer>` mit der Issue-Nummer aus Schritt 1 aus.

`/implement` prüft selbst, ob ein Plan existiert, und erstellt ihn andernfalls via `/plan`. Im `/fast`-Modus wird die reguläre Plan-Bestätigung übersprungen — gehe direkt zur Implementierung. Nur bei echten Architektur-Unklarheiten nachfragen (z.B. Event-basiert vs. gRPC nach ADR-010, Cross-Domain-Read-Model vs. neuer API, ODC-Schema-Version v1 vs. v2, PII-Encryption gemäss ADR-009 erforderlich?).

## Regeln

- Automatischer Durchlauf — aber bei echten Unklarheiten interaktiv nachfragen
- Die reguläre Plan-Bestätigung wird bewusst übersprungen
- Bei Fehlern in einem Schritt: Fehler melden und abbrechen, nicht weitermachen
- Duplikat erkannt = sofortiger Abbruch mit klarer Meldung
