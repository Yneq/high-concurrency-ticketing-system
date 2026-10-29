package com.ticketing.finalphase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
@Component
public class RecoveryJobs {
    private static final Logger log=LoggerFactory.getLogger(RecoveryJobs.class);
    private final JdbcTemplate db; private final KafkaTemplate<String,String> kafka;
    private final TransactionTemplate tx; private final ReservationService service; private final AdmissionGate gate;
    public RecoveryJobs(JdbcTemplate db,KafkaTemplate<String,String> kafka,TransactionTemplate tx,ReservationService service,AdmissionGate gate) {
        this.db=db;this.kafka=kafka;this.tx=tx;this.service=service;this.gate=gate;
    }
    @Scheduled(initialDelayString="${ticketing.jobs-initial-delay-ms:1000}",fixedDelayString="${ticketing.outbox-delay-ms:1000}")
    public void publish() {
        // One bounded send per short transaction. Crash after broker ack may duplicate delivery.
        // Never mark published before Kafka ack. Consumer is idempotent by reservation PK/status.
        for(int i=0;i<20;i++) {
            try {
                Boolean sent=tx.execute(status -> {
                    var rows=db.queryForList("SELECT id,reservation_id FROM outbox WHERE published_at IS NULL ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");
                    if(rows.isEmpty()) return false;
                    var row=rows.getFirst(); UUID id=(UUID)row.get("id");
                    try { kafka.send("ticket-reservations",row.get("reservation_id").toString(),row.get("reservation_id").toString()).get(5,TimeUnit.SECONDS); }
                    catch(Exception e) { if(e instanceof InterruptedException) Thread.currentThread().interrupt();throw new IllegalStateException("Kafka unavailable; outbox retained",e); }
                    db.update("UPDATE outbox SET published_at=CURRENT_TIMESTAMP,attempts=attempts+1 WHERE id=?",id);
                    return true;
                });
                if(!Boolean.TRUE.equals(sent)) break;
            } catch(RuntimeException e) { log.warn("Outbox publication deferred: {}",e.getMessage());break; }
        }
    }
    @Scheduled(initialDelayString="${ticketing.jobs-initial-delay-ms:1000}",fixedDelayString="${ticketing.expiry-delay-ms:1000}")
    public void expire() {
        try {
            tx.executeWithoutResult(status -> {
                var ids=db.query("SELECT id FROM reservations WHERE status IN ('QUEUED','PENDING_PAYMENT') AND expires_at<=CURRENT_TIMESTAMP ORDER BY expires_at FOR UPDATE SKIP LOCKED LIMIT 100",
                    (rs,n)->rs.getObject(1,UUID.class));
                for(UUID id:ids) service.releaseLocked(id,"EXPIRED");
            });
        } catch(RuntimeException e) { log.warn("Expiry deferred: {}",e.getMessage()); }
    }
    @Scheduled(initialDelayString="${ticketing.jobs-initial-delay-ms:1000}",fixedDelayString="${ticketing.reconcile-delay-ms:5000}")
    public void reconcile() {
        try {
            var rows=db.queryForList("SELECT event_id,remaining FROM tickets ORDER BY event_id");
            for(var row:rows) gate.project(((Number)row.get("event_id")).longValue(),((Number)row.get("remaining")).intValue());
        } catch(RuntimeException e) { log.debug("Redis projection deferred",e); }
    }
    public void repairInventory(long event) {
        tx.executeWithoutResult(status -> {
            // All admissions/expirations update this row. Lock it before taking the authoritative snapshot.
            var totals=db.queryForList("SELECT total_stock FROM tickets WHERE event_id=? FOR UPDATE",event);
            if(totals.isEmpty()) throw ReservationService.error(org.springframework.http.HttpStatus.NOT_FOUND,"EVENT_NOT_FOUND");
            long held=db.queryForObject("SELECT count(*) FROM reservations WHERE event_id=? AND status IN ('QUEUED','PENDING_PAYMENT','PAID')",Long.class,event);
            int total=((Number)totals.getFirst().get("total_stock")).intValue();
            if(held>total) throw new IllegalStateException("Held reservations exceed total; manual investigation required");
            db.update("UPDATE tickets SET remaining=? WHERE event_id=?",total-held,event);
        });
        reconcile();
    }
}
