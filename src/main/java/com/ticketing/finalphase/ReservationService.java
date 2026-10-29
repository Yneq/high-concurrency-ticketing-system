package com.ticketing.finalphase;
import com.ticketing.finalphase.ApiModels.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ReservationService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AdmissionGate gate;
    private final long ttl;
    public ReservationService(JdbcTemplate db, TransactionTemplate tx, AdmissionGate gate,
                              @Value("${ticketing.reservation-ttl-seconds:300}") long ttl) {
        if (ttl < 1) throw new IllegalArgumentException("reservation TTL must be positive");
        this.db = db; this.tx = tx; this.gate = gate; this.ttl = ttl;
    }
    static ResponseStatusException error(HttpStatus status, String reason) { return new ResponseStatusException(status, reason); }
    private Reservation map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Reservation(rs.getObject("id", UUID.class), rs.getLong("event_id"), rs.getString("user_id"),
            rs.getString("status"), rs.getObject("expires_at", OffsetDateTime.class));
    }
    public Inventory inventory(long event) {
        return db.query("SELECT event_id,total_stock,remaining FROM tickets WHERE event_id=?",
            (rs,n) -> new Inventory(rs.getLong(1),rs.getInt(2),rs.getInt(3)), event).stream().findFirst()
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND,"EVENT_NOT_FOUND"));
    }
    public Reservation get(UUID id) {
        return db.query("SELECT * FROM reservations WHERE id=?",this::map,id).stream().findFirst()
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND,"RESERVATION_NOT_FOUND"));
    }
    public Reservation replay(String key) {
        return db.query("SELECT * FROM reservations WHERE idempotency_key=?",this::map,key).stream().findFirst().orElse(null);
    }
    private Reservation match(Reservation previous, long event, String user) {
        if (previous.eventId()!=event || !previous.userId().equals(user))
            throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD");
        return previous;
    }
    public Reservation reserve(long event, String key, String user, boolean useRedis, boolean asynchronous) {
        if (event==1) throw error(HttpStatus.BAD_REQUEST,"EVENT_1_RESERVED_FOR_UNSAFE_V1");
        if (key==null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw error(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY");
        if (user==null || user.isBlank() || user.length()>100) throw error(HttpStatus.BAD_REQUEST,"INVALID_USER_ID");
        Reservation prior = replay(key);
        if (prior!=null) return match(prior,event,user);
        long admitted = useRedis ? gate.acquire(event,key) : -1;
        if (admitted==0) {
            // Concurrent same-key admission may already be committed by now.
            prior = replay(key);
            if (prior!=null) return match(prior,event,user);
            inventory(event); // Preserve 404 semantics for missing events.
            throw error(HttpStatus.CONFLICT,"SOLD_OUT_OR_ADMISSION_CACHE_EXHAUSTED");
        }
        try {
            return tx.execute(status -> {
                // Serializes same-key requests across instances; unique key remains the durable constraint.
                db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, key);
                Reservation existing = replay(key);
                if (existing!=null) return match(existing,event,user);
                int changed = db.update("UPDATE tickets SET remaining=remaining-1 WHERE event_id=? AND remaining>0",event);
                if (changed!=1) { inventory(event); throw error(HttpStatus.CONFLICT,"SOLD_OUT"); }
                UUID id=UUID.randomUUID();
                db.update("INSERT INTO reservations(id,idempotency_key,event_id,user_id,status,expires_at) VALUES(?,?,?,?,'QUEUED',clock_timestamp() + (? * INTERVAL '1 second'))",
                    id,key,event,user,ttl);
                if (asynchronous) db.update("INSERT INTO outbox(id,reservation_id) VALUES(?,?)",UUID.randomUUID(),id);
                else createOrder(id);
                return get(id);
            });
        } catch (RuntimeException e) {
            if (admitted==1) gate.release(event,key);
            throw e;
        }
    }
    /** Caller must hold a transaction; row locking makes delivery and payment/expiry mutually exclusive. */
    public void createOrder(UUID id) {
        var rows=db.query("SELECT * FROM reservations WHERE id=? FOR UPDATE",this::map,id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown reservation " + id);
        var reservation=rows.getFirst();
        if (!reservation.status().equals("QUEUED")) return;
        if (db.queryForObject("SELECT expires_at<=clock_timestamp() FROM reservations WHERE id=?",Boolean.class,id)) {
            releaseLocked(id,"EXPIRED"); return;
        }
        db.update("INSERT INTO orders(id,status) VALUES(?,'PENDING_PAYMENT') ON CONFLICT(id) DO NOTHING",id);
        db.update("UPDATE reservations SET status='PENDING_PAYMENT',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
    }
    public Reservation payment(UUID id, boolean success) {
        return tx.execute(status -> {
            var rows=db.query("SELECT * FROM reservations WHERE id=? FOR UPDATE",this::map,id);
            if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND,"RESERVATION_NOT_FOUND");
            var r=rows.getFirst();
            // Paid is terminal. A later failed payment cannot release a paid ticket.
            if (r.status().equals("PAID")) return r;
            if (r.status().equals("EXPIRED") || r.status().equals("FAILED")) return r;
            if (db.queryForObject("SELECT expires_at<=clock_timestamp() FROM reservations WHERE id=?",Boolean.class,id)) {
                releaseLocked(id,"EXPIRED"); return get(id);
            }
            if (!r.status().equals("PENDING_PAYMENT")) throw error(HttpStatus.CONFLICT,"ORDER_NOT_READY");
            if (!success) { releaseLocked(id,"FAILED"); return get(id); }
            db.update("UPDATE orders SET status='PAID',payment_reference=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",UUID.randomUUID(),id);
            db.update("UPDATE reservations SET status='PAID',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
            return get(id);
        });
    }
    public void releaseLocked(UUID id,String terminal) {
        // Row is locked by the caller. Compare-and-set provides a second exactly-once release guard.
        var events=db.query("UPDATE reservations SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('QUEUED','PENDING_PAYMENT') RETURNING event_id",
            (rs,n)->rs.getLong(1),terminal,id);
        if (events.isEmpty()) return;
        db.update("UPDATE orders SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",terminal,id);
        db.update("UPDATE tickets SET remaining=remaining+1 WHERE event_id=?",events.getFirst());
        // Redis is asynchronously repaired from DB; never make stock release depend on Redis availability.
    }
    public List<Map<String,Object>> audit(long event) {
        return db.queryForList("""
            SELECT t.event_id,t.total_stock,t.remaining,
              (SELECT count(*) FROM reservations r WHERE r.event_id=t.event_id AND r.status IN ('QUEUED','PENDING_PAYMENT','PAID')) AS held,
              t.total_stock-t.remaining-(SELECT count(*) FROM reservations r WHERE r.event_id=t.event_id AND r.status IN ('QUEUED','PENDING_PAYMENT','PAID')) AS discrepancy,
              (SELECT count(*) FROM orders o JOIN reservations r ON r.id=o.id WHERE r.event_id=t.event_id AND o.status='PAID') AS paid,
              (SELECT count(*) FROM (SELECT o.id FROM orders o JOIN reservations r ON r.id=o.id WHERE r.event_id=t.event_id GROUP BY o.id HAVING count(*)>1) d) AS duplicate_orders
            FROM tickets t WHERE t.event_id=?
            """,event);
    }
}
