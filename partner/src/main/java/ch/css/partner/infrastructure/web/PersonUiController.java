package ch.css.partner.infrastructure.web;

import ch.css.partner.domain.model.Address;
import ch.css.partner.domain.model.AddressType;
import ch.css.partner.domain.model.Gender;
import ch.css.partner.domain.model.Person;
import ch.css.partner.domain.service.AddressOverlapException;
import ch.css.partner.domain.service.AddressNotFoundException;
import ch.css.partner.domain.service.PersonApplicationService;
import ch.css.partner.domain.service.PersonNotFoundException;
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
    PersonApplicationService personService;

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
        return list.data("personen", personService.listAllPersons());
    }

    @GET
    @Path("/{id}/edit")
    public Object getEdit(@PathParam("id") String id) {
        try {
            Person person = personService.findById(id);
            return edit.data("person", person);
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity("<p>Person not found: " + id + "</p>").build();
        }
    }

    // ── htmx Fragments ────────────────────────────────────────────────────────

    /** Renders the filtered table rows (htmx live search). */
    @GET
    @Path("/fragments/list")
    public TemplateInstance getPersonenListFragment(
            @QueryParam("name") String name,
            @QueryParam("firstName") String firstName,
            @QueryParam("ahv") String ahv) {

        boolean hasFilter = isNotBlank(name) || isNotBlank(firstName) || isNotBlank(ahv);
        List<Person> personen;
        try {
            personen = hasFilter
                    ? personService.searchPersons(name, firstName, ahv, null)
                    : personService.listAllPersons();
        } catch (Exception ignored) {
            personen = List.of();
        }
        return list.getFragment("tabelle").data("personen", personen);
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
            String id = personService.createPerson(
                    name, firstName,
                    Gender.valueOf(gender),
                    LocalDate.parse(dateOfBirth),
                    socialSecurityNumber);
            Person person = personService.findById(id);
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
            String addressId = personService.addAddress(
                    personId, AddressType.valueOf(addressType),
                    street, houseNumber, postalCode, city,
                    isNotBlank(land) ? land : "Schweiz",
                    from, to);
            Address address = personService.findById(personId).getAddresses().stream()
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
            Address address = personService.findById(personId).getAddresses().stream()
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
            Address address = personService.findById(personId).getAddresses().stream()
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
            personService.updateAddressValidity(personId, aid, from, to);
            Address address = personService.findById(personId).getAddresses().stream()
                    .filter(a -> a.getAddressId().equals(aid))
                    .findFirst().orElseThrow();
            return adresseKarte.data("adresse", address).data("personId", personId);
        } catch (AddressOverlapException e) {
            Address address = personService.findById(personId).getAddresses().stream()
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
            personService.updatePersonalData(personId, name, firstName,
                    Gender.valueOf(gender),
                    LocalDate.parse(dateOfBirth));
            Person person = personService.findById(personId);
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
