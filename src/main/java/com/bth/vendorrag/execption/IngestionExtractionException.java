package com.bth.vendorrag.execption;

public class IngestionExtractionException extends RuntimeException {

    private static final long serialVersionUID = 1018076094057180486L;

	public IngestionExtractionException(String message, Throwable cause) {
        super(message, cause);
    }

    public IngestionExtractionException(String message) {
        super(message);
    }
}