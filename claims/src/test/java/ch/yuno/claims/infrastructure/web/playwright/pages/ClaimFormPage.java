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
        assertThat(page.locator("h5")).containsText("Schadenmeldung erfassen");
    }
    public void assertErrorMessageContains(String text) {
        assertThat(page.locator(".alert-danger")).containsText(text);
    }
    public void assertNoErrorMessage() {
        assertThat(page.locator(".alert-danger")).not().isVisible();
    }
    // ── Actions ───────────────────────────────────────────────────────────────
    public void fillPolicyId(String policyId) {
        // Open the "Direkte Police-ID Eingabe" accordion and fill the direct input
        page.locator("summary:has-text('Direkte Police-ID Eingabe')").click();
        page.locator("details input[type=text]").fill(policyId);
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
}
