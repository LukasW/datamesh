## Stage 1: Resolve Iceberg Sink Connector and all transitive dependencies via Maven
FROM maven:3.9-eclipse-temurin-17 AS iceberg-deps
RUN mkdir -p /tmp/iceberg-plugin
COPY iceberg-pom.xml /tmp/iceberg-plugin/pom.xml
RUN cd /tmp/iceberg-plugin && \
    mvn dependency:copy-dependencies -DoutputDirectory=/tmp/iceberg-plugin/libs -q

## Stage 2: Build the Kafka Connect image
FROM confluentinc/cp-kafka-connect:7.5.0

RUN confluent-hub install --no-prompt debezium/debezium-connector-postgresql:2.5.4
RUN confluent-hub install --no-prompt confluentinc/kafka-connect-avro-converter:7.5.0

# Iceberg Sink Connector for writing Kafka events to Iceberg tables on MinIO/S3
# Resolved via Maven in the builder stage to include all transitive dependencies.
USER root
COPY --from=iceberg-deps /tmp/iceberg-plugin/libs/ /usr/share/confluent-hub-components/iceberg-kafka-connect/
USER appuser
