package com.bth.vendorrag.nlquery;

import org.springframework.stereotype.Service;

@Service
public class SchemaService {

    // Static for now. Once your graph grows (Hotel, Room, Package, Supplier,
    // Vehicle, Driver, Review, ...), replace this with schema retrieval:
    // embed each node/relationship block, vector-search against the question,
    // and return only the relevant slice instead of the whole schema.
    private static final String FULL_SCHEMA = """
            Nodes:
            Customer { id, name, email }
            Booking { id, bookingDate, amount, status }
            Hotel { id, name, city, rating, reviewCount }
            Destination { id, name }
            Amenity { id, name }

            Relationships:
            (Customer)-[:MADE_BOOKING]->(Booking)
            (Booking)-[:FOR_HOTEL]->(Hotel)
            (Booking)-[:HAS_DESTINATION]->(Destination)
            (Hotel)-[:LOCATED_IN]->(Destination)
            (Hotel)-[:HAS_AMENITY]->(Amenity)
            """;

    public String getRelevantSchema(String question) {
        return FULL_SCHEMA;
    }
}