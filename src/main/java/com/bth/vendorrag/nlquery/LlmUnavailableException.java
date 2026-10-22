package com.bth.vendorrag.nlquery;

public class LlmUnavailableException extends RuntimeException {
    private static final long serialVersionUID = -3001434357207724631L;

	public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}