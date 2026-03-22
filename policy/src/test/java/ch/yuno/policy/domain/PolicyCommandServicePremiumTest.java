package ch.yuno.policy.domain;

import ch.yuno.policy.domain.model.PremiumCalculationResult;
import ch.yuno.policy.domain.port.out.OutboxRepository;
import ch.yuno.policy.domain.port.out.PolicyRepository;
import ch.yuno.policy.domain.port.out.PremiumCalculationPort;
import ch.yuno.policy.domain.service.PolicyCommandService;
import ch.yuno.policy.domain.service.PremiumCalculationUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyCommandServicePremiumTest {

    @Mock PolicyRepository policyRepository;
    @Mock OutboxRepository outboxRepository;
    @Mock PremiumCalculationPort premiumCalculationPort;
    @InjectMocks PolicyCommandService service;

    @BeforeEach
    void setUp() {
        when(policyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(policyRepository.existsByPolicyNumber(any())).thenReturn(false);
    }

    @Test
    void createPolicyWithPremiumCalculation_success() {
        var premiumResult = new PremiumCalculationResult(
                UUID.randomUUID().toString(),
                new BigDecimal("1000.00"), new BigDecimal("100.00"),
                new BigDecimal("30.00"), new BigDecimal("0.00"),
                new BigDecimal("1130.00"), "CHF");
        when(premiumCalculationPort.calculatePremium(
                eq("product-1"), eq("HOUSEHOLD_CONTENTS"), eq(25), eq("8001"), any()))
                .thenReturn(premiumResult);

        String policyId = service.createPolicyWithPremiumCalculation(
                "partner-1", "product-1", "HOUSEHOLD_CONTENTS", 25, "8001",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                new BigDecimal("500.00"), List.of("HOUSEHOLD_CONTENTS"));

        assertNotNull(policyId);
        verify(premiumCalculationPort).calculatePremium(
                "product-1", "HOUSEHOLD_CONTENTS", 25, "8001", List.of("HOUSEHOLD_CONTENTS"));
        verify(policyRepository).save(any());
    }

    @Test
    void createPolicyWithPremiumCalculation_serviceUnavailable_throwsException() {
        when(premiumCalculationPort.calculatePremium(any(), any(), anyInt(), any(), any()))
                .thenThrow(new PremiumCalculationUnavailableException("Service unavailable"));

        assertThrows(PremiumCalculationUnavailableException.class, () ->
                service.createPolicyWithPremiumCalculation(
                        "partner-1", "product-1", "HOUSEHOLD_CONTENTS", 25, "8001",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                        BigDecimal.ZERO, List.of()));

        verify(policyRepository, never()).save(any());
    }

    @Test
    void createPolicy_existingMethod_stillWorks() {
        String policyId = service.createPolicy(
                "partner-1", "product-1",
                LocalDate.of(2026, 1, 1), null,
                new BigDecimal("500.00"), BigDecimal.ZERO);

        assertNotNull(policyId);
        verify(policyRepository).save(any());
        verifyNoInteractions(premiumCalculationPort);
    }
}
