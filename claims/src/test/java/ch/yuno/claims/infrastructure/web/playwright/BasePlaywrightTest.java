package ch.yuno.claims.infrastructure.web.playwright;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import java.net.URL;
/**
 * Base class for all Playwright UI tests.
 *
 * <p><strong>Important:</strong> concrete subclasses MUST carry {@code @QuarkusTest}
 * and {@code @TestSecurity(authorizationEnabled = false)} themselves. Quarkus requires
 * these annotations on the actual (non-abstract) class so that CDI can register it
 * as a managed bean.
 *
 * <p>Run only Playwright tests: {@code mvn test -Dgroups=playwright}
 * <p>Prerequisites: {@code mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"}
 */
public abstract class BasePlaywrightTest {
    // Injected by Quarkus before each test – gives the actual test-server URL
    @TestHTTPResource("/")
    URL appRootUrl;
    @Inject
    TestDataHelper dataHelper;
    // --- Shared Playwright / Browser (created once per JVM) ---
    static Playwright playwright;
    static Browser browser;
    protected BrowserContext context;
    protected Page page;
    protected String baseUrl;
    @BeforeAll
    static void startBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(true)
        );
    }
    @AfterAll
    static void stopBrowser() {
        if (playwright != null) {
            playwright.close();
        }
    }
    @BeforeEach
    void setUp() {
        // Capture the actual test-server base URL (random port)
        baseUrl = appRootUrl.toString();
        // Fresh browser context per test for full cookie/storage isolation
        context = browser.newContext(
                new Browser.NewContextOptions()
                        .setBaseURL(baseUrl)
        );
        page = context.newPage();
        // Clean database state before every test
        dataHelper.clearAll();
    }
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }
}
