package ch.css.policy.infrastructure.messaging;

import ch.css.policy.domain.model.CoverageType;
import ch.css.policy.domain.port.out.PolicyEventPublisher;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    @Inject
    @Channel("policy-issued")
    Emitter<String> policyIssuedEmitter;

    @Inject
    @Channel("policy-cancelled")
    Emitter<String> policyCancelledEmitter;

    @Inject
    @Channel("policy-changed")
    Emitter<String> policyChangedEmitter;

    @Inject
    @Channel("policy-coverage-added")
    Emitter<String> coverageAddedEmitter;

    @Inject
    @Channel("policy-coverage-removed")
    Emitter<String> coverageRemovedEmitter;

    @Override
    public void publishPolicyIssued(String policyId, String policyNumber, String partnerId,
                                    String productId, LocalDate coverageStartDate, BigDecimal premium) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PolicyIssued\",\"policyId\":\"%s\"," +
                "\"policyNumber\":\"%s\",\"partnerId\":\"%s\",\"productId\":\"%s\"," +
                "\"coverageStartDate\":\"%s\",\"premium\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, policyNumber, partnerId, productId,
                coverageStartDate, premium, Instant.now());
        send(policyIssuedEmitter, policyId, json, "PolicyIssued");
    }

    @Override
    public void publishPolicyCancelled(String policyId, String policyNumber) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PolicyCancelled\",\"policyId\":\"%s\"," +
                "\"policyNumber\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, policyNumber, Instant.now());
        send(policyCancelledEmitter, policyId, json, "PolicyCancelled");
    }

    @Override
    public void publishPolicyChanged(String policyId, String policyNumber,
                                     BigDecimal premium, BigDecimal deductible) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PolicyChanged\",\"policyId\":\"%s\"," +
                "\"policyNumber\":\"%s\",\"premium\":%s,\"deductible\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, policyNumber, premium, deductible, Instant.now());
        send(policyChangedEmitter, policyId, json, "PolicyChanged");
    }

    @Override
    public void publishCoverageAdded(String policyId, String coverageId,
                                     CoverageType coverageType, BigDecimal insuredAmount) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"CoverageAdded\",\"policyId\":\"%s\"," +
                "\"coverageId\":\"%s\",\"coverageType\":\"%s\",\"insuredAmount\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, coverageId, coverageType.name(), insuredAmount, Instant.now());
        send(coverageAddedEmitter, policyId, json, "CoverageAdded");
    }

    @Override
    public void publishCoverageRemoved(String policyId, String coverageId) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"CoverageRemoved\",\"policyId\":\"%s\"," +
                "\"coverageId\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, coverageId, Instant.now());
        send(coverageRemovedEmitter, policyId, json, "CoverageRemoved");
    }

    private void send(Emitter<String> emitter, String key, String json, String eventType) {
        log.infof("Publishing Kafka event [%s] key=%s", eventType, key);
        try {
            emitter.send(KafkaRecord.of(key, json));
        } catch (Exception e) {
            log.warnf("Failed to publish %s event for %s: %s", eventType, key, e.getMessage());
        }
    }
}
