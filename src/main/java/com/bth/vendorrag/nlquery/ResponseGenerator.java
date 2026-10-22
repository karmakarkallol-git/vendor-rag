package com.bth.vendorrag.nlquery;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class ResponseGenerator {

    private final ChatClient chatClient;

    // Simple result shapes get a templated answer (no LLM call).
    // Anything beyond this gets summarized by the LLM.
    private static final int SIMPLE_ROW_LIMIT = 10;
    private static final int SIMPLE_COLUMN_LIMIT = 2;

    private static final String SYSTEM_PROMPT = """
            You turn Neo4j query results into a short, natural-language answer
            for a travel-booking assistant (BookThisHoliday).

            Rules:
            - Answer only using the data provided below. Never invent facts.
            - If the result list is empty, say plainly that nothing matched —
              don't apologize excessively or speculate why.
            - Be concise: a sentence or two, or a short list if there are
              multiple results worth naming individually (cap at ~10 items,
              mention the total count if there are more).
            - Don't mention Cypher, Neo4j, or the query itself — answer as if
              you just know the answer.
            """;

    public ResponseGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String generate(String question, List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return "I couldn't find anything matching that.";
        }

        if (isSimpleShape(results)) {
            return templateAnswer(results);
        }

        return llmAnswer(question, results);
    }

    /**
     * "Simple" = few rows, few columns — the kind of result a template can
     * render without losing information or needing interpretation (counts,
     * comparisons, aggregations, mixed/nested values don't qualify).
     */
    private boolean isSimpleShape(List<Map<String, Object>> results) {
        if (results.size() > SIMPLE_ROW_LIMIT) {
            return false;
        }
        return results.stream().allMatch(row ->
                row.size() <= SIMPLE_COLUMN_LIMIT &&
                row.values().stream().noneMatch(v -> v instanceof Map || v instanceof List)
        );
    }

    private String templateAnswer(List<Map<String, Object>> results) {
        // Single column: "Found 3: X, Y, Z"
        // Two columns: "Found 3: X (Y), A (B), C (D)"
        List<String> items = results.stream()
                .map(this::formatRow)
                .collect(Collectors.toList());

        if (items.size() == 1) {
            return "Found 1 match: " + items.get(0) + ".";
        }
        return "Found " + items.size() + " matches: " + String.join(", ", items) + ".";
    }

    private String formatRow(Map<String, Object> row) {
        List<Object> values = List.copyOf(row.values());
        if (values.size() == 1) {
            return String.valueOf(values.get(0));
        }
        return values.get(0) + " (" + values.get(1) + ")";
    }

    private String llmAnswer(String question, List<Map<String, Object>> results) {
        String resultBlock = formatResultsForPrompt(results);

        try {
            return chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("""
                            Question: %s

                            Results (%d rows):
                            %s
                            """.formatted(question, results.size(), resultBlock))
                    .call()
                    .content();
        } catch (Exception e) {
            // Degrade to the same templated style used for "simple" shapes,
            // even though this result set didn't qualify as simple —
            // a plain row dump beats a 500 error.
            return "Found " + results.size() + " result(s). (Answer summarization is "
                    + "temporarily unavailable — showing raw data below.)";
        }
    }
    

    private String formatResultsForPrompt(List<Map<String, Object>> results) {
        int cap = 50;
        StringBuilder sb = new StringBuilder();
        results.stream().limit(cap).forEach(row -> sb.append(row).append("\n"));
        if (results.size() > cap) {
            sb.append("... and ").append(results.size() - cap).append(" more rows\n");
        }
        return sb.toString();
    }
    
    
}