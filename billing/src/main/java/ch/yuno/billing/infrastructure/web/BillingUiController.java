package ch.yuno.billing.infrastructure.web;

import ch.yuno.billing.domain.model.Invoice;
import ch.yuno.billing.domain.model.InvoiceStatus;
import ch.yuno.billing.domain.model.PolicyholderView;
import ch.yuno.billing.domain.service.InvoiceCommandService;
import ch.yuno.billing.domain.service.InvoiceNotFoundException;
import ch.yuno.billing.domain.service.InvoiceQueryService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/billing")
@Produces(MediaType.TEXT_HTML)
public class BillingUiController {

    @Location("rechnungen/list")
    Template listTemplate;

    @Inject
    InvoiceCommandService commandService;

    @Inject
    InvoiceQueryService queryService;

    // ── Full Pages ────────────────────────────────────────────────────────────

    @GET
    public TemplateInstance list(
            @QueryParam("status") String status,
            @QueryParam("partnerId") String partnerId,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        List<Invoice> invoices = loadInvoices(status, partnerId, page, size);
        Map<String, PolicyholderView> partnerMap = buildPartnerMap(invoices);
        long total = queryService.countAll();
        long totalPages = (total + size - 1) / size;

        return listTemplate
                .data("rechnungen", invoices)
                .data("partnerSichten", partnerMap)
                .data("totalElements", total)
                .data("totalPages", totalPages)
                .data("currentPage", page)
                .data("pageSize", size)
                .data("searchStatus", status != null ? status : "")
                .data("searchPartnerId", partnerId != null ? partnerId : "");
    }

    // ── htmx Fragments ────────────────────────────────────────────────────────

    @GET
    @Path("/fragments/list")
    public TemplateInstance listFragment(
            @QueryParam("status") String status,
            @QueryParam("partnerId") String partnerId,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return list(status, partnerId, page, size);
    }

    @POST
    @Path("/fragments/{id}/pay")
    public Response pay(@PathParam("id") String invoiceId) {
        try {
            commandService.recordPayment(invoiceId);
            Invoice invoice = queryService.findByIdOrThrow(invoiceId);
            PolicyholderView partner = queryService.findPolicyholder(invoice.getPartnerId()).orElse(null);
            return Response.ok(renderRow(invoice, partner)).type(MediaType.TEXT_HTML).build();
        } catch (InvoiceNotFoundException e) {
            return Response.status(404).entity("<tr><td colspan='7' class='text-danger'>Rechnung nicht gefunden</td></tr>").build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity("<div class='alert alert-danger'>" + e.getMessage() + "</div>").build();
        }
    }

    @POST
    @Path("/fragments/{id}/dun")
    public Response dun(@PathParam("id") String invoiceId) {
        try {
            commandService.initiateDunning(invoiceId);
            Invoice invoice = queryService.findByIdOrThrow(invoiceId);
            PolicyholderView partner = queryService.findPolicyholder(invoice.getPartnerId()).orElse(null);
            return Response.ok(renderRow(invoice, partner)).type(MediaType.TEXT_HTML).build();
        } catch (InvoiceNotFoundException e) {
            return Response.status(404).entity("<tr><td colspan='7' class='text-danger'>Rechnung nicht gefunden</td></tr>").build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity("<div class='alert alert-danger'>" + e.getMessage() + "</div>").build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Invoice> loadInvoices(String status, String partnerId, int page, int size) {
        if (status != null && !status.isBlank()) {
            return queryService.listByStatus(InvoiceStatus.valueOf(status.toUpperCase()), page, size);
        }
        if (partnerId != null && !partnerId.isBlank()) {
            return queryService.listByPartnerId(partnerId, page, size);
        }
        return queryService.listAll(page, size);
    }

    private Map<String, PolicyholderView> buildPartnerMap(List<Invoice> invoices) {
        Map<String, PolicyholderView> map = new HashMap<>();
        for (Invoice invoice : invoices) {
            queryService.findPolicyholder(invoice.getPartnerId())
                    .ifPresent(pv -> map.put(invoice.getPartnerId(), pv));
        }
        return map;
    }

    private String renderRow(Invoice i, PolicyholderView partner) {
        String partnerName = partner != null ? partner.name() : i.getPartnerId();
        String statusBadge = "<span class=\"badge badge-" + i.getStatus() + "\">" + i.getStatus() + "</span>";
        return """
                <tr id="invoice-%s">
                  <td><strong>%s</strong></td>
                  <td>%s<br><small class="text-muted">%s</small></td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>CHF %s</td>
                  <td>%s</td>
                </tr>""".formatted(
                i.getInvoiceId(), i.getInvoiceNumber(),
                partnerName, i.getPartnerId(),
                i.getPolicyNumber(), statusBadge,
                i.getDueDate(), i.getTotalAmount(),
                actionButtons(i));
    }

    private String actionButtons(Invoice i) {
        StringBuilder sb = new StringBuilder();
        if (i.getStatus() == InvoiceStatus.OPEN || i.getStatus() == InvoiceStatus.OVERDUE) {
            sb.append("""
                    <button class="btn btn-sm btn-outline-success"
                            hx-post="/billing/fragments/%s/pay"
                            hx-target="#invoice-%s" hx-swap="outerHTML"
                            hx-confirm="Zahlung für %s erfassen?">
                      Bezahlt
                    </button>""".formatted(i.getInvoiceId(), i.getInvoiceId(), i.getInvoiceNumber()));
        }
        if (i.getStatus() == InvoiceStatus.OPEN || i.getStatus() == InvoiceStatus.OVERDUE) {
            sb.append("""
                    <button class="btn btn-sm btn-outline-warning ms-1"
                            hx-post="/billing/fragments/%s/dun"
                            hx-target="#invoice-%s" hx-swap="outerHTML"
                            hx-confirm="Mahnverfahren für %s einleiten?">
                      Mahnen
                    </button>""".formatted(i.getInvoiceId(), i.getInvoiceId(), i.getInvoiceNumber()));
        }
        return sb.toString();
    }
}
