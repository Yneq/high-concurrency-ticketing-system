package com.ticketing.controller;
import com.ticketing.dto.*;
import com.ticketing.service.TicketService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;
    public TicketController(TicketService service) { this.service = service; }
    @GetMapping("/{eventId}")
    public TicketResponse get(@PathVariable Long eventId) { return service.get(eventId); }
    @PostMapping("/{eventId}/reserve")
    public ReserveResponse reserve(@PathVariable Long eventId) { return service.reserve(eventId); }
}
