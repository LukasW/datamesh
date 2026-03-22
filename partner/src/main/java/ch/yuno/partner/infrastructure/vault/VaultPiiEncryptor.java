package ch.yuno.partner.infrastructure.vault;

import ch.yuno.partner.domain.port.out.PiiEncryptor;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vault Transit engine adapter for per-entity AES-256 encryption (ADR-009).
 * Each person gets a named key: partner/{personId}.
 * Keys are auto-created on first encrypt (convergent_encryption disabled, aes256-gcm96).
 * <p>
 * Crypto-shredding: calling {@link #deleteKey(String)} permanently destroys the key,
 * rendering all ciphertext for that person unreadable in Kafka events and Parquet files.
 */
@ApplicationScoped
public class VaultPiiEncryptor implements PiiEncryptor {

    private static final Pattern CIPHERTEXT_PATTERN = Pattern.compile("\"ciphertext\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PLAINTEXT_PATTERN = Pattern.compile("\"plaintext\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "vault.url", defaultValue = "http://vault:8200")
    String vaultUrl;

    @ConfigProperty(name = "vault.token", defaultValue = "dev-root-token")
    String vaultToken;

    @Override
    public String encrypt(String personId, String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        String keyName = keyName(personId);
        ensureKey(keyName);
        String base64Plaintext = Base64.getEncoder().encodeToString(plaintext.getBytes());
        String body = "{\"plaintext\":\"" + base64Plaintext + "\"}";
        String response = vaultPost("/v1/transit/encrypt/" + keyName, body);
        Matcher m = CIPHERTEXT_PATTERN.matcher(response);
        if (m.find()) {
            return m.group(1);
        }
        throw new RuntimeException("Vault encrypt failed for key " + keyName + ": " + response);
    }

    @Override
    public String decrypt(String personId, String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty() || !ciphertext.startsWith("vault:")) {
            return ciphertext;
        }
        String keyName = keyName(personId);
        String body = "{\"ciphertext\":\"" + ciphertext + "\"}";
        String response = vaultPost("/v1/transit/decrypt/" + keyName, body);
        Matcher m = PLAINTEXT_PATTERN.matcher(response);
        if (m.find()) {
            return new String(Base64.getDecoder().decode(m.group(1)));
        }
        throw new RuntimeException("Vault decrypt failed for key " + keyName + ": " + response);
    }

    @Override
    public void deleteKey(String personId) {
        String keyName = keyName(personId);
        // First allow deletion (keys are non-deletable by default)
        vaultPost("/v1/transit/keys/" + keyName + "/config", "{\"deletion_allowed\":true}");
        // Then delete the key — crypto-shredding complete
        vaultDelete("/v1/transit/keys/" + keyName);
    }

    private void ensureKey(String keyName) {
        String body = "{\"type\":\"aes256-gcm96\"}";
        vaultPost("/v1/transit/keys/" + keyName, body);
    }

    private String keyName(String personId) {
        return "partner-" + personId;
    }

    private String vaultPost(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(vaultUrl + path))
                    .header("X-Vault-Token", vaultToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("Vault request failed: " + path, e);
        }
    }

    private void vaultDelete(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(vaultUrl + path))
                    .header("X-Vault-Token", vaultToken)
                    .DELETE()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("Vault delete failed: " + path, e);
        }
    }
}
