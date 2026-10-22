package com.bth.vendorrag.nlquery;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bth.vendorrag.ingestion.QueryService;

@Service
public class NaturalLanguageQueryService {

    private final SchemaService schemaService;
    private final LlmCypherService llmCypherService;
    private final CypherValidator cypherValidator;
    private final QueryService neo4jQueryService;
    private final ResponseGenerator responseGenerator;

    public NaturalLanguageQueryService(SchemaService schemaService,
                                        LlmCypherService llmCypherService,
                                        CypherValidator cypherValidator,
                                        QueryService neo4jQueryService,
                                        ResponseGenerator responseGenerator) {
        this.schemaService = schemaService;
        this.llmCypherService = llmCypherService;
        this.cypherValidator = cypherValidator;
        this.neo4jQueryService = neo4jQueryService;
        this.responseGenerator = responseGenerator;
    }

    public QueryResponse execute(String question) {
        String schema = schemaService.getRelevantSchema(question);
        
        CypherRequest cypherRequest = llmCypherService.generateCypher(question, schema); // may be LLM or fallback
        cypherValidator.validate(cypherRequest.cypher()); // runs either way
        
        List<Map<String, Object>> rows = neo4jQueryService.retrieveData(
                cypherRequest.cypher(),
                cypherRequest.parameters()
        );

        String answer = responseGenerator.generate(question, rows);

        return new QueryResponse(
                question,
                answer,
                cypherRequest.cypher(),
                cypherRequest.parameters(),
                cypherRequest.explanation(),
                rows
        );
    }
}