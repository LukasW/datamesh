package ch.yuno.claims.infrastructure.web.playwright.pages;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
/**
 * Page Object for the claims list view ({@code /claims}).
 *
 * <p>Encapsulates all selectors and interactions so that test classes stay
 * readable and are isolated from HTML changes.
 */
public class ClaimListPage {
    private final Page page;
    public ClaimListPage(Page page) {
        this.page = page;
    }
    // ── Navigation ────────────────────────────────────────────────────────────
    public void navigate() {
        page.navigate("/claims");
    }
    // ── Assertions ────────────────────────────────────────────────────────────
    public void assertLoaded() {
        assertThat(page.locator("h2")).containsText("Schadenfälle");
    }
    public void assertNewClaimButtonVisible() {
        assertThat(newClaimButton()).isVisible();
    }
    public void assertEmptyStateVisible() {
        assertThat(page.locator("td:has-text('Keine Schadenfälle gefunden.')")).isVisible();
    }
    public void assertClaimVisible(String claimNumber) {
        assertThat(rowByClaimNumber(claimNumber)).isVisible();
    }
    public void assertEditFormVisible(String claimId) {
        assertThat(page.locator("#claim-" + claimId + " form")).isVisible();
    }
    public void assertEditFormHidden(String claimId) {
        assertThat(page.locator("#claim-" + claimId + " form")).not().isVisible();
    }
    // ── Actions ───────────────────────────────────────────────────────────────
    public void clickNewClaim() {
        newClaimButton().click();
    }
    public void clickEdit(String claimId) {
        page.locator("#claim-" + claimId).getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Ändern")).click();
    }
    public void fillInlineDescription(String claimId, String description) {
        page.locator("#claim-" + claimId + " input[name=description]").fill(description);
    }
    public void fillInlineClaimDate(String claimId, String isoDate) {
        page.locator("#claim-" + claimId + " input[name=claimDate]").fill(isoDate);
    }
    public void clickInlineSave(String claimId) {
        page.locator("#claim-" + claimId).getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Speichern")).click();
    }
    public void clickInlineCancel(String claimId) {
        page.locator("#claim-" + claimId).getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Abbrechen")).click();
    }
    // ── Queries ───────────────────────────────────────────────────────────────
    public int claimRowCount() {
        return page.locator("tbody tr[id^='claim-']").count();
    }
    public String descriptionForClaim(String claimId) {
        return page.locator("#claim-" + claimId + " td:nth-child(3)").textContent();
    }
    // ── Internals ─────────────────────────────────────────────────────────────
    private Locator newClaimButton() {
        // :has-text() matches any element whose text content contains the string,
        // which works reliably even when the link also contains an SVG icon.
        return page.locator("a:has-text('Neue Schadenmeldung')");
    }
    private Locator rowByClaimNumber(String claimNumber) {
        return page.locator("tbody tr").filter(
                new Locator.FilterOptions().setHasText(claimNumber));
    }
}
