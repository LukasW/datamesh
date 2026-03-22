package ch.yuno.hrintegration.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChangeDetectorTest {

    private final ChangeDetector detector = new ChangeDetector();

    @Test
    void shouldReturnFullUrlOnFirstPoll() {
        var url = detector.buildUrl("http://hr:9085/odata", "Employees");

        assertEquals("http://hr:9085/odata/Employees", url);
    }

    @Test
    void shouldReturnFilteredUrlAfterRecordedPoll() {
        detector.recordPoll("Employees");

        var url = detector.buildUrl("http://hr:9085/odata", "Employees");

        assertTrue(url.contains("$filter=lastModified gt "));
        assertTrue(url.startsWith("http://hr:9085/odata/Employees?"));
    }

    @Test
    void shouldResetToFullUrlAfterReset() {
        detector.recordPoll("Employees");
        detector.reset("Employees");

        var url = detector.buildUrl("http://hr:9085/odata", "Employees");

        assertEquals("http://hr:9085/odata/Employees", url);
    }

    @Test
    void shouldTrackEntitySetsSeparately() {
        detector.recordPoll("Employees");

        var empUrl = detector.buildUrl("http://hr:9085/odata", "Employees");
        var orgUrl = detector.buildUrl("http://hr:9085/odata", "OrganizationUnits");

        assertTrue(empUrl.contains("$filter="));
        assertFalse(orgUrl.contains("$filter="));
    }
}
