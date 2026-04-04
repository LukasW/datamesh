package ch.yuno.policy.infrastructure.web;

import ch.yuno.policy.domain.model.Coverage;
import ch.yuno.policy.domain.model.CoverageId;
import ch.yuno.policy.domain.model.CoverageType;
import ch.yuno.policy.domain.model.PageRequest;
import ch.yuno.policy.domain.model.PageResult;
import ch.yuno.policy.domain.model.PartnerView;
import ch.yuno.policy.domain.model.Policy;
import ch.yuno.policy.domain.model.PolicyId;
import ch.yuno.policy.domain.service.CoverageNotFoundException;
import ch.yuno.policy.application.PolicyNotFoundException;
import ch.yuno.policy.domain.port.in.PolicyCommandUseCase;
import ch.yuno.policy.domain.port.in.PolicyQueryUseCase;
import ch.yuno.policy.application.PremiumCalculationUnavailableException;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Qute UI Controller for the Policenverwaltung screens.
 * Handles full-page renders and htmx fragment endpoints.
 */
@Path("/policies")
@Produces(MediaType.TEXT_HTML)
public class PolicyUiController {

    @Inject
    PolicyCommandUseCase policyCommandService;

    @Inject
    PolicyQueryUseCase policyQueryService;

    @Inject
    @Location("policen/list")
    Template list;

    @Inject
    @Location("policen/edit")
    Template edit;

    @Inject
    @Location("policen/fragments/policen-row")
    Template policenRow;

    @Inject
    @Location("policen/fragments/policen-form-modal")
    Template policenFormModal;

    @Inject
    @Location("policen/fragments/policy-details-form")
    Template policyDetailsForm;

    @Inject
    @Location("policen/fragments/coverage-card")
    Template coverageCard;

    @Inject
    @Location("policen/fragments/coverage-form")
    Template coverageForm;

    @Inject
    @Location("policen/fragments/partner-search-widget")
    Template partnerSearchWidget;

    @Inject
    @Location("policen/fragments/partner-picker-selected")
    Template partnerPickerSelected;

    // ── Full Pages ────────────────────────────────────────────────────────────

    @GET
    public TemplateInstance getList() {
        return list.data("policen", policyQueryService.listAllPolicies())
                   .data("partnerSichten", policyQueryService.getPartnerViewsMap())
                   .data("produktSichten", policyQueryService.getProductViewsMap());
    }

    @GET
    @Path("/{id}/edit")
    public Object getEdit(@PathParam("id") String id) {
        try {
            Policy policy = policyQueryService.findById(PolicyId.of(id));
            return edit.data("policy", policy)
                       .data("activeProdukte", policyQueryService.getActiveProducts())
                       .data("partnerSichten", policyQueryService.getPartnerViewsMap())
                       .data("produktSichten", policyQueryService.getProductViewsMap())
                       .data("success", false)
                       .data("errorMessage", null);
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>Policy not found: " + id + "</p>").build();
        }
    }

    // ── htmx Fragments ────────────────────────────────────────────────────────

    /** Renders the filtered table rows with pagination (htmx live search). */
    @GET
    @Path("/fragments/list")
    public TemplateInstance getPoliciesListFragment(
            @QueryParam("policyNummer") String policyNumber,
            @QueryParam("partnerId") String partnerId,
            @QueryParam("status") String status,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        PageResult<Policy> pageResult;
        try {
            PageRequest pageRequest = new PageRequest(page, size);
            pageResult = policyQueryService.searchPolicies(policyNumber, partnerId, status, pageRequest);
        } catch (Exception ignored) {
            pageResult = new PageResult<>(List.of(), 0, 0);
        }
        return list.getFragment("tabelle")
                   .data("policen", pageResult.content())
                   .data("partnerSichten", policyQueryService.getPartnerViewsMap())
                   .data("produktSichten", policyQueryService.getProductViewsMap())
                   .data("currentPage", page)
                   .data("totalPages", pageResult.totalPages())
                   .data("totalElements", pageResult.totalElements())
                   .data("pageSize", size)
                   .data("searchPolicyNummer", policyNumber != null ? policyNumber : "")
                   .data("searchPartnerId", partnerId != null ? partnerId : "")
                   .data("searchStatus", status != null ? status : "");
    }

    /** Returns the new-policy modal form. */
    @GET
    @Path("/fragments/neu")
    public TemplateInstance getPoliciesFormModal() {
        return policenFormModal
                .data("activeProdukte", policyQueryService.getActiveProducts())
                .data("currentYear", LocalDate.now().getYear());
    }

