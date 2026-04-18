package ch.yuno.claims.application;

import ch.yuno.claims.domain.model.Claim;
import ch.yuno.claims.domain.model.ClaimId;
import ch.yuno.claims.domain.model.ClaimStatus;
import ch.yuno.claims.domain.model.PolicySnapshot;
import ch.yuno.claims.domain.port.out.ClaimEventPublisher;
import ch.yuno.claims.domain.port.out.ClaimRepository;
import ch.yuno.claims.domain.port.out.PartnerSearchViewRepository;
import ch.yuno.claims.domain.port.out.PolicySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaimApplicationServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock PolicySnapshotRepository policySnapshotRepository;
    @Mock ClaimEventPublisher claimEventPublisher;
    @Mock PartnerSearchViewRepository partnerSearchViewRepository;

    ClaimApplicationService service;

    private static final PolicySnapshot SNAPSHOT = new PolicySnapshot(
            "policy-1", "POL-2024-0001", "partner-1", "product-1",
            LocalDate.of(2024, 1, 1), new BigDecimal("1200.00"));

    @BeforeEach
    void setUp() {
        service = new ClaimApplicationService(claimRepository, policySnapshotRepository,
                claimEventPublisher, partnerSearchViewRepository);
    }

    @Test
    void openClaimSucceedsSavesClaimAndPublishesEvent() {
        when(policySnapshotRepository.findByPolicyId("policy-1")).thenReturn(Optional.of(SNAPSHOT));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Claim result = service.openClaim("policy-1", "Flood damage", LocalDate.now());

        assertNotNull(result);
        assertEquals(ClaimStatus.OPEN, result.getStatus());
        verify(claimRepository).save(any(Claim.class));
        verify(claimEventPublisher).claimOpened(any(Claim.class));
    }

    @Test
    void openClaimFailsWhenNoPolicySnapshotFound() {
        when(policySnapshotRepository.findByPolicyId("policy-99")).thenReturn(Optional.empty());

        assertThrows(CoverageCheckFailedException.class,
                () -> service.openClaim("policy-99", "Fire damage", LocalDate.now()));

        verify(claimRepository, never()).save(any());
        verifyNoInteractions(claimEventPublisher);
    }

    @Test
    void startReviewPublishesUnderReviewEvent() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.startReview(claim.getClaimId());

        assertEquals(ClaimStatus.IN_REVIEW, claim.getStatus());
        verify(claimEventPublisher).claimUnderReview(any(Claim.class));
    }

    @Test
    void settleClaimPublishesSettledEvent() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        claim.startReview();
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.settle(claim.getClaimId());

        verify(claimEventPublisher).claimSettled(any(Claim.class));
    }

    @Test
    void rejectClaimPublishesRejectedEvent() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(claim.getClaimId());

        assertEquals(ClaimStatus.REJECTED, claim.getStatus());
        verify(claimEventPublisher).claimRejected(any(Claim.class));
    }

    @Test
    void findByIdThrowsForUnknownClaim() {
        when(claimRepository.findById(ClaimId.of("unknown"))).thenReturn(Optional.empty());
        assertThrows(ClaimNotFoundException.class, () -> service.findById(ClaimId.of("unknown")));
    }

    @Test
    void updateClaim_onOpenClaim_persistsNewValues() {
        Claim claim = Claim.openNew("policy-1", "Original", LocalDate.of(2026, 3, 1));
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Claim result = service.updateClaim(claim.getClaimId(), "Updated description", LocalDate.of(2026, 3, 15));

        assertEquals("Updated description", result.getDescription());
        assertEquals(LocalDate.of(2026, 3, 15), result.getClaimDate());
        verify(claimRepository).save(claim);
    }

    @Test
    void updateClaim_onInReviewClaim_throwsIllegalState() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        claim.startReview();
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));

        assertThrows(IllegalStateException.class,
                () -> service.updateClaim(claim.getClaimId(), "Updated", LocalDate.now()));
        verify(claimRepository, never()).save(any());
    }
}
