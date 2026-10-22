package com.bth.vendorrag.ingestion.extraction;

public record HotelExtraction(
        String name,
        String city,
        Double rating
) {}