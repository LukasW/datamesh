package ch.css.partner.infrastructure.web;

import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Redirects the application root (/) to the Personenverwaltung view.
 * Uses the Vert.x router directly (order = -1) so the redirect fires before
 * any JAX-RS route — and before JAX-RS serialization can turn the response into "{}".
 */
@ApplicationScoped
public class RootRedirectResource {

    @Inject
    Router router;

    void onStart(@Observes StartupEvent ev) {
        router.get("/").order(-1).handler(ctx -> ctx.redirect("/persons"));
    }
}
