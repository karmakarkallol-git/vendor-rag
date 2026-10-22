package com.bth.vendorrag.nlquery;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class CypherValidator {

    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "CREATE", "DELETE", "DETACH DELETE", "SET", "REMOVE",
            "MERGE", "DROP", "CALL", "LOAD CSV"
    );

    public void validate(String cypher) {
        if (cypher == null || cypher.isBlank()) {
            throw new CypherValidationException("Generated Cypher was empty");
        }
        String upper = cypher.toUpperCase(Locale.ROOT);
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (upper.contains(keyword)) {
                throw new CypherValidationException(
                        "Generated Cypher contains a disallowed write operation: " + keyword);
            }
        }
        // TODO: swap for a proper Cypher AST parse (e.g. via openCypher grammar or
        // Neo4j's cypher-dsl) once query complexity grows — string matching is a
        // reasonable first gate but not airtight (e.g. keyword inside a string literal).
    }
}