package ch.yuno.product.infrastructure.web;

import ch.yuno.product.domain.model.PageRequest;
import ch.yuno.product.domain.model.PageResult;
import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.domain.service.ProductCommandService;
import ch.yuno.product.domain.service.ProductNotFoundException;
import ch.yuno.product.domain.service.ProductQueryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST adapter for Product CRUD.
 * Maps HTTP requests to ProductCommandService and ProductQueryService use cases.
 */
@Path("/api/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductRestAdapter {

    @Inject
    ProductCommandService productCommandService;

    @Inject
    ProductQueryService productQueryService;

    // ── Product CRUD ───────────────────────────────────────────────────────────

    @POST
    public Response defineProduct(CreateProductRequest req) {
        try {
            ProductId id = productCommandService.defineProduct(
                    req.name(), req.description(),
                    ProductLine.valueOf(req.productLine()),
                    req.basePremium());
            return Response.status(201).entity(Map.of("id", id.value())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    public Response listProducts(
            @QueryParam("name") String name,
            @QueryParam("productLine") String productLine,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        try {
            ProductLine line = (productLine != null && !productLine.isBlank())
                    ? ProductLine.valueOf(productLine) : null;
            PageRequest pageRequest = new PageRequest(page, size);
            PageResult<Product> pageResult = productQueryService.searchProducts(name, line, pageRequest);
            PagedResponse<ProductDto> response = new PagedResponse<>(
                    pageResult.content().stream().map(ProductDto::from).toList(),
                    pageResult.totalElements(),
                    pageResult.totalPages(),
                    page, size);
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}")
    public Response getProduct(@PathParam("id") String id) {
        try {
            return Response.ok(ProductDto.from(productQueryService.findById(ProductId.of(id)))).build();
        } catch (ProductNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updateProduct(@PathParam("id") String id, UpdateProductRequest req) {
        try {
            return Response.ok(ProductDto.from(productCommandService.updateProduct(
                    ProductId.of(id), req.name(), req.description(),
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
            return Response.ok(ProductDto.from(productCommandService.deprecateProduct(ProductId.of(id)))).build();
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
            productCommandService.deleteProduct(ProductId.of(id));
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
                    p.getProductId().value(), p.getName(), p.getDescription(),
                    p.getProductLine().name(), p.getBasePremium(), p.getStatus().name());
        }
    }

    public record PagedResponse<T>(
            List<T> content, long totalElements, int totalPages, int page, int size) {}
}
