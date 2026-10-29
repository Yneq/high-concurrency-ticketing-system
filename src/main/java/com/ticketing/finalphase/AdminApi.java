package com.ticketing.finalphase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
@RestController
@RequestMapping("/api/admin")
public class AdminApi {
    private final String token; private final ReservationService service; private final RecoveryJobs jobs;
    private final JdbcTemplate db; private final KafkaTemplate<String,String> kafka;
    public AdminApi(@Value("${ticketing.admin-token:}") String token,ReservationService service,RecoveryJobs jobs,JdbcTemplate db,KafkaTemplate<String,String> kafka) {
        this.token=token;this.service=service;this.jobs=jobs;this.db=db;this.kafka=kafka;
    }
    private void auth(HttpServletRequest request) {
        String actual=request.getHeader("X-Admin-Token");
        if(token.isBlank() || actual==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8)))
            throw ReservationService.error(HttpStatus.FORBIDDEN,"ADMIN_TOKEN_REQUIRED");
    }
    @GetMapping("/events/{event}/audit")
    public Object audit(@PathVariable long event,HttpServletRequest request) { auth(request);service.inventory(event);return service.audit(event); }
    @PostMapping("/events/{event}/reconcile")
    public Object repair(@PathVariable long event,HttpServletRequest request) { auth(request);jobs.repairInventory(event);return service.audit(event); }
    @GetMapping("/dead-letters")
    public Object deadLetters(HttpServletRequest request) { auth(request);return db.queryForList("SELECT * FROM dead_letters ORDER BY received_at DESC LIMIT 100"); }
    @PostMapping("/dead-letters/{id}/replay")
    public Object replay(@PathVariable UUID id,HttpServletRequest request) throws Exception {
        auth(request);
        var rows=db.queryForList("SELECT payload FROM dead_letters WHERE id=?",id);
        if(rows.isEmpty()) throw ReservationService.error(HttpStatus.NOT_FOUND,"DEAD_LETTER_NOT_FOUND");
        String payload=rows.getFirst().get("payload").toString();
        try { UUID.fromString(payload); } catch(IllegalArgumentException e) { throw ReservationService.error(HttpStatus.BAD_REQUEST,"INVALID_PAYLOAD_CANNOT_REPLAY"); }
        kafka.send("ticket-reservations",payload,payload).get(5,TimeUnit.SECONDS);
        db.update("UPDATE dead_letters SET replayed_at=CURRENT_TIMESTAMP WHERE id=?",id);
        return Map.of("status","REPLAYED");
    }
}
