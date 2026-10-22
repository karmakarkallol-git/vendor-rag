package com.bth.vendorrag.nlquery;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Best-effort Cypher generation when the LLM is unreachable.
 * Covers a handful of common, high-frequency question shapes only —
 * this is a degraded-mode bypass, not a replacement for the LLM path.
 * Anything it doesn't recognize falls through (empty Optional) so the
 * caller can return a clear "LLM unavailable, try again or use /query" error.
 */
@Component
public class RuleBasedCypherFallback {

    private static final Pattern HOTELS_IN_DESTINATION = Pattern.compile(
            "hotels?\\s+in\\s+([a-zA-Z\\s]+?)(?:\\?|$)", Pattern.CASE_INSENSITIVE);

    private static final Pattern HOTELS_WITH_AMENITY = Pattern.compile(
            "hotels?\\s+(?:in\\s+([a-zA-Z\\s]+?)\\s+)?(?:with|that have)\\s+(?:a\\s+)?([a-zA-Z\\s]+?)(?:\\?|$)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HOTELS_RATED_ABOVE = Pattern.compile(
            "hotels?\\s+(?:in\\s+([a-zA-Z\\s]+?)\\s+)?(?:rated|with rating)\\s+(?:above|over)?\\s*([\\d.]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BOOKINGS_ABOVE_AMOUNT = Pattern.compile(
            "bookings?\\s+(?:above|over|greater than)\\s+(?:₹|rs\\.?|inr)?\\s*([\\d,]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BOOKINGS_BY_STATUS = Pattern.compile(
            "bookings?\\s+(?:that are|with status)?\\s*(confirmed|cancelled|canceled|pending|completed)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CUSTOMERS_WHO_BOOKED_DESTINATION = Pattern.compile(
            "customers?\\s+who\\s+booked.*?in\\s+([a-zA-Z\\s]+?)(?:\\?|$)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CUSTOMER_BOOKING_COUNT = Pattern.compile(
            "how many bookings (?:does|has)\\s+([a-zA-Z\\s]+?)\\s+(?:made|have)",
            Pattern.CASE_INSENSITIVE);

    public Optional<CypherRequest> tryMatch(String question) {
        Matcher m;

        // Order matters: more specific patterns (amenity, rating) before the
        // plain "hotels in X" pattern, since they'd otherwise be swallowed by it.
        m = HOTELS_WITH_AMENITY.matcher(question);
        if (m.find()) {
            String destination = m.group(1) != null ? m.group(1).trim() : null;
            String amenity = m.group(2).trim();
            Map<String, Object> params = params("amenity", amenity);
            String destinationClause = "";
            if (destination != null) {
                destinationClause = "AND toLower(d.name) = toLower($destination)\n                    ";
                params.put("destination", destination);
            }
            return Optional.of(new CypherRequest(
                    """
                    MATCH (h:Hotel)-[:HAS_AMENITY]->(a:Amenity)
                    OPTIONAL MATCH (h)-[:LOCATED_IN]->(d:Destination)
                    WHERE toLower(a.name) CONTAINS toLower($amenity)
                    %sRETURN h.name AS name, h.rating AS rating
                    LIMIT 100
                    """.formatted(destinationClause),
                    params,
                    "Rule-based fallback: hotels by amenity"
            ));
        }

        m = HOTELS_RATED_ABOVE.matcher(question);
        if (m.find()) {
            String destination = m.group(1) != null ? m.group(1).trim() : null;
            double rating = Double.parseDouble(m.group(2));
            Map<String, Object> params = params("rating", rating);
            String destinationClause = "";
            if (destination != null) {
                destinationClause = "AND toLower(d.name) = toLower($destination)\n                    ";
                params.put("destination", destination);
            }
            return Optional.of(new CypherRequest(
                    """
                    MATCH (h:Hotel)
                    OPTIONAL MATCH (h)-[:LOCATED_IN]->(d:Destination)
                    WHERE h.rating > $rating
                    %sRETURN h.name AS name, h.rating AS rating
                    ORDER BY h.rating DESC
                    LIMIT 100
                    """.formatted(destinationClause),
                    params,
                    "Rule-based fallback: hotels by minimum rating"
            ));
        }

        m = HOTELS_IN_DESTINATION.matcher(question);
        if (m.find()) {
            String destination = m.group(1).trim();
            return Optional.of(new CypherRequest(
                    """
                    MATCH (h:Hotel)-[:LOCATED_IN]->(d:Destination)
                    WHERE toLower(d.name) = toLower($destination)
                    RETURN h.name AS name, h.rating AS rating
                    LIMIT 100
                    """,
                    params("destination", destination),
                    "Rule-based fallback: hotels by destination"
            ));
        }

        m = BOOKINGS_BY_STATUS.matcher(question);
        if (m.find()) {
            String status = normalizeStatus(m.group(1));
            return Optional.of(new CypherRequest(
                    """
                    MATCH (b:Booking)
                    WHERE toLower(b.status) = toLower($status)
                    RETURN b
                    ORDER BY b.bookingDate DESC
                    LIMIT 100
                    """,
                    params("status", status),
                    "Rule-based fallback: bookings by status"
            ));
        }

        m = BOOKINGS_ABOVE_AMOUNT.matcher(question);
        if (m.find()) {
            double amount = Double.parseDouble(m.group(1).replace(",", ""));
            return Optional.of(new CypherRequest(
                    """
                    MATCH (b:Booking)
                    WHERE b.amount > $amount
                    RETURN b
                    ORDER BY b.amount DESC
                    LIMIT 100
                    """,
                    params("amount", amount),
                    "Rule-based fallback: bookings above amount"
            ));
        }

        m = CUSTOMER_BOOKING_COUNT.matcher(question);
        if (m.find()) {
            String customerName = m.group(1).trim();
            return Optional.of(new CypherRequest(
                    """
                    MATCH (c:Customer)-[:MADE_BOOKING]->(b:Booking)
                    WHERE toLower(c.name) = toLower($customerName)
                    RETURN c.name AS name, count(b) AS bookingCount
                    """,
                    params("customerName", customerName),
                    "Rule-based fallback: booking count by customer"
            ));
        }

        m = CUSTOMERS_WHO_BOOKED_DESTINATION.matcher(question);
        if (m.find()) {
            String destination = m.group(1).trim();
            return Optional.of(new CypherRequest(
                    """
                    MATCH (c:Customer)-[:MADE_BOOKING]->(b:Booking)-[:HAS_DESTINATION]->(d:Destination)
                    WHERE toLower(d.name) = toLower($destination)
                    RETURN DISTINCT c.name AS name, c.email AS email
                    LIMIT 100
                    """,
                    params("destination", destination),
                    "Rule-based fallback: customers by destination"
            ));
        }

        return Optional.empty();
    }

    private String normalizeStatus(String raw) {
        String lower = raw.toLowerCase();
        return lower.equals("canceled") ? "cancelled" : lower;
    }

    private Map<String, Object> params(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
}