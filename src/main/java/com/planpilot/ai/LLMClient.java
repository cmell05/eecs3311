package com.planpilot.ai;

public interface LLMClient {
    String generateText(String systemInstruction, String userPrompt);
}
