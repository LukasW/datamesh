# Contributing Guidelines

> **Version:** 1.0.0 · **Last updated:** 2026-03-15

This document defines the contribution rules for the **Sachversicherung Datamesh Platform**. Every contributor — human or AI — must follow these guidelines before submitting code.

---

## Language Policy (Non-Negotiable)

The project enforces a strict two-language split defined by ADR-005:

| Artefact | Mandatory Language | Rationale |
|---|---|---|
| Source code (classes, methods, fields, enums, comments, logs) | **English** | Universally readable; aligns with global Java conventions |
| REST API paths, query params, JSON field names | **English** | API consumers may be international |
| Exception and error messages (thrown in code) | **English** | Stack traces and log aggregators are read by engineers |
| Kafka event type names | **English PascalCase** | Event names are schema identifiers, not UI labels |
| ODC YAML `description` values | **English** | ODC is a technical contract |
| Qute HTML templates (labels, buttons, error messages, tooltips, placeholders) | **German** | End-users of the insurance platform are German-speaking |
| `specs/business_spec.md` files | **English** | Shared with international architects and AI tooling |
| `CLAUDE.md` and all other docs under `specs/` | **English** | Same reasoning |
| `specs/arc42.md` | **German** | Navigation anchor for the German-speaking organisation |

### ❌ Patterns that will block a review

```java
// ❌ German field name in domain model
private String vorname;
private LocalDate gueltigVon;

// ✅ Correct
private String firstName;
private LocalDate validFrom;

// ❌ German exception message in domain service
throw new IllegalArgumentException("Prämie muss grösser 0 sein");

// ✅ Correct
throw new IllegalArgumentException("Premium must be greater than zero");

// ❌ German Kafka event type
eventType = "PolicyAusgestellt";

// ✅ Correct
eventType = "PolicyIssued";

// ❌ German UI string in source code (must be in template, not code)
return Response.ok("Police erfolgreich ausgestellt").build(); // if exposed as a UI label

// ✅ German only in Qute .html templates
<button type="submit">Police ausstellen</button>
```

---

## Architecture Rules

Before opening a pull request, verify:

1. **No shared databases.** Your service may only read/write its own PostgreSQL schema. Cross-domain data comes from Kafka events or (rarely) REST.

2. **Kafka first.** New domain integrations must use Kafka events, not direct REST calls. Only the two exceptions in ADR-003 are permitted.

3. **Hexagonal architecture.** No `@Inject`, no JPA annotations, no Quarkus imports inside `domain/model/` or `domain/service/`.

4. **Every new Kafka topic needs an ODC.** Add `src/main/resources/contracts/{topic}.odcontract.yaml`. Breaking schema changes → new topic version.

5. **Outbox Pattern only.** Never write to DB and Kafka in the same transaction without the outbox table.

---

## Documentation Workflow

| Change type | Required documentation update |
|---|---|
| New business logic or use case | Update `[service]/specs/business_spec.md` |
| New or changed domain term | Update the **Ubiquitous Language** table in `[service]/specs/business_spec.md` |
| New Kafka event schema | Update the relevant ODC YAML file |
| New microservice added | Add entry to `specs/arc42.md` (chapters 3 and 5) + create `[service]/specs/business_spec.md` |
| New ADR | Add to `specs/arc42.md` chapter 9 and reference in `CLAUDE.md` |

---

## Pull Request Checklist

Before requesting a review, confirm:

- [ ] All code identifiers (classes, methods, fields) are in **English**
- [ ] All UI strings (Qute templates) are in **German**
- [ ] No direct DB access to another domain's database
- [ ] No new synchronous REST calls without ADR approval
- [ ] New Kafka topics have an ODC file
- [ ] `[service]/specs/business_spec.md` updated (if business logic changed)
- [ ] `specs/arc42.md` updated (if new services or ADRs added)
- [ ] `mvn clean package -DskipTests` succeeds
- [ ] `mvn test` passes for the affected service

---

## AI Assistant Instructions (GitHub Copilot, Claude)

If you are an AI assistant generating code for this project:

1. **Read `CLAUDE.md` first.** It is the primary reference for all conventions.
2. **All generated code identifiers must be in English** — no German field names, method names, or class names.
3. **Exception messages in application/domain services must be in English.**
4. **German is only allowed inside `.html` Qute template files** for user-facing labels, buttons, and error messages.
5. **Never translate technical comments or log messages into German.**
6. **When adding a new domain concept, add it to the Ubiquitous Language table** in the relevant `specs/business_spec.md`.
7. **Check `specs/arc42.md`** before proposing a new integration pattern — ADRs are binding.

---

## Commit Message Format

```
[service] short imperative description (max 72 chars)

Optional body explaining WHY, not WHAT. Reference issue numbers if applicable.

Refs: #123
```

Examples:
```
[policy] add coverage removal endpoint with CoverageRemoved event
[partner] fix address overlap resolution for open-ended validity
[product] rename ProduktSicht to ProductView (ADR-005 compliance)
```

