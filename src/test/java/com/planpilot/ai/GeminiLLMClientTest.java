package com.planpilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiLLMClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsConfiguredModelRequestAndExtractsText() throws Exception {
        AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
        GeminiLLMClient client = new GeminiLLMClient(
                "test-key",
                request -> {
                    capturedRequest.set(request);
                    return new GeminiLLMClient.TransportResponse(
                            200,
                            "{\"candidates\":[{\"content\":{\"parts\":["
                                    + "{\"text\":\"PLANPILOT_\"},"
                                    + "{\"thought\":true,\"text\":\"hidden\"},"
                                    + "{\"text\":\"OK\"}]}}]}");
                },
                objectMapper);

        String response = client.generateText(
                "Be concise.", "Reply with PLANPILOT_OK.");

        assertEquals("PLANPILOT_OK", response);
        assertEquals(GeminiLLMClient.ENDPOINT, capturedRequest.get().uri());
        assertEquals("test-key", capturedRequest.get().headers()
                .firstValue("x-goog-api-key").orElseThrow());
        assertFalse(capturedRequest.get().uri().toString().contains("test-key"));
    }

    @Test
    void createsStructuredJsonWithoutManualEscaping() throws Exception {
        GeminiLLMClient client = new GeminiLLMClient(
                "test-key",
                request -> new GeminiLLMClient.TransportResponse(200, "{}"),
                objectMapper);

        JsonNode body = objectMapper.readTree(client.createRequestBody(
                "System \"instruction\"", "Line one\nLine two"));

        assertEquals("System \"instruction\"", body
                .path("system_instruction").path("parts").path(0).path("text")
                .asText());
        assertEquals("Line one\nLine two", body
                .path("contents").path(0).path("parts").path(0).path("text")
                .asText());
    }

    @Test
    void reportsHttpErrorsWithoutExposingApiKey() {
        LLMClient client = new GeminiLLMClient(
                "secret-test-key",
                request -> new GeminiLLMClient.TransportResponse(
                        429, "{\"error\":{\"message\":\"Rate limit exceeded\"}}"),
                objectMapper);

        LLMClientException exception = assertThrows(
                LLMClientException.class,
                () -> client.generateText("System", "Prompt"));

        assertTrue(exception.getMessage().contains("temporarily unavailable"));
        assertFalse(exception.getMessage().contains("secret-test-key"));
    }

    @Test
    void retriesTransientStatusesWithBoundedBackoff() {
        for (int status : new int[]{429, 500, 502, 503, 504}) {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            java.util.List<Long> waits = new java.util.ArrayList<>();
            GeminiLLMClient client = new GeminiLLMClient("test-key", request -> {
                calls.incrementAndGet();
                return new GeminiLLMClient.TransportResponse(status, "{}");
            }, objectMapper, waits::add);
            assertTrue(assertThrows(LLMClientException.class,
                    () -> client.generateText("system", "prompt")).getMessage().contains("temporarily unavailable"));
            assertEquals(3, calls.get());
            assertEquals(java.util.List.of(500L, 1000L), waits);
        }
    }

    @Test
    void succeedsAfterTransientFailureAndPreservesInterruption() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        GeminiLLMClient client = new GeminiLLMClient("test-key", request ->
                new GeminiLLMClient.TransportResponse(calls.getAndIncrement() == 0 ? 503 : 200,
                        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"),
                objectMapper, millis -> {});
        assertEquals("ok", client.generateText("system", "prompt"));
        assertEquals(2, calls.get());
        GeminiLLMClient interrupted = new GeminiLLMClient("test-key", request ->
                new GeminiLLMClient.TransportResponse(503, "{}"), objectMapper,
                millis -> { throw new InterruptedException(); });
        try {
            assertThrows(LLMClientException.class, () -> interrupted.generateText("system", "prompt"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test
    void doesNotRetryPermanentErrorsOrEchoServerContent() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        GeminiLLMClient client = new GeminiLLMClient("secret-test-key", request -> {
            calls.incrementAndGet();
            return new GeminiLLMClient.TransportResponse(401,
                    "{\"error\":{\"message\":\"secret-test-key sensitive request\"}}");
        }, objectMapper, millis -> { throw new AssertionError("Must not back off for 401"); });
        var failure = assertThrows(LLMClientException.class,
                () -> client.generateText("system", "sensitive request"));
        assertEquals(1, calls.get());
        assertFalse(failure.getMessage().contains("secret-test-key"));
        assertFalse(failure.getMessage().contains("sensitive request"));
    }

    @Test
    void rejectsBlockedOrMalformedResponses() {
        LLMClient blockedClient = new GeminiLLMClient(
                "test-key",
                request -> new GeminiLLMClient.TransportResponse(
                        200,
                        "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"),
                objectMapper);
        LLMClient malformedClient = new GeminiLLMClient(
                "test-key",
                request -> new GeminiLLMClient.TransportResponse(200, "not-json"),
                objectMapper);

        LLMClientException blocked = assertThrows(
                LLMClientException.class,
                () -> blockedClient.generateText("System", "Prompt"));
        assertTrue(blocked.getMessage().contains("SAFETY"));
        assertThrows(LLMClientException.class,
                () -> malformedClient.generateText("System", "Prompt"));
    }

    @Test
    void fakeClientSupportsOfflineConsumerTests() {
        LLMClient client = new FakeLLMClient("fake response");

        String response = client.generateText("system", "prompt");

        assertEquals("fake response", response);
        FakeLLMClient fake = (FakeLLMClient) client;
        assertEquals("system", fake.getSystemInstruction());
        assertEquals("prompt", fake.getUserPrompt());
    }
}
