package ch.css.partner.infrastructure.web;

import ch.css.partner.domain.model.Address;
import ch.css.partner.domain.model.AddressType;
import ch.css.partner.domain.model.Gender;
import ch.css.partner.domain.model.Person;
import ch.css.partner.domain.service.AddressOverlapException;
import ch.css.partner.domain.service.AddressNotFoundException;
import ch.css.partner.domain.service.PersonApplicationService;
import ch.css.partner.domain.service.PersonNotFoundException;
import ch.css.partner.infrastructure.persistence.PersonAuditAdapter;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST adapter for Person CRUD and Address sub-resource.
 * Maps HTTP requests to PersonApplicationService use cases.
 */
@Path("/api/persons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PersonRestAdapter {

    @Inject
    PersonApplicationService personService;

    @Inject
    PersonAuditAdapter auditAdapter;

    // ── Person CRUD ───────────────────────────────────────────────────────────

    @POST
    public Response createPerson(CreatePersonRequest req) {
        try {
            String id = personService.createPerson(
                    req.name(), req.firstName(),
                    Gender.valueOf(req.gender()),
                    req.dateOfBirth(), req.socialSecurityNumber());
            return Response.status(201).entity(Map.of("id", id)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    public Response searchPersons(
            @QueryParam("name") String name,
            @QueryParam("firstName") String firstName,
            @QueryParam("ahv") String ahv,
            @QueryParam("dateOfBirth") String dateOfBirth) {
        boolean hasFilter = isNotBlank(name) || isNotBlank(firstName)
                || isNotBlank(ahv) || isNotBlank(dateOfBirth);
        if (!hasFilter) {
            return Response.ok(List.of()).build();
        }
        try {
            LocalDate dob = isNotBlank(dateOfBirth) ? LocalDate.parse(dateOfBirth) : null;
            List<PersonDto> result = personService.searchPersons(name, firstName, ahv, dob)
                    .stream().map(PersonDto::from).toList();
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}")
    public Response getPerson(@PathParam("id") String id) {
        try {
            return Response.ok(PersonDto.from(personService.findById(id))).build();
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updatePerson(@PathParam("id") String id, UpdatePersonRequest req) {
        try {
            personService.updatePersonalData(id, req.name(), req.firstName(),
                    Gender.valueOf(req.gender()), req.dateOfBirth());
            return Response.ok(PersonDto.from(personService.findById(id))).build();
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deletePerson(@PathParam("id") String id) {
        try {
            personService.deletePerson(id);
            return Response.noContent().build();
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    // ── Addresses ─────────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/addresses")
    public Response getAddresses(@PathParam("id") String id,
                                  @QueryParam("typ") String typ,
                                  @QueryParam("current") Boolean current) {
        try {
            List<Address> addresses = personService.getAddresses(id);
            if (isNotBlank(typ)) {
                AddressType addressType = AddressType.valueOf(typ);
                addresses = addresses.stream().filter(a -> a.getAddressType() == addressType).toList();
            }
            if (Boolean.TRUE.equals(current)) {
                addresses = addresses.stream().filter(Address::isCurrent).toList();
            }
            return Response.ok(addresses.stream().map(AddressDto::from).toList()).build();
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/addresses")
    public Response addAddress(@PathParam("id") String id, AddAddressRequest req) {
        try {
            String addressId = personService.addAddress(
                    id,
                    AddressType.valueOf(req.addressType()),
                    req.street(), req.houseNumber(), req.postalCode(), req.city(),
                    req.land() != null ? req.land() : "Schweiz",
                    req.validFrom(), req.validTo());
            Address address = personService.findById(id).getAddresses().stream()
                    .filter(a -> a.getAddressId().equals(addressId))
                    .findFirst().orElseThrow();
            return Response.status(201).entity(AddressDto.from(address)).build();
        } catch (AddressOverlapException e) {
            return Response.status(409).entity(Map.of("message", e.getMessage())).build();
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}/addresses/{aid}")
    public Response updateAddressValidity(@PathParam("id") String id,
                                          @PathParam("aid") String aid,
                                          UpdateAddressRequest req) {
        try {
            personService.updateAddressValidity(id, aid, req.validFrom(), req.validTo());
            Address address = personService.findById(id).getAddresses().stream()
                    .filter(a -> a.getAddressId().equals(aid))
                    .findFirst().orElseThrow();
            return Response.ok(AddressDto.from(address)).build();
        } catch (AddressOverlapException e) {
            return Response.status(409).entity(Map.of("message", e.getMessage())).build();
        } catch (PersonNotFoundException | AddressNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}/addresses/{aid}")
    public Response deleteAddress(@PathParam("id") String id, @PathParam("aid") String aid) {
        try {
            personService.deleteAddress(id, aid);
            return Response.noContent().build();
        } catch (PersonNotFoundException | AddressNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    // ── Audit History ─────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/history")
    public Response getPersonHistory(@PathParam("id") String id) {
        return Response.ok(auditAdapter.getPersonHistory(id)).build();
    }

    @GET
    @Path("/{id}/addresses/{aid}/history")
    public Response getAddressHistory(@PathParam("id") String id, @PathParam("aid") String aid) {
        return Response.ok(auditAdapter.getAddressHistory(aid)).build();
    }

    // ── Request/Response DTOs ─────────────────────────────────────────────────

    public record CreatePersonRequest(
            String name, String firstName, String gender,
            LocalDate dateOfBirth, String socialSecurityNumber) {}

    public record UpdatePersonRequest(
            String name, String firstName, String gender, LocalDate dateOfBirth) {}

    public record AddAddressRequest(
            String addressType, String street, String houseNumber,
            String postalCode, String city, String land,
            LocalDate validFrom, LocalDate validTo) {}

    public record UpdateAddressRequest(LocalDate validFrom, LocalDate validTo) {}

    public record PersonDto(
            String personId, String name, String firstName, String gender,
            LocalDate dateOfBirth, String socialSecurityNumber, List<AddressDto> addresses) {

        public static PersonDto from(Person p) {
            return new PersonDto(
                    p.getPersonId(), p.getName(), p.getFirstName(),
                    p.getGender().name(), p.getDateOfBirth(),
                    p.getSocialSecurityNumber() != null ? p.getSocialSecurityNumber().formatted() : null,
                    p.getAddresses().stream().map(AddressDto::from).toList());
        }
    }

    public record AddressDto(
            String addressId, String personId, String addressType,
            String street, String houseNumber, String postalCode, String city, String land,
            LocalDate validFrom, LocalDate validTo, boolean current) {

        public static AddressDto from(Address a) {
            return new AddressDto(
                    a.getAddressId(), a.getPersonId(), a.getAddressType().name(),
                    a.getStreet(), a.getHouseNumber(), a.getPostalCode(), a.getCity(), a.getLand(),
                    a.getValidFrom(), a.getValidTo(), a.isCurrent());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
