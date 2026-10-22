package com.bth.vendorrag.ingestion;


import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
public class QueryService {

    private final Neo4jClient neo4jClient;

    public QueryService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * Executes a Cypher query supplied by the user and returns the raw result rows.
     *
     * @param cypherQuery the Cypher query string (user-provided)
     * @param params      optional bind parameters (pass Map.of() if none)
     * @return list of result rows, each row as a column-name -> value map
     */
    public List<Map<String, Object>> retrieveData(String cypherQuery, Map<String, Object> params) {
        Collection<Map<String, Object>> results = neo4jClient
                .query(cypherQuery)
                .bindAll(params)
                .fetch()
                .all();

        return List.copyOf(results);
    }
}