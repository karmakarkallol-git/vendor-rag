package com.bth.vendorrag.nlquery;

import java.util.Map;

public record CypherRequest(String cypher, Map<String, Object> parameters, String explanation) {}