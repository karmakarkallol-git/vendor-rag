package com.bth.vendorrag.nlquery;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
public class LlmCypherService {

    private final ChatClient chatClient;
    private final RuleBasedCypherFallback fallback;

    private static final String SYSTEM_PROMPT = """
            You translate natural-language travel-data questions into Cypher for Neo4j.

            Rules:
            - Use ONLY labels, properties, and relationships given in the schema below.
            - Do not invent properties or relationships.
            - Generate ONLY read-only Cypher: MATCH, OPTIONAL MATCH, WHERE, WITH,
              RETURN, ORDER BY, LIMIT, SKIP, UNWIND.
            - Never use CREATE, DELETE, DETACH DELETE, SET, REMOVE, MERGE, DROP, CALL, LOAD CSV.
            - Use query parameters (e.g. $destination) instead of inlining literal values.
            - Add LIMIT 100 unless the user asks for a different limit.

            Schema:
            %s
            """;

    public LlmCypherService(ChatClient.Builder chatClientBuilder, RuleBasedCypherFallback fallback) {
        this.chatClient = chatClientBuilder.build();
        this.fallback = fallback;
    }

    /**
     * Up to 2 retries (3 attempts total) with exponential backoff — covers
     * transient blips (rate limits, brief network errors) without stalling
     * the request for too long. If all attempts fail, recover() below runs.
     */
    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2)
    )
    public CypherRequest generateCypher(String question, String schema) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT.formatted(schema))
                .user(question)
                .call()
                .entity(CypherRequest.class);
    }

    @Recover
    public CypherRequest recover(Exception e, String question, String schema) {
        Optional<CypherRequest> matched = fallback.tryMatch(question);
        if (matched.isPresent()) {
            return matched.get();
        }
        throw new LlmUnavailableException(
                "LLM is currently unavailable and no fallback rule matched this question.", e);
    }
}