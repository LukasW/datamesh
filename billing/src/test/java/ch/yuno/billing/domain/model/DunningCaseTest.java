package ch.yuno.billing.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DunningCaseTest {

    @Test
    void newDunningCaseStartsAtReminder() {
        DunningCase dc = new DunningCase("invoice-1");
        assertEquals(DunningLevel.REMINDER, dc.getLevel());
        assertNotNull(dc.getDunningCaseId());
    }

    @Test
    void escalateReminderToFirstWarning() {
        DunningCase dc = new DunningCase("invoice-1");
        dc.escalate();
        assertEquals(DunningLevel.FIRST_WARNING, dc.getLevel());
    }

    @Test
    void escalateFirstWarningToFinalWarning() {
        DunningCase dc = new DunningCase("invoice-1");
        dc.escalate();
        dc.escalate();
        assertEquals(DunningLevel.FINAL_WARNING, dc.getLevel());
    }

    @Test
    void escalateFinalWarningToCollection() {
        DunningCase dc = new DunningCase("invoice-1");
        dc.escalate();
        dc.escalate();
        dc.escalate();
        assertEquals(DunningLevel.COLLECTION, dc.getLevel());
    }

    @Test
    void escalateFromCollectionThrows() {
        DunningCase dc = new DunningCase("invoice-1");
        dc.escalate();
        dc.escalate();
        dc.escalate();
        assertThrows(IllegalStateException.class, dc::escalate);
    }
}
