package com.bth.vendorrag.api;

import com.bth.vendorrag.ingestion.IngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController {
    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(value = "/read", consumes = "multipart/form-data")
    public ResponseEntity<IngestionService.IngestionResult> ingest(
            @RequestParam MultipartFile file,
            @RequestParam(required = false) String sourceType) throws IOException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        IngestionService.IngestionResult result = ingestionService.ingest(
                file.getOriginalFilename(),
                file.getInputStream(),
                sourceType // optional hint: "whatsapp", "email", etc. Tika auto-detects otherwise
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}