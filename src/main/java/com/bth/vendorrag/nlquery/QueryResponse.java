package com.bth.vendorrag.nlquery;

import java.util.List;
import java.util.Map;

public record QueryResponse(
        String question,
        String answer,
        String cypher,
        Map<String, Object> parameters,
        String explanation,
        List<Map<String, Object>> results
) {}