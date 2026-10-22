package com.bth.vendorrag.ingestion;

import java.util.List;

public record TravelExtraction(
        String vendor,                 // "Asian Vacations DMC"
        String documentType,           // "booking_confirmation" | "package_flyer" | "invoice" | "itinerary"
        String packageName,            // "Kerala" — null for single bookings
        String tagline,                // "Nature's poetry, written in green" — null if not present
        List<Leg> legs,                // multi-city breakdown; single-element list for a simple booking
        Integer totalNights,
        Integer totalDays,
        Pricing pricing,
        List<String> inclusions,       // ["Hotels/Resorts", "Sightseeing", "Transport", "Breakfast"]
        String specialOffer,           // "Complementary 1 Hour Shikara in Alleppey"
        String bookingReference,       // null for flyers
        ContactInfo contact
) {
    public record Leg(
            String location,           // "Munnar"
            Integer nights
    ) {}

    public record Pricing(
            Double amount,
            String currency,
            String unit,               // "per_person"
            Integer minGuests          // 6
    ) {}

    public record ContactInfo(
            String phone,
            String email
    ) {}
}