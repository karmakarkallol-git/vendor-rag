package com.bth.vendorrag.ingestion.parser;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class FileParserService {

    private final Tika tika = new Tika();

    public String extractText(InputStream inputStream) {
        try {
            return tika.parseToString(inputStream);
        } catch (IOException | TikaException e) {
            throw new FileParsingException("Failed to parse file", e);
        }
    }
}