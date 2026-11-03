package com.planpilot.ai;

import com.planpilot.config.GeminiConfiguration;

/**
 * Opt-in live connectivity check for the configured Gemini client.
 * This class is never invoked by the unit test suite.
 */
public final class GeminiConnectionCheck {
    private static final String EXPECTED_RESPONSE = "PLANPILOT_OK";

    private GeminiConnectionCheck() {
    }

    public static void main(String[] args) {
        LLMClient client = new GeminiLLMClient(GeminiConfiguration.load());
        String response = client.generateText(
                "Follow the user's response-format instruction exactly.",
                "Reply with exactly " + EXPECTED_RESPONSE + " and nothing else.");

        if (!EXPECTED_RESPONSE.equals(response.trim())) {
            throw new IllegalStateException(
                    "Gemini responded, but the verification text did not match. "
                            + "Received: " + response);
        }
        System.out.println("Gemini Java integration verified: " + response);
    }
}
