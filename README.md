# travel-rag

All-Java Spring Boot app: PDF/email ingestion + Neo4j graph/vector store + AI-driven itinerary queries.

## Dependency list (what each one is for)

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | REST API layer (query endpoint, ingestion trigger endpoint) |
| `spring-boot-starter-validation` | Request validation |
| `tika-core` / `tika-parsers-standard-package` | Parse PDF, Word, and other document formats |
| `jakarta.mail` | IMAP email ingestion |
| `spring-ai-starter-model-anthropic` | LLM calls: entity extraction, query understanding, response generation, with structured output mapped to Java records |
| `spring-ai-starter-vector-store-neo4j` | Embedding storage/search against Neo4j's native vector index |
| `neo4j-java-driver` | Direct Cypher for graph writes, `MERGE`-based dedup, filtering + scoring queries |
| `spring-boot-starter-amqp` | RabbitMQ client for the async ingestion queue |
| `lombok` | Boilerplate reduction (optional, remove if you'd rather not use it) |
| `spring-boot-starter-test`, `spring-rabbit-test` | Testing |

Java 21 is set as the target — this project relies on virtual threads for ingestion concurrency.

## Package layout

```
com.bth.vendorrag
├── ingestion/
│   ├── parser/       Tika-based file parsing
│   ├── extraction/   Spring AI structured-output entity extraction
│   └── resolution/   Entity resolution/matching against existing BTH records
├── graph/            Neo4j writes (MERGE) and Cypher filter/scoring queries
├── query/            Query understanding + itinerary assembly
├── api/              REST controllers
└── config/           Virtual thread executor, RabbitMQ, Neo4j config
```


## Before running

Set these environment variables (or override in `application.yml`):

- `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`
- `ANTHROPIC_API_KEY`
- `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` (or run RabbitMQ locally via Docker)

## Setup
1. install docker 
2. run docker image in local : docker run -d --name neo4j-local -p 7474:7474 -p 7687:7687 neo4j:5


## Next steps

1. Define the Neo4j schema (node labels: `Hotel`, `HolidayPackage`, `CarRental`, `Destination`, `Agent`) and create constraints/indexes for the `MERGE` keys.
2. Implement `FileParserService` (Tika wrapper) and one extraction record type (e.g. `PackageExtraction`) end-to-end for a single PDF type as a proof of concept.
3. Wire the ingestion queue consumer with a virtual-thread executor.
4. Build the BTH sync/ETL job to mirror existing structured data into Neo4j.
