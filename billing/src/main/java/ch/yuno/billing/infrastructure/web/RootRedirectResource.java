package ch.yuno.billing.infrastructure.web;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class RootRedirectResource {

    public void init(@Observes Router router) {
        router.get("/").handler(ctx -> ctx.redirect("/billing"));
    }
}
