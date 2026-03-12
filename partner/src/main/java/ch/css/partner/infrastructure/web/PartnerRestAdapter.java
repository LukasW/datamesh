package ch.css.partner.infrastructure.web;

import ch.css.partner.domain.model.Partner;
import ch.css.partner.domain.service.PartnerApplicationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * REST Adapter: Driving Adapter for HTTP API
 * Exposes use cases as REST endpoints
 */
@Path("/api/partners")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Partners", description = "Partner / Customer Management API")
public class PartnerRestAdapter {

    @Inject
    PartnerApplicationService partnerService;

    /**
     * GET /api/partners/search?name={nameFragment}
     * Search partners by name
     */
    @GET
    @Path("/search")
    @Operation(summary = "Search partners by name", description = "Returns all partners whose name contains the given fragment (case-insensitive).")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of matching partners",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = PartnerDto.class))),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response searchPartners(
            @Parameter(description = "Name fragment to search for", required = true)
            @QueryParam("name") String name) {
        try {
            List<Partner> results = partnerService.searchByName(name);
            List<PartnerDto> dtos = results.stream()
                    .map(this::toDto)
                    .toList();
            return Response.ok(dtos).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }
    }

    /**
     * GET /api/partners/{partnerId}
     * Get partner by ID
     */
    @GET
    @Path("/{partnerId}")
    @Operation(summary = "Get partner by ID", description = "Retrieves a single partner by their unique ID.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Partner found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = PartnerDto.class))),
            @APIResponse(responseCode = "404", description = "Partner not found")
    })
    public Response getPartner(
            @Parameter(description = "Unique partner ID", required = true)
            @PathParam("partnerId") String partnerId) {
        try {
            Partner partner = partnerService.getPartner(partnerId);
            return Response.ok(toDto(partner)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Partner not found"))
                    .build();
        }
    }

    /**
     * POST /api/partners
     * Create new partner
     */
    @POST
    @Operation(summary = "Create a new partner", description = "Creates a new partner/customer and returns their generated ID.")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Partner created successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = CreatePartnerResponse.class))),
            @APIResponse(responseCode = "400", description = "Invalid request – validation failed"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response createPartner(CreatePartnerRequest request) {
        try {
            String partnerId = partnerService.createPartner(
                    request.name,
                    request.email,
                    request.phone,
                    request.partnerType);
            return Response.status(Response.Status.CREATED)
                    .entity(new CreatePartnerResponse(partnerId))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }
    }

    private PartnerDto toDto(Partner partner) {
        return new PartnerDto(
                partner.getPartnerId(),
                partner.getName(),
                partner.getEmail(),
                partner.getPhone(),
                partner.getStreet(),
                partner.getCity(),
                partner.getPostalCode(),
                partner.getCountry(),
                partner.getPartnerType().name(),
                partner.getStatus().name());
    }
}

/**
 * DTO: Partner Response
 */
class PartnerDto {
    public String partnerId;
    public String name;
    public String email;
    public String phone;
    public String street;
    public String city;
    public String postalCode;
    public String country;
    public String partnerType;
    public String status;

    public PartnerDto(String partnerId, String name, String email, String phone,
                      String street, String city, String postalCode, String country,
                      String partnerType, String status) {
        this.partnerId = partnerId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.street = street;
        this.city = city;
        this.postalCode = postalCode;
        this.country = country;
        this.partnerType = partnerType;
        this.status = status;
    }
}

/**
 * DTO: Create Partner Request
 */
class CreatePartnerRequest {
    public String name;
    public String email;
    public String phone;
    public String partnerType;
}

/**
 * DTO: Create Partner Response
 */
class CreatePartnerResponse {
    public String partnerId;

    public CreatePartnerResponse(String partnerId) {
        this.partnerId = partnerId;
    }
}

/**
 * DTO: Error Response
 */
class ErrorResponse {
    public String message;

    public ErrorResponse(String message) {
        this.message = message;
    }
}
