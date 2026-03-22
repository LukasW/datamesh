package ch.yuno.product.infrastructure.web;

import ch.yuno.product.domain.model.PageRequest;
import ch.yuno.product.domain.model.PageResult;
import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.domain.service.ProductCommandService;
import ch.yuno.product.domain.service.ProductNotFoundException;
import ch.yuno.product.domain.service.ProductQueryService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Qute UI Controller for the Product Management screens.
 * Handles full-page renders and htmx fragment endpoints.
 */
@Path("/products")
@Produces(MediaType.TEXT_HTML)
public class ProductUiController {

    @Inject
    ProductCommandService productCommandService;

    @Inject
    ProductQueryService productQueryService;

    @Inject
    @Location("products/list")
    Template list;

    @Inject
    @Location("products/edit")
    Template edit;

    @Inject
    @Location("products/fragments/product-row")
    Template productRow;

    @Inject
    @Location("products/fragments/product-form-modal")
    Template productFormModal;

    @Inject
    @Location("products/fragments/product-details-form")
    Template productDetailsForm;

    // ── Full Pages ────────────────────────────────────────────────────────────

    @GET
    public TemplateInstance getList() {
        return list.data("products", productQueryService.listAllProducts())
                   .data("productLines", ProductLine.values());
    }

    @GET
    @Path("/{id}/edit")
    public Object getEdit(@PathParam("id") String id) {
        try {
            Product product = productQueryService.findById(ProductId.of(id));
            return edit.data("product", product)
                       .data("productLines", ProductLine.values());
        } catch (ProductNotFoundException e) {
            return Response.status(404).entity("<p>Product not found: " + id + "</p>").build();
        }
    }

    // ── htmx Fragments ────────────────────────────────────────────────────────

    /** Renders the filtered table rows with pagination (htmx live search). */
    @GET
    @Path("/fragments/list")
    public TemplateInstance getProductListFragment(
            @QueryParam("name") String name,
            @QueryParam("productLine") String productLine,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        ProductLine line = (productLine != null && !productLine.isBlank() && !"ALL".equals(productLine))
                ? ProductLine.valueOf(productLine) : null;
        PageResult<Product> pageResult;
        try {
            PageRequest pageRequest = new PageRequest(page, size);
            pageResult = productQueryService.searchProducts(name, line, pageRequest);
        } catch (Exception ignored) {
            pageResult = new PageResult<>(List.of(), 0, 0);
        }
        return list.getFragment("tabelle")
                .data("products", pageResult.content())
                .data("currentPage", page)
                .data("totalPages", pageResult.totalPages())
                .data("totalElements", pageResult.totalElements())
                .data("pageSize", size)
                .data("searchName", name != null ? name : "")
                .data("searchProductLine", productLine != null ? productLine : "ALL");
    }

    /** Returns the new-product modal form. */
    @GET
    @Path("/fragments/neu")
    public TemplateInstance getProductFormModal() {
        return productFormModal.data("productLines", ProductLine.values());
    }

    /**
     * Creates a new product from modal form data and returns a table row fragment.
     * On validation error returns 422 with an error alert HTML.
     */
    @POST
    @Path("/fragments")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object createProductFragment(
            @FormParam("name") String name,
            @FormParam("description") String description,
            @FormParam("productLine") String productLine,
            @FormParam("basePremium") String basePremium) {
        try {
            BigDecimal premium = new BigDecimal(basePremium.replace(",", "."));
            ProductId id = productCommandService.defineProduct(
                    name, description,
                    ProductLine.valueOf(productLine),
                    premium);
            Product product = productQueryService.findById(id);
            return productRow.data("product", product);
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /**
     * Updates product details from form and re-renders the details form with success flag.
     * Uses POST (not PUT) for reliable htmx form submission. Errors return 200 so htmx shows them.
     */
    @POST
    @Path("/fragments/{id}/details")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object updateProductFragment(
            @PathParam("id") String productId,
            @FormParam("name") String name,
            @FormParam("description") String description,
            @FormParam("productLine") String productLine,
            @FormParam("basePremium") String basePremium) {
        try {
            BigDecimal premium = new BigDecimal(basePremium.replace(",", "."));
            Product product = productCommandService.updateProduct(ProductId.of(productId), name, description,
                    ProductLine.valueOf(productLine), premium);
            return productDetailsForm.data("product", product)
                                     .data("productLines", ProductLine.values())
                                     .data("success", true);
        } catch (ProductNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (Exception e) {
            return Response.status(200)
                    .entity("<div id=\"product-details-form\"><div class=\"alert alert-danger\">"
                            + escapeHtml(e.getMessage()) + "</div></div>")
                    .build();
        }
    }

    /**
     * Deprecates a product and re-renders the details form.
     */
    @POST
    @Path("/fragments/{id}/deprecate")
    public Object deprecateProductFragment(@PathParam("id") String productId) {
        try {
            Product product = productCommandService.deprecateProduct(ProductId.of(productId));
            return productDetailsForm.data("product", product)
                                     .data("productLines", ProductLine.values())
                                     .data("success", true);
        } catch (ProductNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (Exception e) {
            return Response.status(200)
                    .entity("<div id=\"product-details-form\"><div class=\"alert alert-danger\">"
                            + escapeHtml(e.getMessage()) + "</div></div>")
                    .build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
