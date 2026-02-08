package com.systemdesign.logging.controller;

import com.systemdesign.logging.model.LogEntry;
import com.systemdesign.logging.service.LogIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogIngestionService logIngestionService;

    public LogController(LogIngestionService logIngestionService) {
        this.logIngestionService = logIngestionService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody LogEntry entry) {
        logIngestionService.ingest(entry);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted"));
    }
}
