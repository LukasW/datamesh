package ch.yuno.billing.application;

import ch.yuno.billing.domain.model.BillingCycle;
import ch.yuno.billing.domain.model.Invoice;
import ch.yuno.billing.domain.model.InvoiceId;
import ch.yuno.billing.domain.model.InvoiceStatus;
import ch.yuno.billing.domain.port.out.BillingEventPublisher;
import ch.yuno.billing.domain.port.out.DunningCaseRepository;
import ch.yuno.billing.domain.port.out.InvoiceLineItemLabelProvider;
import ch.yuno.billing.domain.port.out.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @Mock BillingEventPublisher billingEventPublisher;
    @Mock InvoiceLineItemLabelProvider lineItemLabelProvider;

    InvoiceCommandService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceCommandService();
        injectField(service, "invoiceRepository", invoiceRepository);
        injectField(service, "dunningCaseRepository", dunningCaseRepository);
        injectField(service, "billingEventPublisher", billingEventPublisher);
        injectField(service, "lineItemLabelProvider", lineItemLabelProvider);
    }

    @Test
    void createInvoiceForPolicySavesInvoiceAndPublishesEvent() {
        when(invoiceRepository.findByPolicyId("policy-1")).thenReturn(List.of());
        when(invoiceRepository.existsByInvoiceNumber(anyString())).thenReturn(false);
        when(lineItemLabelProvider.labelForCycle(any())).thenReturn("Jahresprämie");

        InvoiceId invoiceId = service.createInvoiceForPolicy(
                "policy-1", "POL-2024-001", "partner-1",
                new BigDecimal("1200.00"), BillingCycle.ANNUAL);

        assertNotNull(invoiceId);
        verify(invoiceRepository).save(any(Invoice.class));
        verify(billingEventPublisher).invoiceCreated(any(Invoice.class));
    }

    @Test
    void createInvoiceForPolicyIsIdempotentOnRedelivery() {
        Invoice existing = new Invoice("INV-001", "policy-1", "POL-2024-001",
                "partner-1", BillingCycle.ANNUAL, new BigDecimal("1200.00"),
                LocalDate.now(), LocalDate.now().plusDays(30));
        when(invoiceRepository.findByPolicyId("policy-1")).thenReturn(List.of(existing));

        InvoiceId invoiceId = service.createInvoiceForPolicy(
                "policy-1", "POL-2024-001", "partner-1",
                new BigDecimal("1200.00"), BillingCycle.ANNUAL);

        assertEquals(existing.getInvoiceId(), invoiceId);
        verify(invoiceRepository, never()).save(any(Invoice.class));
        verify(billingEventPublisher, never()).invoiceCreated(any(Invoice.class));
        verify(invoiceRepository, never()).existsByInvoiceNumber(anyString());
    }

    @Test
    void recordPaymentSavesUpdatedInvoiceAndPublishesEvent() {
        Invoice invoice = new Invoice("INV-001", "policy-1", "POL-001",
                "partner-1", BillingCycle.ANNUAL, new BigDecimal("1200.00"),
                LocalDate.now(), LocalDate.now().plusDays(30));
        when(invoiceRepository.findById(InvoiceId.of("inv-1"))).thenReturn(Optional.of(invoice));

        service.recordPayment(InvoiceId.of("inv-1"));

        verify(invoiceRepository).save(invoice);
        verify(billingEventPublisher).paymentReceived(invoice);
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
    void triggerPayoutPublishesEvent() {
        service.triggerPayout("claim-1", "policy-1", "partner-1", new BigDecimal("5000.00"));

        verify(billingEventPublisher).payoutTriggered("claim-1", "policy-1", "partner-1", new BigDecimal("5000.00"));
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
