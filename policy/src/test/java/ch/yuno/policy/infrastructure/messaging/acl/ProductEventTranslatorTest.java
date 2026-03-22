package ch.yuno.policy.infrastructure.messaging.acl;

import ch.yuno.policy.domain.model.ProductView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProductEventTranslator – ACL Unit Tests")
class ProductEventTranslatorTest {

    private ProductEventTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new ProductEventTranslator(new ObjectMapper());
    }

    @Test
    @DisplayName("translate – valid ProductDefined event produces ProductView")
    void translate_validEvent_producesProductView() throws Exception {
        String payload = """
                {"eventId":"abc","eventType":"ProductDefined","productId":"prod-1",
                 "name":"Hausrat Basis","productLine":"HOUSEHOLD_CONTENTS","basePremium":"200.00",
                 "timestamp":"2026-01-01T00:00:00Z"}""";

        ProductView view = translator.translate(payload);

        assertEquals("prod-1", view.getProductId());
        assertEquals("Hausrat Basis", view.getName());
        assertEquals("HOUSEHOLD_CONTENTS", view.getProductLine());
        assertEquals(new BigDecimal("200.00"), view.getBasePremium());
        assertTrue(view.isActive());
    }

    @Test
    @DisplayName("translate – missing productId throws IllegalArgumentException")
    void translate_missingProductId_throws() {
        String payload = """
                {"eventType":"ProductDefined","name":"Test"}""";

        assertThrows(IllegalArgumentException.class, () -> translator.translate(payload));
    }

    @Test
    @DisplayName("extractProductId – valid deprecated event returns productId")
    void extractProductId_validEvent_returnsId() throws Exception {
        String payload = """
                {"eventId":"abc","eventType":"ProductDeprecated","productId":"prod-1",
                 "timestamp":"2026-01-01T00:00:00Z"}""";

        assertEquals("prod-1", translator.extractProductId(payload));
    }

    @Test
    @DisplayName("extractProductId – missing productId throws IllegalArgumentException")
    void extractProductId_missingId_throws() {
        String payload = """
                {"eventType":"ProductDeprecated"}""";

        assertThrows(IllegalArgumentException.class, () -> translator.extractProductId(payload));
    }
}
