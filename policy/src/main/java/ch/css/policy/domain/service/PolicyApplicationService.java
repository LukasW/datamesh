package ch.css.policy.domain.service;

import ch.css.policy.domain.model.Deckungstyp;
import ch.css.policy.domain.model.PartnerSicht;
import ch.css.policy.domain.model.Policy;
import ch.css.policy.domain.model.PolicyStatus;
import ch.css.policy.domain.model.ProduktSicht;
import ch.css.policy.domain.port.out.PartnerSichtRepository;
import ch.css.policy.domain.port.out.PolicyEventPublisher;
import ch.css.policy.domain.port.out.PolicyRepository;
import ch.css.policy.domain.port.out.ProduktSichtRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class PolicyApplicationService {

    @Inject
    PolicyRepository policyRepository;

    @Inject
    PolicyEventPublisher policyEventPublisher;

    @Inject
    PartnerSichtRepository partnerSichtRepository;

    @Inject
    ProduktSichtRepository produktSichtRepository;

    // ── Policy Management ──────────────────────────────────────────────────────

    @Transactional
    public String createPolicy(String policyNummer, String partnerId, String produktId,
                               LocalDate versicherungsbeginn, LocalDate versicherungsende,
                               BigDecimal praemie, BigDecimal selbstbehalt) {
        if (policyRepository.existsByPolicyNummer(policyNummer)) {
            throw new IllegalArgumentException("PolicyNummer bereits vorhanden: " + policyNummer);
        }
        Policy policy = new Policy(policyNummer, partnerId, produktId,
                versicherungsbeginn, versicherungsende, praemie, selbstbehalt);
        policyRepository.save(policy);
        return policy.getPolicyId();
    }

    @Transactional
    public void aktivierePolicy(String policyId) {
        Policy policy = findOrThrow(policyId);
        policy.aktivieren();
        policyRepository.save(policy);
        policyEventPublisher.publishPolicyIssued(
                policy.getPolicyId(), policy.getPolicyNummer(),
                policy.getPartnerId(), policy.getProduktId(),
                policy.getVersicherungsbeginn(), policy.getPraemie());
    }

    @Transactional
    public void kuendigePolicy(String policyId) {
        Policy policy = findOrThrow(policyId);
        policy.kuendigen();
        policyRepository.save(policy);
        policyEventPublisher.publishPolicyCancelled(policy.getPolicyId(), policy.getPolicyNummer());
    }

    @Transactional
    public void updatePolicyDetails(String policyId, String produktId,
                                    LocalDate versicherungsbeginn, LocalDate versicherungsende,
                                    BigDecimal praemie, BigDecimal selbstbehalt) {
        Policy policy = findOrThrow(policyId);
        policy.updateDetails(produktId, versicherungsbeginn, versicherungsende, praemie, selbstbehalt);
        policyRepository.save(policy);
        policyEventPublisher.publishPolicyChanged(
                policy.getPolicyId(), policy.getPolicyNummer(),
                policy.getPraemie(), policy.getSelbstbehalt());
    }

    @Transactional
    public String addDeckung(String policyId, Deckungstyp deckungstyp, BigDecimal versicherungssumme) {
        Policy policy = findOrThrow(policyId);
        String deckungId = policy.addDeckung(deckungstyp, versicherungssumme);
        policyRepository.save(policy);
        policyEventPublisher.publishDeckungHinzugefuegt(policyId, deckungId, deckungstyp, versicherungssumme);
        return deckungId;
    }

    @Transactional
    public void removeDeckung(String policyId, String deckungId) {
        Policy policy = findOrThrow(policyId);
        policy.removeDeckung(deckungId);
        policyRepository.save(policy);
        policyEventPublisher.publishDeckungEntfernt(policyId, deckungId);
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    public Policy findById(String policyId) {
        return findOrThrow(policyId);
    }

    public Policy findByPolicyNummer(String policyNummer) {
        return policyRepository.findByPolicyNummer(policyNummer)
                .orElseThrow(() -> new PolicyNotFoundException(policyNummer));
    }

    public List<Policy> listAllPolicen() {
        return policyRepository.search(null, null, null);
    }

    public List<Policy> searchPolicen(String policyNummer, String partnerId, String statusStr) {
        PolicyStatus status = (statusStr != null && !statusStr.isBlank())
                ? PolicyStatus.valueOf(statusStr) : null;
        return policyRepository.search(policyNummer, partnerId, status);
    }

    // ── Read Model Queries (Partner & Product) ─────────────────────────────────

    public java.util.Map<String, PartnerSicht> getPartnerSichtenMap() {
        java.util.Map<String, PartnerSicht> map = new java.util.HashMap<>();
        partnerSichtRepository.findAll().forEach(p -> map.put(p.getPartnerId(), p));
        return map;
    }

    public java.util.Optional<PartnerSicht> findPartnerSicht(String partnerId) {
        return partnerSichtRepository.findById(partnerId);
    }

    /**
     * Searches partner sichten by name fragment (case-insensitive, max 20 results).
     * Returns top 20 partners when query is blank.
     */
    public java.util.List<PartnerSicht> searchPartnerSichten(String nameQuery) {
        return partnerSichtRepository.search(nameQuery);
    }

    public java.util.List<ProduktSicht> getActiveProdukte() {
        return produktSichtRepository.findAllActive();
    }

    public java.util.Map<String, ProduktSicht> getProduktSichtenMap() {
        java.util.Map<String, ProduktSicht> map = new java.util.HashMap<>();
        produktSichtRepository.findAll().forEach(p -> map.put(p.getProduktId(), p));
        return map;
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private Policy findOrThrow(String policyId) {
        return policyRepository.findById(policyId)
                .orElseThrow(() -> new PolicyNotFoundException(policyId));
    }
}