    /**
     * Creates a new policy from modal form data and returns a table row fragment.
     * On validation error returns 422 with an error alert HTML.
     */
    @POST
    @Path("/fragments")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object createPolicyFragment(
            @FormParam("partnerId") String partnerId,
            @FormParam("productId") String productId,
            @FormParam("coverageStartDate") String coverageStartDate,
            @FormParam("coverageEndDate") String coverageEndDate,
            @FormParam("premium") String premium,
            @FormParam("deductible") String deductible) {
        try {
            LocalDate beginn = LocalDate.parse(coverageStartDate);
            LocalDate ende = isNotBlank(coverageEndDate) ? LocalDate.parse(coverageEndDate) : null;
            BigDecimal p = new BigDecimal(premium);
            BigDecimal sb = isNotBlank(deductible) ? new BigDecimal(deductible) : BigDecimal.ZERO;
            PolicyId id = policyCommandService.createPolicy(partnerId, productId, beginn, ende, p, sb);
            Policy policy = policyQueryService.findById(id);
            return policenRow.data("policy", policy)
                             .data("partnerSichten", policyQueryService.getPartnerViewsMap())
                             .data("produktSichten", policyQueryService.getProductViewsMap());
        } catch (PremiumCalculationUnavailableException e) {
            return Response.status(503)
                    .entity("<div class=\"alert alert-warning\">"
                            + "Die Prämienberechnung ist momentan nicht verfügbar. "
                            + "Bitte versuchen Sie es später erneut.</div>")
                    .build();
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /** Returns the inline details edit form. */
    @GET
    @Path("/fragments/{id}/details-form")
    public Object getDetailsForm(@PathParam("id") String id) {
        try {
            Policy policy = policyQueryService.findById(PolicyId.of(id));
            return renderPolicyDetailsForm(policy, false, null);
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        }
    }

    /**
     * Updates policy details from form data and re-renders the details form.
     */
    @PUT
    @Path("/fragments/{id}/details")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object updateDetailsFragment(
            @PathParam("id") String policyId,
            @FormParam("productId") String productId,
            @FormParam("coverageStartDate") String coverageStartDate,
            @FormParam("coverageEndDate") String coverageEndDate,
            @FormParam("premium") String premium,
            @FormParam("deductible") String deductible) {
        try {
            LocalDate beginn = LocalDate.parse(coverageStartDate);
            LocalDate ende = isNotBlank(coverageEndDate) ? LocalDate.parse(coverageEndDate) : null;
            BigDecimal p = new BigDecimal(premium);
            BigDecimal sb = isNotBlank(deductible) ? new BigDecimal(deductible) : BigDecimal.ZERO;
            PolicyId pid = PolicyId.of(policyId);
            policyCommandService.updatePolicyDetails(pid, productId, beginn, ende, p, sb);
            Policy policy = policyQueryService.findById(pid);
            return renderPolicyDetailsForm(policy, true, null);
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (PremiumCalculationUnavailableException e) {
            try {
                Policy policy = policyQueryService.findById(PolicyId.of(policyId));
                return renderPolicyDetailsForm(policy, false,
                        "<div class=\"alert alert-warning\">"
                        + "Die Prämienberechnung ist momentan nicht verfügbar. "
                        + "Bitte versuchen Sie es später erneut.</div>");
            } catch (Exception ex) {
                return Response.status(503)
                        .entity("<div class=\"alert alert-warning\">"
                                + "Die Prämienberechnung ist momentan nicht verfügbar. "
                                + "Bitte versuchen Sie es später erneut.</div>")
                        .build();
            }
        } catch (Exception e) {
            try {
                Policy policy = policyQueryService.findById(PolicyId.of(policyId));
                return renderPolicyDetailsForm(policy, false,
                        "<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>");
            } catch (Exception ex) {
                return Response.status(422)
                        .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                        .build();
            }
        }
    }

    /** Activates a policy and returns the refreshed table row. */
    @POST
    @Path("/fragments/{id}/activate")
    public Object activateFragment(@PathParam("id") String id) {
        try {
            PolicyId pid = PolicyId.of(id);
            policyCommandService.activatePolicy(pid);
            Policy policy = policyQueryService.findById(pid);
            return policenRow.data("policy", policy)
                             .data("partnerSichten", policyQueryService.getPartnerViewsMap())
                             .data("produktSichten", policyQueryService.getProductViewsMap());
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (IllegalStateException e) {
            return Response.status(409)
                    .entity("<div class=\"alert alert-warning\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /** Cancels a policy and returns the refreshed table row. */
    @POST
    @Path("/fragments/{id}/cancel")
    public Object cancelFragment(@PathParam("id") String id) {
        try {
            PolicyId pid = PolicyId.of(id);
            policyCommandService.cancelPolicy(pid);
            Policy policy = policyQueryService.findById(pid);
            return policenRow.data("policy", policy)
                             .data("partnerSichten", policyQueryService.getPartnerViewsMap())
                             .data("produktSichten", policyQueryService.getProductViewsMap());
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (IllegalStateException e) {
            return Response.status(409)
                    .entity("<div class=\"alert alert-warning\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /** Returns an empty coverage-form for adding a new coverage. */
    @GET
    @Path("/fragments/{id}/coverage-form")
    public TemplateInstance getCoverageForm(@PathParam("id") String id) {
        return coverageForm.data("policyId", id).data("errorMessage", null);
    }

    /** Returns empty string to remove the coverage-form from the DOM (cancel). */
    @GET
    @Path("/fragments/{id}/coverage-form-cancel")
    public String cancelCoverageForm() {
        return "";
    }

    /**
     * Creates a new coverage from form data and returns the coverage-card fragment.
     * On error re-renders the form with an error message.
     */
    @POST
    @Path("/fragments/{id}/coverages")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object addCoverageFragment(
            @PathParam("id") String policyId,
            @FormParam("coverageType") String coverageType,
            @FormParam("insuredAmount") String insuredAmount) {
        try {
            PolicyId pid = PolicyId.of(policyId);
            BigDecimal amount = new BigDecimal(insuredAmount);
            CoverageId coverageId = policyCommandService.addCoverage(pid, CoverageType.valueOf(coverageType), amount);
            Coverage coverage = policyQueryService.findById(pid).getCoverages().stream()
                    .filter(c -> c.getCoverageId().equals(coverageId))
                    .findFirst().orElseThrow();
            Policy policy = policyQueryService.findById(pid);
            return coverageCard
                    .data("coverage", coverage)
                    .data("policyId", policyId)
                    .data("policyStatus", policy.getStatus().name());
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (Exception e) {
            return coverageForm
                    .data("policyId", policyId)
                    .data("errorMessage", "<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>");
        }
    }

    /** Returns the coverage-card fragment (used for cancel of inline edit). */
    @GET
    @Path("/fragments/{id}/coverages/{did}/card")
    public Object getCoverageCard(@PathParam("id") String policyId, @PathParam("did") String did) {
        try {
            PolicyId pid = PolicyId.of(policyId);
            CoverageId cid = CoverageId.of(did);
            Coverage coverage = policyQueryService.findById(pid).getCoverages().stream()
                    .filter(c -> c.getCoverageId().equals(cid))
                    .findFirst()
                    .orElseThrow(() -> new CoverageNotFoundException(did));
            Policy policy = policyQueryService.findById(pid);
            return coverageCard
                    .data("coverage", coverage)
                    .data("policyId", policyId)
                    .data("policyStatus", policy.getStatus().name());
        } catch (PolicyNotFoundException | CoverageNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        }
    }

    // ── Partner Search ────────────────────────────────────────────────────────

    /**
     * Returns the partner search widget with filtered results.
     * Called via htmx to load the search panel and on every keyup in the search input.
     */
    @GET
    @Path("/fragments/partner-search")
    public TemplateInstance getPartnerSearchWidget(@QueryParam("q") String q) {
        List<PartnerView> partners = policyQueryService.searchPartnerViews(q);
        return partnerSearchWidget
                .data("partners", partners)
                .data("q", q != null ? q : "");
    }

    /**
     * Selects a partner and returns the partner-picker div in "selected" state.
     * Targeted with hx-swap="outerHTML" on #partner-picker.
     */
    @POST
    @Path("/fragments/partner-select/{partnerId}")
    public Object selectPartner(@PathParam("partnerId") String partnerId) {
        return policyQueryService.findPartnerView(partnerId)
                .map(p -> (Object) partnerPickerSelected
                        .data("partnerId", p.getPartnerId())
                        .data("partnerName", p.getName()))
                .orElse(Response.status(404)
                        .entity("<div class=\"alert alert-danger\">Partner not found: "
                                + escapeHtml(partnerId) + "</div>")
                        .build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private TemplateInstance renderPolicyDetailsForm(Policy policy, boolean success, String errorMessage) {
        return policyDetailsForm
                .data("policy", policy)
                .data("activeProdukte", policyQueryService.getActiveProducts())
                .data("partnerSichten", policyQueryService.getPartnerViewsMap())
                .data("success", success)
                .data("errorMessage", errorMessage);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
