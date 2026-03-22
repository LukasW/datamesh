package ch.yuno.claims.domain.service;

import ch.yuno.claims.domain.model.Claim;
import ch.yuno.claims.domain.model.ClaimStatus;
import ch.yuno.claims.domain.model.PolicySnapshot;
import ch.yuno.claims.domain.port.out.ClaimRepository;
import ch.yuno.claims.domain.port.out.OutboxRepository;
import ch.yuno.claims.domain.port.out.PolicySnapshotRepository;
import ch.yuno.claims.infrastructure.messaging.outbox.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @Mock OutboxRepository outboxRepository;

    ClaimApplicationService service;

    private static final PolicySnapshot SNAPSHOT = new PolicySnapshot(
            "policy-1", "POL-2024-0001", "partner-1", "product-1",
            LocalDate.of(2024, 1, 1), new BigDecimal("1200.00"));

    @BeforeEach
    void setUp() {
        service = new ClaimApplicationService(claimRepository, policySnapshotRepository, outboxRepository);
    }

    @Test
    void openClaimSucceedsSavesClaimAndPublishesEvent() {
        when(policySnapshotRepository.findByPolicyId("policy-1")).thenReturn(Optional.of(SNAPSHOT));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Claim result = service.openClaim("policy-1", "Flood damage", LocalDate.now());

        assertNotNull(result);
        assertEquals(ClaimStatus.OPEN, result.getStatus());
        verify(claimRepository).save(any(Claim.class));
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertEquals("ClaimOpened", captor.getValue().eventType());
        assertEquals("claims.v1.opened", captor.getValue().topic());
    }

    @Test
    void openClaimFailsWhenNoPolicySnapshotFound() {
        when(policySnapshotRepository.findByPolicyId("policy-99")).thenReturn(Optional.empty());

        assertThrows(CoverageCheckFailedException.class,
                () -> service.openClaim("policy-99", "Fire damage", LocalDate.now()));

        verify(claimRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void settleClaimPublishesSettledEvent() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        claim.startReview();
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.settle(claim.getClaimId());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertEquals("ClaimSettled", captor.getValue().eventType());
        assertEquals("claims.v1.settled", captor.getValue().topic());
    }

    @Test
    void rejectClaimNoOutboxEvent() {
        Claim claim = Claim.openNew("policy-1", "Damage", LocalDate.now());
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(claim.getClaimId());

        assertEquals(ClaimStatus.REJECTED, claim.getStatus());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void findByIdThrowsForUnknownClaim() {
        when(claimRepository.findById("unknown")).thenReturn(Optional.empty());
        assertThrows(ClaimNotFoundException.class, () -> service.findById("unknown"));
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
