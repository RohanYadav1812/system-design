package com.systemdesign.logging.service;

import com.systemdesign.logging.model.LogEntry;

public interface LogIngestionService {

    void ingest(LogEntry entry);
}
