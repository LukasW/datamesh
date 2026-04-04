# ADR-011: JSON as Event Serialization Format

| Field      | Value                  |
|------------|------------------------|
| **Status** | Accepted               |
| **Date**   | 2026-04-04             |
| **Author** | Lukas Weibel           |

## Context

The platform uses the Transactional Outbox pattern with Debezium CDC to publish domain events to Kafka. Events are stored as JSON strings in the outbox table's `payload` column and forwarded via Debezium's `StringConverter`.

The codebase previously maintained dual schema definitions: Avro schemas (`.avsc`) and JSON Schemas (`.schema.json`) for each event type. However, at runtime, **only JSON was ever used**:

- All Debezium connectors use `StringConverter` (not `AvroConverter`).
- All Kafka consumers use `StringDeserializer` and parse events with Jackson `ObjectMapper`.
- No Schema Registry is deployed.
- The Avro Maven plugin and Confluent dependencies were declared in the parent POM but unused by any module.

This dual maintenance created confusion about which format is authoritative and added unnecessary dependency overhead.

## Decision

**JSON is the sole event serialization format.** JSON Schema (draft-07) in `contracts/schemas/` is the single source of truth for event structure.

Specifically:
1. All Avro dependencies removed from the parent POM (`avro`, `kafka-avro-serializer`, `avro-maven-plugin`, Confluent repository).
2. All `.avsc` files removed from `contracts/` directories.
3. JSON Schema files (`.schema.json`) remain as the authoritative contract definition.
4. Open Data Contracts (ODC YAML) reference JSON Schemas, not Avro.

## Schema Compatibility Rules

To ensure backward compatibility without a Schema Registry, the following rules apply:

| Change                        | Allowed? | Notes                                    |
|-------------------------------|----------|------------------------------------------|
| Add optional field            | Yes      | Must have `null` default or be absent    |
| Remove optional field         | No       | Deprecate first, remove in next major    |
| Remove required field         | No       | Breaking → requires new topic version    |
| Change field type             | No       | Breaking → requires new topic version    |
| Rename field                  | No       | Breaking → requires new topic version    |
| Add enum value                | Yes      | Consumers must tolerate unknown values   |
| Remove enum value             | No       | Breaking → requires new topic version    |

Breaking changes require a new topic version (e.g., `policy.v1.issued` → `policy.v2.issued`).

## Compatibility Enforcement

- **Producers:** Validate event payload against JSON Schema before writing to the outbox table.
- **Consumers:** Configure `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES = false` to tolerate new optional fields. Use Dead Letter Queues for unparseable events.
- **CI Pipeline:** Schema diff checks on PRs to detect breaking changes in `.schema.json` files.

## Consequences

### Positive
- **Single source of truth:** No more dual schema maintenance.
- **Outbox compatibility:** JSON payloads are native to the `TEXT`/`JSONB` outbox column and `StringConverter`.
- **Debuggability:** Events are human-readable in Kafka tooling (`kafkacat`, Redpanda Console).
- **Reduced infrastructure:** No Schema Registry required.
- **PII encryption (ADR-009):** Vault Transit encryption works naturally on JSON string fields.
- **Fewer dependencies:** Avro and Confluent libraries removed from the build.

### Negative
- **No runtime schema enforcement at broker level:** Validation must happen in application code and CI, not at the Kafka layer.
- **Larger payloads:** JSON is more verbose than Avro binary format. Acceptable at current scale.
- **No automatic code generation:** Avro's code-gen is lost; event classes are maintained manually (already the case).

## Alternatives Considered

### Avro with Confluent Schema Registry
Rejected because:
- Requires deploying and operating a Schema Registry.
- Conflicts with the Transactional Outbox pattern (JSON payload in DB → binary Avro on Kafka requires an additional transformation step).
- Over-engineered for current scale (6 domains, moderate throughput).
- PII encryption (ADR-009) becomes more complex with binary fields.
