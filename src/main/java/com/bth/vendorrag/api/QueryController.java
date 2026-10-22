package com.bth.vendorrag.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bth.vendorrag.ingestion.QueryService;
import com.bth.vendorrag.nlquery.CypherValidationException;
import com.bth.vendorrag.nlquery.LlmUnavailableException;
import com.bth.vendorrag.nlquery.NaturalLanguageQueryService;
import com.bth.vendorrag.nlquery.QueryResponse;

@RestController
@RequestMapping("/api/chat")
public class QueryController {

    private final QueryService neo4jQueryService;
    private final NaturalLanguageQueryService naturalLanguageQueryService;

    public QueryController(QueryService neo4jQueryService,
                            NaturalLanguageQueryService naturalLanguageQueryService) {
        this.neo4jQueryService = neo4jQueryService;
        this.naturalLanguageQueryService = naturalLanguageQueryService;
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody AskRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question must not be empty"));
        }
        try {
            QueryResponse response = naturalLanguageQueryService.execute(request.question());
            return ResponseEntity.ok(response);
        } catch (LlmUnavailableException e) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "AI query service is temporarily unavailable. "
                            + "Try rephrasing, or use POST /api/chat/query with raw Cypher.",
                    "detail", e.getMessage()
            ));
        } catch (CypherValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Query execution failed: " + e.getMessage()));
        }
    }

    /**
     * KEPT: raw Cypher passthrough — useful for debugging/admin tools,
     * but no longer the path the chat UI should call directly.
     */
    @PostMapping("/query")
    public ResponseEntity<?> runQuery(@RequestBody QueryRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query must not be empty"));
        }
        try {
            Map<String, Object> params = request.params() != null ? request.params() : Map.of();
            var results = neo4jQueryService.retrieveData(request.query(), params);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Query execution failed: " + e.getMessage()));
        }
    }

    public record AskRequest(String question) {}
    public record QueryRequest(String query, Map<String, Object> params) {}
}