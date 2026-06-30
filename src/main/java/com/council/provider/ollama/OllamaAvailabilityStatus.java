package com.council.provider.ollama;

public enum OllamaAvailabilityStatus {
    AVAILABLE,
    OLLAMA_NOT_RUNNING,
    MODEL_NOT_INSTALLED,
    DISABLED,
    BAD_RESPONSE_SCHEMA,
    TIMEOUT,
    NETWORK_ERROR,
    UNKNOWN
}
