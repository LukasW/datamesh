package ch.css.policy.infrastructure.web;

import ch.css.policy.domain.model.Coverage;
import ch.css.policy.domain.model.CoverageType;
import ch.css.policy.domain.model.PartnerView;
import ch.css.policy.domain.model.Policy;
import ch.css.policy.domain.service.CoverageNotFoundException;
import ch.css.policy.domain.service.PolicyApplicationService;
import ch.css.policy.domain.service.PolicyNotFoundException;
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
    PolicyApplicationService policyService;

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
        return list.data("policen", policyService.listAllPolicies())
                   .data("partnerSichten", policyService.getPartnerViewsMap())
                   .data("produktSichten", policyService.getProductViewsMap());
    }

    @GET
    @Path("/{id}/edit")
    public Object getEdit(@PathParam("id") String id) {
        try {
            Policy policy = policyService.findById(id);
            return edit.data("policy", policy)
                       .data("activeProdukte", policyService.getActiveProducts())
                       .data("partnerSichten", policyService.getPartnerViewsMap())
                       .data("produktSichten", policyService.getProductViewsMap())
                       .data("success", false)
                       .data("errorMessage", null);
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>Policy not found: " + id + "</p>").build();
        }
    }

    // ── htmx Fragments ────────────────────────────────────────────────────────

    /** Renders the filtered table rows (htmx live search). */
    @GET
    @Path("/fragments/list")
    public TemplateInstance getPoliciesListFragment(
            @QueryParam("policyNummer") String policyNumber,
            @QueryParam("partnerId") String partnerId,
            @QueryParam("status") String status) {
        List<Policy> policen;
        try {
            policen = policyService.searchPolicies(policyNumber, partnerId, status);
        } catch (Exception ignored) {
            policen = List.of();
        }
        return list.getFragment("tabelle")
                   .data("policen", policen)
                   .data("partnerSichten", policyService.getPartnerViewsMap())
                   .data("produktSichten", policyService.getProductViewsMap());
    }

    /** Returns the new-policy modal form. */
    @GET
    @Path("/fragments/neu")
    public TemplateInstance getPoliciesFormModal() {
        return policenFormModal
                .data("activeProdukte", policyService.getActiveProducts())
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
            String id = policyService.createPolicy(partnerId, productId, beginn, ende, p, sb);
            Policy policy = policyService.findById(id);
            return policenRow.data("policy", policy)
                             .data("partnerSichten", policyService.getPartnerViewsMap())
                             .data("produktSichten", policyService.getProductViewsMap());
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
            Policy policy = policyService.findById(id);
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
            policyService.updatePolicyDetails(policyId, productId, beginn, ende, p, sb);
            Policy policy = policyService.findById(policyId);
            return renderPolicyDetailsForm(policy, true, null);
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (Exception e) {
            try {
                Policy policy = policyService.findById(policyId);
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
    @Path("/fragments/{id}/aktivieren")
    public Object activateFragment(@PathParam("id") String id) {
        try {
            policyService.activatePolicy(id);
            Policy policy = policyService.findById(id);
            return policenRow.data("policy", policy)
                             .data("partnerSichten", policyService.getPartnerViewsMap())
                             .data("produktSichten", policyService.getProductViewsMap());
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
    @Path("/fragments/{id}/kuendigen")
    public Object cancelFragment(@PathParam("id") String id) {
        try {
            policyService.cancelPolicy(id);
            Policy policy = policyService.findById(id);
            return policenRow.data("policy", policy)
                             .data("partnerSichten", policyService.getPartnerViewsMap())
                             .data("produktSichten", policyService.getProductViewsMap());
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
    @Path("/fragments/{id}/deckung-form")
    public TemplateInstance getCoverageForm(@PathParam("id") String id) {
        return coverageForm.data("policyId", id).data("errorMessage", null);
    }

    /** Returns empty string to remove the coverage-form from the DOM (cancel). */
    @GET
    @Path("/fragments/{id}/deckung-form-cancel")
    public String cancelCoverageForm() {
        return "";
    }

    /**
     * Creates a new coverage from form data and returns the coverage-card fragment.
     * On error re-renders the form with an error message.
     */
    @POST
    @Path("/fragments/{id}/deckungen")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object addCoverageFragment(
            @PathParam("id") String policyId,
            @FormParam("coverageType") String coverageType,
            @FormParam("insuredAmount") String insuredAmount) {
        try {
            BigDecimal amount = new BigDecimal(insuredAmount);
            String coverageId = policyService.addCoverage(policyId, CoverageType.valueOf(coverageType), amount);
            Coverage coverage = policyService.findById(policyId).getCoverages().stream()
                    .filter(c -> c.getCoverageId().equals(coverageId))
                    .findFirst().orElseThrow();
            Policy policy = policyService.findById(policyId);
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
    @Path("/fragments/{id}/deckungen/{did}/karte")
    public Object getCoverageCard(@PathParam("id") String policyId, @PathParam("did") String did) {
        try {
            Coverage coverage = policyService.findById(policyId).getCoverages().stream()
                    .filter(c -> c.getCoverageId().equals(did))
                    .findFirst()
                    .orElseThrow(() -> new CoverageNotFoundException(did));
            Policy policy = policyService.findById(policyId);
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
    @Path("/fragments/partner-suche")
    public TemplateInstance getPartnerSearchWidget(@QueryParam("q") String q) {
        List<PartnerView> partners = policyService.searchPartnerViews(q);
        return partnerSearchWidget
                .data("partners", partners)
                .data("q", q != null ? q : "");
    }

    /**
     * Selects a partner and returns the partner-picker div in "selected" state.
     * Targeted with hx-swap="outerHTML" on #partner-picker.
     */
    @POST
    @Path("/fragments/partner-auswaehlen/{partnerId}")
    public Object selectPartner(@PathParam("partnerId") String partnerId) {
        return policyService.findPartnerView(partnerId)
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
                .data("activeProdukte", policyService.getActiveProducts())
                .data("partnerSichten", policyService.getPartnerViewsMap())
                .data("success", success)
                .data("errorMessage", errorMessage);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
