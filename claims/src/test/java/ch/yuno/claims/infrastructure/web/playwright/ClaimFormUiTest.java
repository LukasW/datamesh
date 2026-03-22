package ch.yuno.claims.infrastructure.web.playwright;

import ch.yuno.claims.infrastructure.web.playwright.pages.ClaimFormPage;
import ch.yuno.claims.infrastructure.web.playwright.pages.ClaimListPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import java.util.regex.Pattern;

/**
 * Playwright UI tests for the FNOL creation form (/claims/neu).
 *
 * Tests covered:
 * - Form page renders all required input fields
 * - Submitting with an unknown policy shows an error and keeps the user on the form
 * - The form retains entered values after a failed submit
 * - Submitting valid data creates the claim and redirects to the list
 * - Cancel button navigates back to the list
 */
@QuarkusTest
@TestSecurity(authorizationEnabled = false)
@Tag("playwright")
class ClaimFormUiTest extends BasePlaywrightTest {

    private static final String KNOWN_POLICY_ID   = "bbbbbbbb-0002-0002-0002-000000000002";
    private static final String UNKNOWN_POLICY_ID = "ffffffff-ffff-ffff-ffff-ffffffffffff";

    ClaimFormPage formPage;
    ClaimListPage listPage;

    @BeforeEach
    void initPages() {
        formPage = new ClaimFormPage(page);
        listPage = new ClaimListPage(page);
    }

    @Test
    void formPage_loadsAllRequiredFields() {
        formPage.navigate();
        formPage.assertLoaded();
        // Partner search modal trigger visible
        assertThat(page.locator("#no-partner-selected button")).isVisible();
        // Hidden policyId field exists
        assertThat(page.locator("#policyId")).isHidden();
        assertThat(page.locator("#claimDate")).isVisible();
        assertThat(page.locator("#description")).isVisible();
        assertThat(page.locator("button[type=submit]")).isVisible();
    }

    @Test
    void submitWithUnknownPolicy_showsErrorAndStaysOnForm() {
        formPage.navigate();
        formPage.fillPolicyId(UNKNOWN_POLICY_ID);
        formPage.fillClaimDate("2026-03-10");
        formPage.fillDescription("Sturmschaden am Dach");
        formPage.submit();
        formPage.assertLoaded();
        formPage.assertErrorMessageContains("Keine aktive Police gefunden");
    }

    @Test
    void formValues_areRetained_afterFailedSubmit() {
        formPage.navigate();
        formPage.fillPolicyId(UNKNOWN_POLICY_ID);
        formPage.fillClaimDate("2026-03-10");
        formPage.fillDescription("Test Vorausfuellung");
        formPage.submit();
        // policyId is a hidden input – check its value attribute
        assertThat(page.locator("#policyId")).hasValue(UNKNOWN_POLICY_ID);
        assertThat(page.locator("#description")).hasValue("Test Vorausfuellung");
    }

    @Test
    void submitWithKnownPolicy_createsClaimAndRedirectsToList() {
        dataHelper.insertPolicySnapshot(KNOWN_POLICY_ID);
        formPage.navigate();
        formPage.fillPolicyId(KNOWN_POLICY_ID);
        formPage.fillClaimDate("2026-03-12");
        formPage.fillDescription("Einbruch Gartenhaus");
        formPage.submit();
        assertThat(page).hasURL(Pattern.compile(".*/claims$"));
        listPage.assertLoaded();
        assertThat(page.locator("td:has-text(\'Einbruch Gartenhaus\')")).isVisible();
    }

    @Test
    void partnerSearch_findsAndSelectsPartner() {
        String partnerId = "aaaaaaaa-1111-1111-1111-aaaaaaaaaaaa";
        String policyId = KNOWN_POLICY_ID;
        dataHelper.insertPartner(partnerId, "Müller", "Hans",
                java.time.LocalDate.of(1985, 3, 15), "756.1234.5678.97", "VN-00000042");
        dataHelper.insertPolicySnapshot(policyId);

        formPage.navigate();
        formPage.openPartnerModal();

        // Wait for modal to be visible
        page.waitForSelector("#partnerModal.show", new com.microsoft.playwright.Page.WaitForSelectorOptions().setTimeout(3000));

        formPage.searchPartnerByName("Müller");

        // Verify search results appear
        assertThat(page.locator("#modal-partner-results table")).isVisible();
        assertThat(page.locator("#modal-partner-results td:has-text('Müller')")).isVisible();

        // Select the partner
        formPage.selectFirstPartnerResult();

        // Verify partner is shown in the form
        assertThat(page.locator("#selected-partner")).isVisible();
        assertThat(page.locator("#selected-partner-name")).containsText("Müller");
    }

    @Test
    void cancelButton_navigatesBackToList() {
        formPage.navigate();
        formPage.clickCancel();
        listPage.assertLoaded();
    }
}
