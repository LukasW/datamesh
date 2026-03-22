package ch.yuno.partner.infrastructure.web;

import ch.yuno.partner.domain.model.Address;
import ch.yuno.partner.domain.model.AddressType;
import ch.yuno.partner.domain.model.Gender;
import ch.yuno.partner.domain.model.PageRequest;
import ch.yuno.partner.domain.model.PageResult;
import ch.yuno.partner.domain.model.Person;
import ch.yuno.partner.domain.service.AddressOverlapException;
import ch.yuno.partner.domain.service.AddressNotFoundException;
import ch.yuno.partner.domain.service.PersonCommandService;
import ch.yuno.partner.domain.service.PersonNotFoundException;
import ch.yuno.partner.domain.service.PersonQueryService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;

/**
 * Qute UI Controller for the Personenverwaltung screens.
 * Handles full-page renders and htmx fragment endpoints.
 */
@Path("/persons")
@Produces(MediaType.TEXT_HTML)
public class PersonUiController {

    @Inject
    PersonCommandService personCommandService;

    @Inject
    PersonQueryService personQueryService;

    @Inject
    @Location("personen/list")
    Template list;

    @Inject
    @Location("personen/edit")
    Template edit;

    @Inject
    @Location("personen/fragments/personen-row")
    Template personenRow;

    @Inject
    @Location("personen/fragments/personen-form-modal")
    Template personenFormModal;

    @Inject
    @Location("personen/fragments/adresse-form")
    Template adresseForm;

    @Inject
    @Location("personen/fragments/adresse-karte")
    Template adresseKarte;

    @Inject
    @Location("personen/fragments/adresse-gueltigkeit-form")
    Template adresseGueltigkeitForm;

    @Inject
    @Location("personen/fragments/personalien-form")
    Template personalienForm;

    // ── Full Pages ────────────────────────────────────────────────────────────

    @GET
    public TemplateInstance getList() {
        return list.data("personen", personQueryService.listAllPersons());
    }

    @GET
    @Path("/{id}/edit")
    public Object getEdit(@PathParam("id") String id) {
        try {
            Person person = personQueryService.findById(id);
            return edit.data("person", person);
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity("<p>Person not found: " + id + "</p>").build();
        }
    }

    // ── htmx Fragments ────────────────────────────────────────────────────────

    /** Renders the filtered table rows with pagination (htmx live search). */
    @GET
    @Path("/fragments/list")
    public TemplateInstance getPersonenListFragment(
            @QueryParam("name") String name,
            @QueryParam("firstName") String firstName,
            @QueryParam("ahv") String ahv,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        PageResult<Person> pageResult;
        try {
            PageRequest pageRequest = new PageRequest(page, size);
            pageResult = personQueryService.searchPersons(name, firstName, ahv, null, pageRequest);
        } catch (Exception ignored) {
            pageResult = new PageResult<>(List.of(), 0, 0);
        }
        return list.getFragment("tabelle")
                .data("personen", pageResult.content())
                .data("currentPage", page)
                .data("totalPages", pageResult.totalPages())
                .data("totalElements", pageResult.totalElements())
                .data("pageSize", size)
                .data("searchName", name != null ? name : "")
                .data("searchFirstName", firstName != null ? firstName : "")
                .data("searchAhv", ahv != null ? ahv : "");
    }

    /** Returns the new-person modal form. */
    @GET
    @Path("/fragments/neu")
    public TemplateInstance getPersonenFormModal() {
        return personenFormModal.instance();
    }

