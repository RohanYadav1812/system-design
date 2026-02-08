package com.systemdesign.logging.service.impl;

import com.systemdesign.logging.model.LogEntry;
import com.systemdesign.logging.service.LogIngestionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaLogIngestionService implements LogIngestionService {

    private final KafkaTemplate<String, LogEntry> kafkaTemplate;
    private final String topic;

    public KafkaLogIngestionService(KafkaTemplate<String, LogEntry> kafkaTemplate,
                                    @Value("${logging.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void ingest(LogEntry entry) {
        kafkaTemplate.send(topic, entry.service(), entry);
    }
}
