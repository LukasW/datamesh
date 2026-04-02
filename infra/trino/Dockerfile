FROM trinodb/trino

# Vault Transit Decrypt UDF Plugin (ADR-009: crypto-shredding)
# Provides vault_decrypt(person_id, ciphertext) scalar function.
COPY vault-udf/target/trino-vault-decrypt-1.0.0-SNAPSHOT/ /usr/lib/trino/plugin/vault/
