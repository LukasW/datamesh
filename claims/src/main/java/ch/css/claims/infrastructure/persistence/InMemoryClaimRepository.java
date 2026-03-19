package ch.css.claims.infrastructure.persistence;

import ch.css.claims.domain.model.Claim;
import ch.css.claims.domain.port.out.ClaimRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of ClaimRepository for the stub service.
 * Will be replaced by a JPA-based implementation when persistence is added.
 */
@ApplicationScoped
public class InMemoryClaimRepository implements ClaimRepository {

    private final Map<String, Claim> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Claim> findById(String claimId) {
        return Optional.ofNullable(store.get(claimId));
    }

    @Override
    public Claim save(Claim claim) {
        store.put(claim.getClaimId(), claim);
        return claim;
    }

    @Override
    public List<Claim> findByPolicyId(String policyId) {
        return store.values().stream()
                .filter(c -> c.getPolicyId().equals(policyId))
                .toList();
    }
}
