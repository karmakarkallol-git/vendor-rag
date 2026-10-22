package com.bth.vendorrag.nlquery;

public class CypherValidationException extends RuntimeException {
	
    private static final long serialVersionUID = 4246572107868783799L;

	public CypherValidationException(String message) {
        super(message);
    }
}