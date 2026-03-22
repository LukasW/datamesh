package ch.yuno.billing.domain.service;

import ch.yuno.billing.domain.model.BillingCycle;
import ch.yuno.billing.domain.model.Invoice;
import ch.yuno.billing.domain.model.InvoiceId;
import ch.yuno.billing.domain.model.InvoiceStatus;
import ch.yuno.billing.domain.port.out.DunningCaseRepository;
import ch.yuno.billing.domain.port.out.InvoiceRepository;
import ch.yuno.billing.domain.port.out.OutboxRepository;
import ch.yuno.billing.infrastructure.messaging.outbox.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceCommandServiceTest {

    @Mock InvoiceRepository invoiceRepository;
    @Mock DunningCaseRepository dunningCaseRepository;
    @Mock OutboxRepository outboxRepository;

    InvoiceCommandService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceCommandService();
        // Inject mocks via reflection (field injection pattern used in billing service)
        injectField(service, "invoiceRepository", invoiceRepository);
        injectField(service, "dunningCaseRepository", dunningCaseRepository);
        injectField(service, "outboxRepository", outboxRepository);
    }

    @Test
    void createInvoiceForPolicySavesInvoiceAndOutboxEvent() {
        when(invoiceRepository.existsByInvoiceNumber(anyString())).thenReturn(false);

        InvoiceId invoiceId = service.createInvoiceForPolicy(
                "policy-1", "POL-2024-001", "partner-1",
                new BigDecimal("1200.00"), BillingCycle.ANNUAL);

        assertNotNull(invoiceId);
        verify(invoiceRepository).save(any(Invoice.class));
        verify(outboxRepository).save(any(OutboxEvent.class));
    }

    @Test
    void recordPaymentSavesUpdatedInvoiceAndOutboxEvent() {
        Invoice invoice = new Invoice("INV-001", "policy-1", "POL-001",
                "partner-1", BillingCycle.ANNUAL, new BigDecimal("1200.00"),
                LocalDate.now(), LocalDate.now().plusDays(30));
        when(invoiceRepository.findById(InvoiceId.of("inv-1"))).thenReturn(Optional.of(invoice));

        service.recordPayment(InvoiceId.of("inv-1"));

        verify(invoiceRepository).save(invoice);
        verify(outboxRepository).save(any(OutboxEvent.class));
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
    }

    @Test
    void cancelInvoicesForPolicyCancelsOpenAndOverdueInvoices() {
        Invoice open = new Invoice("INV-001", "policy-1", "POL-001",
                "partner-1", BillingCycle.ANNUAL, new BigDecimal("1200.00"),
                LocalDate.now(), LocalDate.now().plusDays(30));
        Invoice overdue = new Invoice("INV-001", "policy-1", "POL-001",
                "partner-1", BillingCycle.ANNUAL, new BigDecimal("1200.00"),
                LocalDate.now(), LocalDate.now().plusDays(30));
        overdue.markOverdue();

        when(invoiceRepository.findByPolicyIdAndStatus("policy-1", InvoiceStatus.OPEN)).thenReturn(List.of(open));
        when(invoiceRepository.findByPolicyIdAndStatus("policy-1", InvoiceStatus.OVERDUE)).thenReturn(List.of(overdue));

        service.cancelInvoicesForPolicy("policy-1");

        verify(invoiceRepository, times(2)).save(any(Invoice.class));
        assertEquals(InvoiceStatus.CANCELLED, open.getStatus());
        assertEquals(InvoiceStatus.CANCELLED, overdue.getStatus());
    }

    @Test
    void triggerPayoutWritesOutboxEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);

        service.triggerPayout("claim-1", "policy-1", "partner-1", new BigDecimal("5000.00"));

        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertEquals("PayoutTriggered", event.eventType());
        assertEquals("billing.v1.payout-triggered", event.topic());
    }

    private void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
