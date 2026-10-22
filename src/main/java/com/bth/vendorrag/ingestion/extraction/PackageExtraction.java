package com.bth.vendorrag.ingestion.extraction;

import java.math.BigDecimal;

public record PackageExtraction(
        String packageName,
        String destination,
        Integer durationDays,
        BigDecimal price,
        HotelExtraction hotel
) {}