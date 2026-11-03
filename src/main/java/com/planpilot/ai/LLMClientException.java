package com.planpilot.ai;

public class LLMClientException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LLMClientException(String message) {
        super(message);
    }

    public LLMClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