    /**
     * Creates a new person from modal form data and returns a table row fragment.
     * On validation error returns 422 with an error alert HTML.
     */
    @POST
    @Path("/fragments")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object createPersonFragment(
            @FormParam("name") String name,
            @FormParam("firstName") String firstName,
            @FormParam("gender") String gender,
            @FormParam("dateOfBirth") String dateOfBirth,
            @FormParam("socialSecurityNumber") String socialSecurityNumber) {
        try {
            String id = personCommandService.createPerson(
                    name, firstName,
                    Gender.valueOf(gender),
                    LocalDate.parse(dateOfBirth),
                    socialSecurityNumber);
            Person person = personQueryService.findById(id);
            return personenRow.data("person", person);
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /** Returns an empty address-form for adding a new address. */
    @GET
    @Path("/fragments/{id}/address-form")
    public TemplateInstance getAddressForm(@PathParam("id") String id) {
        return adresseForm.data("personId", id).data("errorMessage", null);
    }

    /** Returns empty string to remove the address-form from the DOM (cancel). */
    @GET
    @Path("/fragments/{id}/address-form-cancel")
    public String cancelAddressForm() {
        return "";
    }

    /**
     * Creates a new address from form data and returns the adresse-karte fragment.
     * On conflict (409) re-renders the form with an error message.
     */
    @POST
    @Path("/fragments/{id}/addresses")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object addAddressFragment(
            @PathParam("id") String personId,
            @FormParam("addressType") String addressType,
            @FormParam("street") String street,
            @FormParam("houseNumber") String houseNumber,
            @FormParam("postalCode") String postalCode,
            @FormParam("city") String city,
            @FormParam("land") String land,
            @FormParam("validFrom") String validFrom,
            @FormParam("validTo") String validTo) {
        try {
            LocalDate from = LocalDate.parse(validFrom);
            LocalDate to = isNotBlank(validTo) ? LocalDate.parse(validTo) : null;
            String addressId = personCommandService.addAddress(
                    personId, AddressType.valueOf(addressType),
                    street, houseNumber, postalCode, city,
                    isNotBlank(land) ? land : "Schweiz",
                    from, to);
            Address address = personQueryService.findById(personId).getAddresses().stream()
                    .filter(a -> a.getAddressId().equals(addressId))
                    .findFirst().orElseThrow();
            return adresseKarte.data("adresse", address).data("personId", personId);
        } catch (AddressOverlapException e) {
            String error = "<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>";
            return adresseForm
                    .data("personId", personId)
                    .data("errorMessage", error);
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /** Returns the adresse-karte fragment (used for cancel of gueltigkeit edit). */
    @GET
    @Path("/fragments/{id}/addresses/{aid}/card")
    public Object getAddressCard(@PathParam("id") String personId, @PathParam("aid") String aid) {
        try {
            Address address = personQueryService.findById(personId).getAddresses().stream()
                    .filter(a -> a.getAddressId().equals(aid))
                    .findFirst()
                    .orElseThrow(() -> new AddressNotFoundException(aid));
            return adresseKarte.data("adresse", address).data("personId", personId);
        } catch (PersonNotFoundException | AddressNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        }
    }

    /** Returns the inline gueltigkeit edit form for an address card. */
    @GET
    @Path("/fragments/{id}/addresses/{aid}/validity")
    public Object getAddressValidityForm(@PathParam("id") String personId,
                                             @PathParam("aid") String aid) {
        try {
            Address address = personQueryService.findById(personId).getAddresses().stream()
                    .filter(a -> a.getAddressId().equals(aid))
                    .findFirst()
                    .orElseThrow(() -> new AddressNotFoundException(aid));
            return adresseGueltigkeitForm.data("adresse", address).data("personId", personId);
        } catch (PersonNotFoundException | AddressNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        }
    }

    /**
     * Updates address validity period from inline form and returns refreshed adresse-karte.
     * On conflict re-renders the gueltigkeit form with an error.
     */
    @PUT
    @Path("/fragments/{id}/addresses/{aid}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object updateAddressValidityFragment(
            @PathParam("id") String personId,
            @PathParam("aid") String aid,
            @FormParam("validFrom") String validFrom,
            @FormParam("validTo") String validTo) {
        try {
            LocalDate from = LocalDate.parse(validFrom);
            LocalDate to = isNotBlank(validTo) ? LocalDate.parse(validTo) : null;
            personCommandService.updateAddressValidity(personId, aid, from, to);
            Address address = personQueryService.findById(personId).getAddresses().stream()
                    .filter(a -> a.getAddressId().equals(aid))
                    .findFirst().orElseThrow();
            return adresseKarte.data("adresse", address).data("personId", personId);
        } catch (AddressOverlapException e) {
            Address address = personQueryService.findById(personId).getAddresses().stream()
                    .filter(a -> a.getAddressId().equals(aid))
                    .findFirst().orElseThrow();
            return adresseGueltigkeitForm
                    .data("adresse", address)
                    .data("personId", personId)
                    .data("errorMessage", escapeHtml(e.getMessage()));
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /**
     * Updates personalien from form data and re-renders the personalien-form with success flag.
     */
    @PUT
    @Path("/fragments/{id}/personal-data")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object updatePersonalienFragment(
            @PathParam("id") String personId,
            @FormParam("name") String name,
            @FormParam("firstName") String firstName,
            @FormParam("gender") String gender,
            @FormParam("dateOfBirth") String dateOfBirth) {
        try {
            personCommandService.updatePersonalData(personId, name, firstName,
                    Gender.valueOf(gender),
                    LocalDate.parse(dateOfBirth));
            Person person = personQueryService.findById(personId);
            return personalienForm.data("person", person).data("success", true);
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
