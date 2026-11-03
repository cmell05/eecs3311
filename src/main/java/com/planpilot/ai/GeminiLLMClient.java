package com.planpilot.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planpilot.config.GeminiConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

public final class GeminiLLMClient implements LLMClient {
    public static final String MODEL = "gemini-3.5-flash-lite";
    static final URI ENDPOINT = URI.create(
            "https://generativelanguage.googleapis.com/v1beta/models/"
                    + MODEL + ":generateContent");

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private final String apiKey;
    private final Transport transport;
    private final ObjectMapper objectMapper;

    public GeminiLLMClient(GeminiConfiguration configuration) {
        this(configuration.getApiKey(), defaultTransport(), new ObjectMapper());
    }

    GeminiLLMClient(
            String apiKey, Transport transport, ObjectMapper objectMapper) {
        this.apiKey = requireText(apiKey, "Gemini API key");
        this.transport = Objects.requireNonNull(
                transport, "Transport cannot be null.");
        this.objectMapper = Objects.requireNonNull(
                objectMapper, "Object mapper cannot be null.");
    }

    @Override
    public String generateText(String systemInstruction, String userPrompt) {
        requireText(systemInstruction, "System instruction");
        requireText(userPrompt, "User prompt");
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        createRequestBody(systemInstruction, userPrompt)))
                .build();

        try {
            TransportResponse response = transport.send(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiFailure(response);
            }
            return extractText(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LLMClientException("Gemini request was interrupted.", exception);
        } catch (IOException exception) {
            throw new LLMClientException(
                    "Unable to communicate with the Gemini API.", exception);
        }
    }

    String createRequestBody(
            String systemInstruction, String userPrompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("system_instruction")
                .putArray("parts")
                .addObject()
                .put("text", systemInstruction);
        root.putArray("contents")
                .addObject()
                .put("role", "user")
                .putArray("parts")
                .addObject()
                .put("text", userPrompt);
        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("temperature", 0.2);
        generationConfig.put("maxOutputTokens", 2048);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new LLMClientException(
                    "Unable to create the Gemini request.", exception);
        }
    }

    private String extractText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode parts = root.path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts");
            StringBuilder text = new StringBuilder();
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (!part.path("thought").asBoolean(false)
                            && part.hasNonNull("text")) {
                        text.append(part.get("text").asText());
                    }
                }
            }
            String result = text.toString().trim();
            if (result.isEmpty()) {
                String blockReason = root.path("promptFeedback")
                        .path("blockReason")
                        .asText("");
                String suffix = blockReason.isBlank()
                        ? ""
                        : " Block reason: " + blockReason + ".";
                throw new LLMClientException(
                        "Gemini returned no text response." + suffix);
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new LLMClientException(
                    "Gemini returned invalid JSON.", exception);
        }
    }

    private LLMClientException apiFailure(TransportResponse response) {
        String message = "Gemini request failed with HTTP " + response.statusCode() + ".";
        try {
            String apiMessage = objectMapper.readTree(response.body())
                    .path("error")
                    .path("message")
                    .asText("");
            if (!apiMessage.isBlank()) {
                message += " " + apiMessage;
            }
        } catch (JsonProcessingException ignored) {
            // The status code remains useful even when an error body is malformed.
        }
        return new LLMClientException(message);
    }

    private static Transport defaultTransport() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return request -> {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            return new TransportResponse(response.statusCode(), response.body());
        };
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
        return value.trim();
    }

    @FunctionalInterface
    interface Transport {
        TransportResponse send(HttpRequest request)
                throws IOException, InterruptedException;
    }

    record TransportResponse(int statusCode, String body) {
        TransportResponse {
            Objects.requireNonNull(body, "Response body cannot be null.");
        }
    }
}
