package com.planpilot.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeminiConfigurationTest {
    @Test
    void processEnvironmentTakesPrecedenceOverDotenv() {
        GeminiConfiguration configuration = GeminiConfiguration.fromValues(
                "environment-key", "dotenv-key");

        assertEquals("environment-key", configuration.getApiKey());
    }

    @Test
    void usesDotenvValueWhenProcessEnvironmentIsAbsent() {
        GeminiConfiguration configuration = GeminiConfiguration.fromValues(
                null, "dotenv-key");

        assertEquals("dotenv-key", configuration.getApiKey());
    }

    @Test
    void rejectsMissingAndPlaceholderKeys() {
        assertThrows(IllegalStateException.class,
                () -> GeminiConfiguration.fromValues(null, null));
        assertThrows(IllegalStateException.class,
                () -> GeminiConfiguration.fromValues(
                        null, "YOUR_LOCAL_GEMINI_API_KEY"));
    }

    @Test
    void stringRepresentationNeverExposesKey() {
        GeminiConfiguration configuration = GeminiConfiguration.fromValues(
                "secret-value", null);

        assertFalse(configuration.toString().contains("secret-value"));
    }
}
