package com.bth.vendorrag.graph;

import com.bth.vendorrag.ingestion.extraction.PackageExtraction;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class Neo4jWriterRepository {

    private static final String WRITE_PACKAGE_CYPHER = """
            MERGE (d:Destination {name: $destination})
            MERGE (p:HolidayPackage {name: $packageName, destination: $destination})
              ON CREATE SET p.id = $packageId, p.durationDays = $durationDays,
                             p.price = $price, p.createdAt = datetime()
            MERGE (p)-[:LOCATED_IN]->(d)
            MERGE (h:Hotel {name: $hotelName, city: $hotelCity})
              ON CREATE SET h.rating = $hotelRating
            MERGE (p)-[:INCLUDES]->(h)
            MERGE (h)-[:LOCATED_IN]->(d)
            MERGE (sd:SourceDocument {id: $sourceDocumentId})
              ON CREATE SET sd.fileName = $fileName, sd.ingestedAt = datetime()
            MERGE (p)-[:SOURCED_FROM]->(sd)
            RETURN p.id AS packageId
            """;

    private final Driver driver;

    public Neo4jWriterRepository(Driver driver) {
        this.driver = driver;
    }

    public String writePackageAndHotel(PackageExtraction extraction, String sourceDocumentId, String fileName) {
        Map<String, Object> params = new HashMap<>();
        params.put("destination", extraction.destination());
        params.put("packageName", extraction.packageName());
        params.put("packageId", UUID.randomUUID().toString());
        params.put("durationDays", extraction.durationDays());
        params.put("price", extraction.price() != null ? extraction.price().doubleValue() : null);
        params.put("hotelName", extraction.hotel() != null ? extraction.hotel().name() : "Unknown");
        params.put("hotelCity", extraction.hotel() != null ? extraction.hotel().city() : extraction.destination());
        params.put("hotelRating", extraction.hotel() != null ? extraction.hotel().rating() : null);
        params.put("sourceDocumentId", sourceDocumentId);
        params.put("fileName", fileName);

        try (Session session = driver.session()) {
            return session.run(WRITE_PACKAGE_CYPHER, params).single().get("packageId").asString();
        }
    }
}