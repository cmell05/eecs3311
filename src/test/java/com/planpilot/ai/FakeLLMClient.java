package com.planpilot.ai;

final class FakeLLMClient implements LLMClient {
    private final String response;
    private String systemInstruction;
    private String userPrompt;

    FakeLLMClient(String response) {
        this.response = response;
    }

    @Override
    public String generateText(String systemInstruction, String userPrompt) {
        this.systemInstruction = systemInstruction;
        this.userPrompt = userPrompt;
        return response;
    }

    String getSystemInstruction() {
        return systemInstruction;
    }

    String getUserPrompt() {
        return userPrompt;
    }
}
