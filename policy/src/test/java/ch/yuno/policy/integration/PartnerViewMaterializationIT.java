package ch.yuno.policy.integration;

import ch.yuno.policy.domain.model.PartnerView;
import ch.yuno.policy.domain.port.out.PartnerViewRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@DisplayName("Policy - PartnerView Materialization Integration Test")
class PartnerViewMaterializationIT {

    @Inject
    PartnerViewRepository partnerViewRepository;

    @Test
    @DisplayName("Upsert and find a PartnerView in the read model")
    void upsertAndFindPartnerView() {
        String partnerId = UUID.randomUUID().toString();
        partnerViewRepository.upsert(new PartnerView(partnerId, "Max Muster"));

        var found = partnerViewRepository.findById(partnerId);
        assertTrue(found.isPresent(), "PartnerView should be found after upsert");
        assertEquals("Max Muster", found.get().getName());
    }

    @Test
    @DisplayName("Upsert overwrites existing PartnerView")
    void upsertOverwritesExisting() {
        String partnerId = UUID.randomUUID().toString();
        partnerViewRepository.upsert(new PartnerView(partnerId, "Original Name"));
        partnerViewRepository.upsert(new PartnerView(partnerId, "Updated Name"));

        var found = partnerViewRepository.findById(partnerId);
        assertTrue(found.isPresent());
        assertEquals("Updated Name", found.get().getName(),
            "Second upsert should overwrite the name");
    }

    @Test
    @DisplayName("Search finds PartnerView by partial name")
    void searchByPartialName() {
        String partnerId = UUID.randomUUID().toString();
        partnerViewRepository.upsert(new PartnerView(partnerId, "Anna Beispiel"));

        var results = partnerViewRepository.search("Beispiel");
        assertTrue(results.stream().anyMatch(p -> p.getPartnerId().equals(partnerId)),
            "Search should find partner by partial name");
    }

    @Test
    @DisplayName("FindById returns empty for unknown partner")
    void findById_unknown_returnsEmpty() {
        var result = partnerViewRepository.findById("nonexistent-partner-id");
        assertTrue(result.isEmpty(), "Unknown partnerId should return empty Optional");
    }
}
