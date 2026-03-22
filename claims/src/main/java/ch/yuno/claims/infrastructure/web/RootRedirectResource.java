package ch.yuno.claims.infrastructure.web;

import io.quarkus.vertx.http.runtime.filters.accesslog.AccessLogHandler;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class RootRedirectResource {

    public void init(@Observes Router router) {
        router.get("/").handler(ctx -> ctx.response()
                .setStatusCode(302)
                .putHeader("Location", "/claims")
                .end());
    }
}
