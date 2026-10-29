package com.ticketing.controller;
import com.ticketing.dto.*;
import com.ticketing.service.TicketService;
import org.springframework.web.bind.annotation.*;
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "ticketing.legacy-enabled", havingValue = "true")
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {
    private final TicketService service;
    public TicketController(TicketService service) { this.service = service; }
    @GetMapping("/{eventId}")
    public TicketResponse get(@PathVariable Long eventId) { return service.get(eventId); }
    @PostMapping("/{eventId}/reserve")
    public ReserveResponse reserve(@PathVariable Long eventId) {
        if (eventId != 1L) {
            service.get(eventId);
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"V1_ONLY_ALLOWS_EVENT_1");
        }
        return service.reserve(eventId);
    }
}
