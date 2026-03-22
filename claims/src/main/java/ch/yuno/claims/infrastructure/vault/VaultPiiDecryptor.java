package ch.yuno.claims.infrastructure.vault;

import ch.yuno.claims.domain.port.out.PiiDecryptor;
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
 * Vault Transit engine adapter for decrypting PII fields from partner events.
 * Uses the same key naming convention as the Partner service: partner-{personId}.
 */
@ApplicationScoped
public class VaultPiiDecryptor implements PiiDecryptor {

    private static final Pattern PLAINTEXT_PATTERN = Pattern.compile("\"plaintext\"\\s*:\\s*\"([^\"]+)\"");
    private static final String VAULT_PREFIX = "vault:";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "vault.url", defaultValue = "http://vault:8200")
    String vaultUrl;

    @ConfigProperty(name = "vault.token", defaultValue = "dev-root-token")
    String vaultToken;

    @Override
    public String decrypt(String personId, String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty() || !ciphertext.startsWith(VAULT_PREFIX)) {
            return ciphertext;
        }
        String keyName = "partner-" + personId;
        String body = "{\"ciphertext\":\"" + ciphertext + "\"}";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(vaultUrl + "/v1/transit/decrypt/" + keyName))
                    .header("X-Vault-Token", vaultToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Matcher m = PLAINTEXT_PATTERN.matcher(response.body());
            if (m.find()) {
                return new String(Base64.getDecoder().decode(m.group(1)));
            }
            throw new RuntimeException("Vault decrypt failed for key " + keyName + ": " + response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Vault request failed for key " + keyName, e);
        }
    }
}
