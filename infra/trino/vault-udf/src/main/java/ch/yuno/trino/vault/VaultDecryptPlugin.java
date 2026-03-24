package ch.yuno.trino.vault;

import io.trino.spi.Plugin;

import java.util.Set;

/**
 * Trino plugin that registers the vault_decrypt() scalar function.
 * Enables PII decryption at query time in the analytical pipeline (ADR-009).
 */
public class VaultDecryptPlugin implements Plugin {

    @Override
    public Set<Class<?>> getFunctions() {
        return Set.of(VaultDecryptFunction.class);
    }
}
