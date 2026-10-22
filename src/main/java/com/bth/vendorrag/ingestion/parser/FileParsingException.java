package com.bth.vendorrag.ingestion.parser;

public class FileParsingException extends RuntimeException {
	
	private static final long serialVersionUID = 6942130652050540280L;

	public FileParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}