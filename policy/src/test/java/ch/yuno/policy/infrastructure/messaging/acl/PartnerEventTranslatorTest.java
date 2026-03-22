package ch.yuno.policy.infrastructure.messaging.acl;

import ch.yuno.policy.domain.model.PartnerView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PartnerEventTranslator – ACL Unit Tests")
class PartnerEventTranslatorTest {

    private PartnerEventTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new PartnerEventTranslator(new ObjectMapper());
    }

    @Test
    @DisplayName("translate – valid PersonCreated event produces PartnerView")
    void translate_validEvent_producesPartnerView() throws Exception {
        String payload = """
                {"eventId":"abc","eventType":"PersonCreated","personId":"p-123",
                 "name":"Muster","firstName":"Hans","timestamp":"2026-01-01T00:00:00Z"}""";

        PartnerView view = translator.translate(payload);

        assertEquals("p-123", view.getPartnerId());
        assertEquals("Hans Muster", view.getName());
    }

    @Test
    @DisplayName("translate – missing personId throws IllegalArgumentException")
    void translate_missingPersonId_throws() {
        String payload = """
                {"eventType":"PersonCreated","name":"Muster","firstName":"Hans"}""";

        assertThrows(IllegalArgumentException.class, () -> translator.translate(payload));
    }

    @Test
    @DisplayName("translate – missing name fields throws IllegalArgumentException")
    void translate_missingName_throws() {
        String payload = """
                {"eventType":"PersonCreated","personId":"p-123"}""";

        assertThrows(IllegalArgumentException.class, () -> translator.translate(payload));
    }

    @Test
    @DisplayName("translate – malformed JSON throws Exception")
    void translate_malformedJson_throws() {
        assertThrows(Exception.class, () -> translator.translate("not-json"));
    }
}
