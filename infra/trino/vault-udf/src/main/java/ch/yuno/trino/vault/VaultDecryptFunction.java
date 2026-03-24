package ch.yuno.trino.vault;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Trino scalar function that decrypts Vault Transit ciphertext.
 * Used in SQLMesh staging models to decrypt PII from partner events (ADR-009).
 *
 * <p>Key naming convention: partner-{personId} (same as Partner service).
 *
 * <p>Graceful degradation: returns NULL if decryption fails (e.g. key deleted = crypto-shredded).
 * This ensures Superset dashboards continue to work — shredded partners simply show NULL names.
 *
 * <p>Configuration via environment variables:
 * <ul>
 *   <li>VAULT_ADDR — Vault server URL (default: http://vault:8200)</li>
 *   <li>VAULT_TOKEN — Vault authentication token</li>
 * </ul>
 */
public final class VaultDecryptFunction {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final String VAULT_ADDR = System.getenv().getOrDefault(
            "VAULT_ADDR", "http://vault:8200");

    private static final String VAULT_TOKEN = System.getenv().getOrDefault(
            "VAULT_TOKEN", "dev-root-token");

    private static final String VAULT_PREFIX = "vault:";

    private VaultDecryptFunction() {}

    /**
     * Decrypts a Vault Transit ciphertext for a given person.
     *
     * @param personId  the person identifier (used to derive key name: partner-{personId})
     * @param ciphertext the encrypted value (prefixed with "vault:v1:")
     * @return decrypted plaintext, or NULL if not encrypted / decryption fails
     */
    @ScalarFunction("vault_decrypt")
    @Description("Decrypts a Vault Transit ciphertext using the person's encryption key (ADR-009)")
    @SqlNullable
    @SqlType(StandardTypes.VARCHAR)
    public static Slice vaultDecrypt(
            @SqlType(StandardTypes.VARCHAR) Slice personId,
            @SqlType(StandardTypes.VARCHAR) Slice ciphertext) {

        String encrypted = ciphertext.toStringUtf8();

        // Pass through non-encrypted values
        if (!encrypted.startsWith(VAULT_PREFIX)) {
            return ciphertext;
        }

        String keyName = "partner-" + personId.toStringUtf8();

        try {
            String requestBody = "{\"ciphertext\":\"" + encrypted + "\"}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VAULT_ADDR + "/v1/transit/decrypt/" + keyName))
                    .header("X-Vault-Token", VAULT_TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // Key deleted (crypto-shredded) or Vault unreachable → NULL
                return null;
            }

            String plaintext = extractJsonValue(response.body(), "plaintext");
            if (plaintext == null) {
                return null;
            }

            byte[] decoded = Base64.getDecoder().decode(plaintext);
            return Slices.utf8Slice(new String(decoded, StandardCharsets.UTF_8));

        } catch (Exception e) {
            // Graceful degradation: return NULL on any failure
            return null;
        }
    }

    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start == -1) {
            return null;
        }
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end == -1) {
            return null;
        }
        return json.substring(start, end);
    }
}
