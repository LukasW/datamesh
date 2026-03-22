package ch.yuno.billing.infrastructure.web;

import ch.yuno.billing.domain.model.Invoice;
import ch.yuno.billing.domain.model.InvoiceStatus;
import ch.yuno.billing.domain.service.InvoiceCommandService;
import ch.yuno.billing.domain.service.InvoiceNotFoundException;
import ch.yuno.billing.domain.service.InvoiceQueryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

@Path("/api/invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Invoices", description = "Billing & Collection API")
public class BillingRestAdapter {

    @Inject
    InvoiceCommandService commandService;

    @Inject
    InvoiceQueryService queryService;

    @GET
    @Operation(summary = "List invoices with optional status filter")
    public Response list(
            @QueryParam("status") String status,
            @QueryParam("partnerId") String partnerId,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        List<Invoice> invoices;
        if (status != null && !status.isBlank()) {
            invoices = queryService.listByStatus(InvoiceStatus.valueOf(status.toUpperCase()), page, size);
        } else if (partnerId != null && !partnerId.isBlank()) {
            invoices = queryService.listByPartnerId(partnerId, page, size);
        } else {
            invoices = queryService.listAll(page, size);
        }
        return Response.ok(invoices.stream().map(this::toDto).toList()).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a single invoice")
    public Response getById(@PathParam("id") String id) {
        try {
            Invoice invoice = queryService.findByIdOrThrow(id);
            return Response.ok(toDto(invoice)).build();
        } catch (InvoiceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/pay")
    @Operation(summary = "Record payment for an invoice")
    public Response recordPayment(@PathParam("id") String id) {
        try {
            commandService.recordPayment(id);
            Invoice invoice = queryService.findByIdOrThrow(id);
            return Response.ok(toDto(invoice)).build();
        } catch (InvoiceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/dun")
    @Operation(summary = "Initiate or escalate dunning for an overdue invoice")
    public Response initiateDunning(@PathParam("id") String id) {
        try {
            String dunningCaseId = commandService.initiateDunning(id);
            return Response.ok(Map.of("dunningCaseId", dunningCaseId)).build();
        } catch (InvoiceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ── DTO mapping ───────────────────────────────────────────────────────────

    private Map<String, Object> toDto(Invoice i) {
        return Map.of(
                "invoiceId",     i.getInvoiceId(),
                "invoiceNumber", i.getInvoiceNumber(),
                "policyId",      i.getPolicyId(),
                "policyNumber",  i.getPolicyNumber(),
                "partnerId",     i.getPartnerId(),
                "status",        i.getStatus().name(),
                "billingCycle",  i.getBillingCycle().name(),
                "totalAmount",   i.getTotalAmount(),
                "dueDate",       i.getDueDate().toString()
        );
    }
}
