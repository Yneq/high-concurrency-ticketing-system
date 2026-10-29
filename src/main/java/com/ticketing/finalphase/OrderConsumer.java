package com.ticketing.finalphase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
@Component
public class OrderConsumer {
    private final ReservationService service;
    private final TransactionTemplate tx;
    private final JdbcTemplate db;
    public OrderConsumer(ReservationService service, TransactionTemplate tx, JdbcTemplate db) { this.service=service;this.tx=tx;this.db=db; }
    @KafkaListener(topics="ticket-reservations",groupId="ticket-orders",autoStartup="${ticketing.consumer-enabled:true}")
    public void receive(String payload) { tx.executeWithoutResult(status -> service.createOrder(UUID.fromString(payload))); }
    @KafkaListener(topics="ticket-reservations.DLT",groupId="ticket-dlq",containerFactory="deadLetterFactory")
    public void deadLetter(String payload) {
        db.update("INSERT INTO dead_letters(id,payload,error) VALUES(?,?,?)",UUID.randomUUID(),payload,"Consumer retries exhausted; inspect broker headers and application logs");
    }
}
