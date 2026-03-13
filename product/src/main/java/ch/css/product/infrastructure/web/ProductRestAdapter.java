package ch.css.product.infrastructure.web;

import ch.css.product.domain.model.Product;
import ch.css.product.domain.model.ProductLine;
import ch.css.product.domain.service.ProductApplicationService;
import ch.css.product.domain.service.ProductNotFoundException;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST adapter for Product CRUD.
 * Maps HTTP requests to ProductApplicationService use cases.
 */
@Path("/api/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductRestAdapter {

    @Inject
    ProductApplicationService productService;

    // ── Product CRUD ───────────────────────────────────────────────────────────

    @POST
    public Response defineProduct(CreateProductRequest req) {
        try {
            String id = productService.defineProduct(
                    req.name(), req.description(),
                    ProductLine.valueOf(req.productLine()),
                    req.basePremium());
            return Response.status(201).entity(Map.of("id", id)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    public Response listProducts(
            @QueryParam("name") String name,
            @QueryParam("productLine") String productLine) {
        try {
            ProductLine line = (productLine != null && !productLine.isBlank())
                    ? ProductLine.valueOf(productLine) : null;
            List<ProductDto> result = productService.searchProducts(name, line)
                    .stream().map(ProductDto::from).toList();
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}")
    public Response getProduct(@PathParam("id") String id) {
        try {
            return Response.ok(ProductDto.from(productService.findById(id))).build();
        } catch (ProductNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updateProduct(@PathParam("id") String id, UpdateProductRequest req) {
        try {
            return Response.ok(ProductDto.from(productService.updateProduct(
                    id, req.name(), req.description(),
                    ProductLine.valueOf(req.productLine()), req.basePremium()))).build();
        } catch (ProductNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/deprecate")
    public Response deprecateProduct(@PathParam("id") String id) {
        try {
            return Response.ok(ProductDto.from(productService.deprecateProduct(id))).build();
        } catch (ProductNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deleteProduct(@PathParam("id") String id) {
        try {
            productService.deleteProduct(id);
            return Response.noContent().build();
        } catch (ProductNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    // ── Request/Response DTOs ──────────────────────────────────────────────────

    public record CreateProductRequest(
            String name, String description, String productLine, BigDecimal basePremium) {}

    public record UpdateProductRequest(
            String name, String description, String productLine, BigDecimal basePremium) {}

    public record ProductDto(
            String productId, String name, String description,
            String productLine, BigDecimal basePremium, String status) {

        public static ProductDto from(Product p) {
            return new ProductDto(
                    p.getProductId(), p.getName(), p.getDescription(),
                    p.getProductLine().name(), p.getBasePremium(), p.getStatus().name());
        }
    }
}
