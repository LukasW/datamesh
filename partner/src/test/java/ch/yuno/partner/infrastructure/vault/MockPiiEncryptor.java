package ch.yuno.partner.infrastructure.vault;

import ch.yuno.partner.domain.port.out.PiiEncryptor;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Test double for PiiEncryptor – passes plaintext through unchanged.
 * No Vault server needed during tests.
 */
@Mock
@ApplicationScoped
public class MockPiiEncryptor implements PiiEncryptor {

    @Override
    public String encrypt(String personId, String plaintext) {
        return plaintext;
    }

    @Override
    public String decrypt(String personId, String ciphertext) {
        return ciphertext;
    }

    @Override
    public void deleteKey(String personId) {
        // no-op in tests
    }
}
