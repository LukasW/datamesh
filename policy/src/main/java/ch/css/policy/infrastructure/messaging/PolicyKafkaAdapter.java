package ch.css.policy.infrastructure.messaging;

import ch.css.policy.domain.model.CoverageType;
import ch.css.policy.domain.port.out.PolicyEventPublisher;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@ApplicationScoped
public class PolicyKafkaAdapter implements PolicyEventPublisher {

    private static final Logger log = Logger.getLogger(PolicyKafkaAdapter.class);

    private static final Schema POLICY_ISSUED_SCHEMA = SchemaBuilder.record("PolicyIssued")
            .namespace("ch.css.policy.events")
            .fields()
            .requiredString("eventId").requiredString("eventType").requiredString("policyId")
            .requiredString("policyNumber").requiredString("partnerId").requiredString("productId")
            .requiredString("coverageStartDate").requiredString("premium").requiredString("timestamp")
            .endRecord();

    private static final Schema POLICY_CANCELLED_SCHEMA = SchemaBuilder.record("PolicyCancelled")
            .namespace("ch.css.policy.events")
            .fields()
            .requiredString("eventId").requiredString("eventType").requiredString("policyId")
            .requiredString("policyNumber").requiredString("timestamp")
            .endRecord();

    private static final Schema POLICY_CHANGED_SCHEMA = SchemaBuilder.record("PolicyChanged")
            .namespace("ch.css.policy.events")
            .fields()
            .requiredString("eventId").requiredString("eventType").requiredString("policyId")
            .requiredString("policyNumber").requiredString("premium").requiredString("deductible")
            .requiredString("timestamp")
            .endRecord();

    private static final Schema COVERAGE_ADDED_SCHEMA = SchemaBuilder.record("CoverageAdded")
            .namespace("ch.css.policy.events")
            .fields()
            .requiredString("eventId").requiredString("eventType").requiredString("policyId")
            .requiredString("coverageId").requiredString("coverageType").requiredString("insuredAmount")
            .requiredString("timestamp")
            .endRecord();

    private static final Schema COVERAGE_REMOVED_SCHEMA = SchemaBuilder.record("CoverageRemoved")
            .namespace("ch.css.policy.events")
            .fields()
            .requiredString("eventId").requiredString("eventType").requiredString("policyId")
            .requiredString("coverageId").requiredString("timestamp")
            .endRecord();

    @Inject
    @Channel("policy-issued")
    Emitter<GenericRecord> policyIssuedEmitter;

    @Inject
    @Channel("policy-cancelled")
    Emitter<GenericRecord> policyCancelledEmitter;

    @Inject
    @Channel("policy-changed")
    Emitter<GenericRecord> policyChangedEmitter;

    @Inject
    @Channel("policy-coverage-added")
    Emitter<GenericRecord> coverageAddedEmitter;

    @Inject
    @Channel("policy-coverage-removed")
    Emitter<GenericRecord> coverageRemovedEmitter;

    @Override
    public void publishPolicyIssued(String policyId, String policyNumber, String partnerId,
                                    String productId, LocalDate coverageStartDate, BigDecimal premium) {
        GenericRecord record = new GenericData.Record(POLICY_ISSUED_SCHEMA);
        record.put("eventId", UUID.randomUUID().toString());
        record.put("eventType", "PolicyIssued");
        record.put("policyId", policyId);
        record.put("policyNumber", policyNumber);
        record.put("partnerId", partnerId);
        record.put("productId", productId);
        record.put("coverageStartDate", coverageStartDate.toString());
        record.put("premium", premium.toPlainString());
        record.put("timestamp", Instant.now().toString());
        send(policyIssuedEmitter, policyId, record, "PolicyIssued");
    }

    @Override
    public void publishPolicyCancelled(String policyId, String policyNumber) {
        GenericRecord record = new GenericData.Record(POLICY_CANCELLED_SCHEMA);
        record.put("eventId", UUID.randomUUID().toString());
        record.put("eventType", "PolicyCancelled");
        record.put("policyId", policyId);
        record.put("policyNumber", policyNumber);
        record.put("timestamp", Instant.now().toString());
        send(policyCancelledEmitter, policyId, record, "PolicyCancelled");
    }

    @Override
    public void publishPolicyChanged(String policyId, String policyNumber,
                                     BigDecimal premium, BigDecimal deductible) {
        GenericRecord record = new GenericData.Record(POLICY_CHANGED_SCHEMA);
        record.put("eventId", UUID.randomUUID().toString());
        record.put("eventType", "PolicyChanged");
        record.put("policyId", policyId);
        record.put("policyNumber", policyNumber);
        record.put("premium", premium.toPlainString());
        record.put("deductible", deductible.toPlainString());
        record.put("timestamp", Instant.now().toString());
        send(policyChangedEmitter, policyId, record, "PolicyChanged");
    }

    @Override
    public void publishCoverageAdded(String policyId, String coverageId,
                                     CoverageType coverageType, BigDecimal insuredAmount) {
        GenericRecord record = new GenericData.Record(COVERAGE_ADDED_SCHEMA);
        record.put("eventId", UUID.randomUUID().toString());
        record.put("eventType", "CoverageAdded");
        record.put("policyId", policyId);
        record.put("coverageId", coverageId);
        record.put("coverageType", coverageType.name());
        record.put("insuredAmount", insuredAmount.toPlainString());
        record.put("timestamp", Instant.now().toString());
        send(coverageAddedEmitter, policyId, record, "CoverageAdded");
    }

    @Override
    public void publishCoverageRemoved(String policyId, String coverageId) {
        GenericRecord record = new GenericData.Record(COVERAGE_REMOVED_SCHEMA);
        record.put("eventId", UUID.randomUUID().toString());
        record.put("eventType", "CoverageRemoved");
        record.put("policyId", policyId);
        record.put("coverageId", coverageId);
        record.put("timestamp", Instant.now().toString());
        send(coverageRemovedEmitter, policyId, record, "CoverageRemoved");
    }

    private void send(Emitter<GenericRecord> emitter, String key, GenericRecord record, String eventType) {
        log.infof("Publishing Kafka event [%s] key=%s", eventType, key);
        try {
            emitter.send(KafkaRecord.of(key, record));
        } catch (Exception e) {
            log.warnf("Failed to publish %s event for %s: %s", eventType, key, e.getMessage());
        }
    }
}
