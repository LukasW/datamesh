package ch.yuno.claims.infrastructure.web.playwright;

import ch.yuno.claims.infrastructure.web.playwright.pages.ClaimListPage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

@QuarkusTest
@TestSecurity(authorizationEnabled = false)
@Tag("playwright")
class ClaimListUiTest extends BasePlaywrightTest {

    private static final String POLICY_ID = "aaaaaaaa-0001-0001-0001-000000000001";

    ClaimListPage listPage;

    @BeforeEach
    void initPage() {
        listPage = new ClaimListPage(page);
    }

    @Test
    void pageLoads_showsHeadingAndNewButton() {
        listPage.navigate();

        listPage.assertLoaded();
        listPage.assertNewClaimButtonVisible();
    }

    @Test
    void emptyDatabase_showsEmptyStateRow() {
        listPage.navigate();

        listPage.assertEmptyStateVisible();
    }

    @Test
    void existingOpenClaim_appearsInTable() {
        dataHelper.insertPolicySnapshot(POLICY_ID);
        String claimId = dataHelper.insertOpenClaim(
                POLICY_ID, "Wasserschaden Keller", LocalDate.of(2026, 3, 10));

        listPage.navigate();

        assertThat(page.locator("tbody tr[id=\"claim-" + claimId + "\"]")).isVisible();
        assertThat(page.locator("tbody tr[id=\"claim-" + claimId + "\"] td:nth-child(3)"))
                .containsText("Wasserschaden Keller");
    }

    @Test
    void openClaim_showsAllThreeActionButtons() {
        dataHelper.insertPolicySnapshot(POLICY_ID);
        String claimId = dataHelper.insertOpenClaim(
                POLICY_ID, "Brandschaden", LocalDate.of(2026, 3, 15));

        listPage.navigate();

        assertThat(page.locator("#claim-" + claimId + " button:has-text('In Bearbeitung')")).isVisible();
        assertThat(page.locator("#claim-" + claimId + " button:has-text('Ablehnen')")).isVisible();
        // "Aendern" button triggers inline edit
        assertThat(page.locator("#claim-" + claimId).getByText("ndern")).isVisible();
    }

    @Test
    void newClaimButton_navigatesToForm() {
        listPage.navigate();
        listPage.clickNewClaim();

        assertThat(page).hasURL(Pattern.compile(".*/claims/neu$"));
    }
}





