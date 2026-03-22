package ch.yuno.hr;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wrapper that mimics OData v4 collection response format.
 * Returns entities in the standard {@code {"value": [...]}} envelope.
 */
public record ODataResponse<T>(
    @JsonProperty("@odata.context") String context,
    @JsonProperty("value") List<T> value
) {
    public static <T> ODataResponse<T> of(String entitySet, List<T> entities) {
        return new ODataResponse<>("$metadata#" + entitySet, entities);
    }
}
