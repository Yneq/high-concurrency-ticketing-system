package com.ticketing.finalphase;
import com.ticketing.finalphase.ApiModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class TicketApi {
    private final ReservationService service;
    public TicketApi(ReservationService service) { this.service=service; }
    @GetMapping("/api/tickets/{event}")
    public Inventory inventory(@PathVariable long event) { return service.inventory(event); }
    @PostMapping("/api/tickets/{event}/reserve")
    public ResponseEntity<Reservation> reserve(@PathVariable long event,@RequestHeader("Idempotency-Key") String key,@RequestBody ReserveBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.reserve(event,key,body.userId(),true,true));
    }
    @PostMapping("/api/v2/tickets/{event}/reserve")
    public Reservation atomic(@PathVariable long event,@RequestHeader("Idempotency-Key") String key,@RequestBody ReserveBody body) {
        return service.reserve(event,key,body.userId(),false,false);
    }
    @PostMapping("/api/v3/tickets/{event}/reserve")
    public Reservation redis(@PathVariable long event,@RequestHeader("Idempotency-Key") String key,@RequestBody ReserveBody body) {
        return service.reserve(event,key,body.userId(),true,false);
    }
    @GetMapping("/api/reservations/{id}")
    public Reservation get(@PathVariable UUID id) { return service.get(id); }
    @GetMapping("/api/orders/{id}")
    public Reservation order(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/api/orders/{id}/payment")
    public Reservation payment(@PathVariable UUID id,@RequestBody PaymentBody body) { if (body.success()==null) throw ReservationService.error(HttpStatus.BAD_REQUEST,"SUCCESS_REQUIRED");
        return service.payment(id,body.success()); }
}
