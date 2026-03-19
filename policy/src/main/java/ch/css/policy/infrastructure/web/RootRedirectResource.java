package ch.css.policy.infrastructure.web;

import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Redirects the application root (/) to the Policenverwaltung view.
 */
@ApplicationScoped
public class RootRedirectResource {

    @Inject
    Router router;

    void onStart(@Observes StartupEvent ev) {
        router.get("/").order(-1).handler(ctx -> ctx.redirect("/policies"));
    }
}

