package ch.yuno.hrintegration.processor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the last poll timestamp per entity set to enable delta queries.
 * On first poll, no filter is applied (full bootstrap).
 * On subsequent polls, a $filter on lastModified is added to the OData URL.
 */
@ApplicationScoped
@Named("changeDetector")
public class ChangeDetector {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final Map<String, Instant> lastPolled = new ConcurrentHashMap<>();

    /**
     * Returns the OData URL with an optional $filter for delta detection.
     */
    public String buildUrl(String baseUrl, String entitySet) {
        var lastPoll = lastPolled.get(entitySet);
        if (lastPoll != null) {
            var ts = ISO.format(lastPoll.atZone(ZoneOffset.UTC));
            return baseUrl + "/" + entitySet + "?$filter=lastModified gt " + ts;
        }
        return baseUrl + "/" + entitySet;
    }

    /**
     * Records the current timestamp after a successful poll.
     */
    public void recordPoll(String entitySet) {
        lastPolled.put(entitySet, Instant.now());
    }

    /**
     * Resets the delta state for a given entity set (triggers full reload on next poll).
     */
    public void reset(String entitySet) {
        lastPolled.remove(entitySet);
    }
}
