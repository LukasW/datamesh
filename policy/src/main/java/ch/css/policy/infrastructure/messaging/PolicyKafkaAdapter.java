package ch.css.policy.infrastructure.messaging;

import ch.css.policy.domain.model.Deckungstyp;
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
    public void publishPolicyIssued(String policyId, String policyNummer, String partnerId,
                                    String produktId, LocalDate versicherungsbeginn, BigDecimal praemie) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PolicyIssued\",\"policyId\":\"%s\"," +
                "\"policyNummer\":\"%s\",\"partnerId\":\"%s\",\"produktId\":\"%s\"," +
                "\"versicherungsbeginn\":\"%s\",\"praemie\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, policyNummer, partnerId, produktId,
                versicherungsbeginn, praemie, Instant.now());
        send(policyIssuedEmitter, policyId, json, "PolicyIssued");
    }

    @Override
    public void publishPolicyCancelled(String policyId, String policyNummer) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PolicyCancelled\",\"policyId\":\"%s\"," +
                "\"policyNummer\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, policyNummer, Instant.now());
        send(policyCancelledEmitter, policyId, json, "PolicyCancelled");
    }

    @Override
    public void publishPolicyChanged(String policyId, String policyNummer,
                                     BigDecimal praemie, BigDecimal selbstbehalt) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PolicyChanged\",\"policyId\":\"%s\"," +
                "\"policyNummer\":\"%s\",\"praemie\":%s,\"selbstbehalt\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, policyNummer, praemie, selbstbehalt, Instant.now());
        send(policyChangedEmitter, policyId, json, "PolicyChanged");
    }

    @Override
    public void publishDeckungHinzugefuegt(String policyId, String deckungId,
                                           Deckungstyp deckungstyp, BigDecimal versicherungssumme) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"CoverageAdded\",\"policyId\":\"%s\"," +
                "\"deckungId\":\"%s\",\"deckungstyp\":\"%s\",\"versicherungssumme\":%s,\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, deckungId, deckungstyp.name(), versicherungssumme, Instant.now());
        send(coverageAddedEmitter, policyId, json, "CoverageAdded");
    }

    @Override
    public void publishDeckungEntfernt(String policyId, String deckungId) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"CoverageRemoved\",\"policyId\":\"%s\"," +
                "\"deckungId\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), policyId, deckungId, Instant.now());
        send(coverageRemovedEmitter, policyId, json, "CoverageRemoved");
    }

    private void send(Emitter<String> emitter, String key, String json, String eventType) {
        try {
            emitter.send(KafkaRecord.of(key, json));
        } catch (Exception e) {
            log.warnf("Failed to publish %s event for %s: %s", eventType, key, e.getMessage());
        }
    }
}

