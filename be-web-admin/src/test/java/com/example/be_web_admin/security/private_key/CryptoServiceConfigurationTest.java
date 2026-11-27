package com.example.be_web_admin.security.private_key;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoServiceConfigurationTest {

    @Test
    void reportsExampleTotpKeyPlaceholderAndPreservesExistingDataWarning() {
        CryptoProperties properties = new CryptoProperties();
        properties.setActiveVersion(2);
        properties.setKeys(Map.of(2, "change-me-base64-32-random-bytes"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new CryptoService(properties));

        assertTrue(error.getMessage().contains("TOTP_MASTER_KEY_V2"));
        assertTrue(error.getMessage().contains("khôi phục khóa gốc"));
    }
}
