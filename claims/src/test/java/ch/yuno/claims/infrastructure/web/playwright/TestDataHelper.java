package ch.yuno.claims.infrastructure.web.playwright;
import ch.yuno.claims.domain.model.Claim;
import ch.yuno.claims.domain.model.PolicySnapshot;
import ch.yuno.claims.infrastructure.persistence.ClaimJpaAdapter;
import ch.yuno.claims.infrastructure.persistence.PolicySnapshotJpaAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
/**
 * CDI helper bean for setting up and tearing down test data within
 * {@code @QuarkusTest}. All methods run in their own transaction so that data
 * is visible to the application server handling Playwright HTTP requests.
 */
@ApplicationScoped
public class TestDataHelper {
    @Inject
    EntityManager em;
    @Inject
    ClaimJpaAdapter claimJpaAdapter;
    @Inject
    PolicySnapshotJpaAdapter policySnapshotJpaAdapter;
    /** Remove all claims, snapshots and outbox events between tests. */
    @Transactional
    public void clearAll() {
        em.createNativeQuery("DELETE FROM outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM claim_aud").executeUpdate();
        em.createNativeQuery("DELETE FROM claim").executeUpdate();
        em.createNativeQuery("DELETE FROM policy_snapshot").executeUpdate();
    }
    /**
     * Insert a minimal PolicySnapshot so that FNOL creation can succeed.
     *
     * @param policyId the policy UUID to register
     * @return the same policyId for chaining
     */
    @Transactional
    public String insertPolicySnapshot(String policyId) {
        PolicySnapshot snapshot = new PolicySnapshot(
                policyId,
                "POL-TEST-" + policyId.substring(0, 4).toUpperCase(),
                "partner-test",
                "product-test",
                LocalDate.now().minusMonths(3),
                new BigDecimal("1200.00")
        );
        policySnapshotJpaAdapter.upsert(snapshot);
        return policyId;
    }
    /**
     * Insert an OPEN claim directly (bypasses the application service so that
     * no outbox event is required).
     *
     * @return the claimId of the inserted claim
     */
    @Transactional
    public String insertOpenClaim(String policyId, String description, LocalDate claimDate) {
        Claim claim = Claim.openNew(policyId, description, claimDate);
        claimJpaAdapter.save(claim);
        return claim.getClaimId().value();
    }
}
