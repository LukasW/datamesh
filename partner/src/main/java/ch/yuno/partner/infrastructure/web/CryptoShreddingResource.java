package ch.yuno.partner.infrastructure.web;

import ch.yuno.partner.domain.port.out.PiiEncryptor;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST endpoint for GDPR/nDSG right-to-erasure via crypto-shredding (ADR-009).
 * Deleting a person's encryption key renders all PII in Kafka events and
 * Iceberg Parquet files permanently unreadable.
 */
@Path("/api/crypto-shredding")
@Produces(MediaType.APPLICATION_JSON)
public class CryptoShreddingResource {

    @Inject
    PiiEncryptor piiEncryptor;

    /**
     * Deletes the per-entity encryption key in Vault.
     * After this call, all encrypted PII fields for this person become permanently unreadable
     * across Kafka topics, Iceberg tables, and any downstream materialized views.
     */
    @DELETE
    @Path("/persons/{personId}")
    public Response deleteKey(@PathParam("personId") String personId) {
        piiEncryptor.deleteKey(personId);
        return Response.ok()
                .entity("{\"status\":\"shredded\",\"personId\":\"" + personId + "\"," +
                        "\"message\":\"Encryption key deleted. All PII for this person is now permanently unreadable.\"}")
                .build();
    }
}
