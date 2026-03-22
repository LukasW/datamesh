package ch.yuno.claims.infrastructure.web.playwright.pages;
import com.microsoft.playwright.Page;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
/**
 * Page Object for the new-claim form ({@code /claims/neu}).
 */
public class ClaimFormPage {
    private final Page page;
    public ClaimFormPage(Page page) {
        this.page = page;
    }
    // ── Navigation ────────────────────────────────────────────────────────────
    public void navigate() {
        page.navigate("/claims/neu");
    }
    // ── Assertions ────────────────────────────────────────────────────────────
    public void assertLoaded() {
        assertThat(page.locator(".card-header h5")).containsText("Schadenmeldung erfassen");
    }
    public void assertErrorMessageContains(String text) {
        assertThat(page.locator(".alert-danger")).containsText(text);
    }
    public void assertNoErrorMessage() {
        assertThat(page.locator(".alert-danger")).not().isVisible();
    }
    // ── Actions ───────────────────────────────────────────────────────────────
    public void fillPolicyId(String policyId) {
        page.evaluate("id => document.getElementById('policyId').value = id", policyId);
    }
    public void fillDescription(String description) {
        page.locator("#description").fill(description);
    }
    public void fillClaimDate(String isoDate) {
        page.locator("#claimDate").fill(isoDate);
    }
    public void submit() {
        page.locator("button[type=submit]").click();
    }
    public void clickCancel() {
        page.locator("a:has-text('Abbrechen')").click();
    }
    public void openPartnerModal() {
        page.locator("#no-partner-selected button").click();
    }

    public void searchPartnerByName(String name) {
        page.locator("#modal-tab-name input[name='q']").fill(name);
        // trigger htmx keyup event
        page.locator("#modal-tab-name input[name='q']").press("Space");
        page.waitForSelector("#modal-partner-results table", new Page.WaitForSelectorOptions().setTimeout(5000));
    }

    public void selectFirstPartnerResult() {
        page.locator("#modal-partner-results table tbody tr:first-child button").click();
    }
}
