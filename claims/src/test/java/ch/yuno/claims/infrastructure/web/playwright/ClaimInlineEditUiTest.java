package ch.yuno.claims.infrastructure.web.playwright;

import ch.yuno.claims.infrastructure.web.playwright.pages.ClaimListPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Playwright UI tests for the htmx inline-edit feature on OPEN claims.
 *
 * Tests covered:
 * - Clicking "Aendern" swaps the row with an inline edit form
 * - The inline form is pre-filled with the current values
 * - Saving with a new description updates the row and removes the form
 * - Clicking "Abbrechen" restores the original read-only row
 */
@QuarkusTest
@TestSecurity(authorizationEnabled = false)
@Tag("playwright")
class ClaimInlineEditUiTest extends BasePlaywrightTest {

    private static final String POLICY_ID = "cccccccc-0003-0003-0003-000000000003";
    private static final String ORIG_DESC = "Hagel Terrassendach";

    ClaimListPage listPage;

    @BeforeEach
    void initPage() {
        listPage = new ClaimListPage(page);
        dataHelper.insertPolicySnapshot(POLICY_ID);
    }

    @Test
    void clickEdit_swapsRowWithInlineForm() {
        String claimId = dataHelper.insertOpenClaim(POLICY_ID, ORIG_DESC, LocalDate.of(2026, 3, 1));
        listPage.navigate();
        listPage.clickEdit(claimId);
        listPage.assertEditFormVisible(claimId);
    }

    @Test
    void inlineForm_isPrefilledWithCurrentValues() {
        String claimId = dataHelper.insertOpenClaim(POLICY_ID, ORIG_DESC, LocalDate.of(2026, 3, 1));
        listPage.navigate();
        listPage.clickEdit(claimId);
        assertThat(page.locator("#claim-" + claimId + " input[name=description]"))
                .hasValue(ORIG_DESC);
        assertThat(page.locator("#claim-" + claimId + " input[name=claimDate]"))
                .hasValue("2026-03-01");
    }

    @Test
    void saveEdit_updatesDescriptionInRow() {
        String claimId = dataHelper.insertOpenClaim(POLICY_ID, ORIG_DESC, LocalDate.of(2026, 3, 1));
        String newDesc = "Hagelschaden aktualisiert";
        listPage.navigate();
        listPage.clickEdit(claimId);
        listPage.fillInlineDescription(claimId, newDesc);
        listPage.clickInlineSave(claimId);
        listPage.assertEditFormHidden(claimId);
        assertThat(page.locator("#claim-" + claimId + " td:nth-child(3)")).containsText(newDesc);
    }

    @Test
    void cancelEdit_restoresOriginalRow() {
        String claimId = dataHelper.insertOpenClaim(POLICY_ID, ORIG_DESC, LocalDate.of(2026, 3, 1));
        listPage.navigate();
        listPage.clickEdit(claimId);
        listPage.fillInlineDescription(claimId, "Temporaere Aenderung");
        listPage.clickInlineCancel(claimId);
        listPage.assertEditFormHidden(claimId);
        assertThat(page.locator("#claim-" + claimId + " td:nth-child(3)")).containsText(ORIG_DESC);
    }
}
