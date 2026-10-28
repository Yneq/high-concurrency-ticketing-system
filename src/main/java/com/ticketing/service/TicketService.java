package com.ticketing.service;
import com.ticketing.dto.*;
import com.ticketing.entity.Ticket;
import com.ticketing.repository.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
@Service
public class TicketService {
    private final TicketRepository repository;
    private final long raceWindowMs;
    public TicketService(TicketRepository repository, @Value("${ticketing.race-window-ms:50}") long raceWindowMs) {
        if (raceWindowMs < 0) throw new IllegalArgumentException("race-window-ms must be nonnegative");
        this.repository = repository;
        this.raceWindowMs = raceWindowMs;
    }
    @Transactional(readOnly = true)
    public TicketResponse get(Long eventId) { return TicketResponse.from(find(eventId)); }
    @Transactional
    public ReserveResponse reserve(Long eventId) {
        Ticket ticket = find(eventId);
        if (ticket.getRemaining() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "SOLD_OUT");
        // Educational delay widens the real read/check/write race. Setting it to 0 does NOT fix it.
        try { Thread.sleep(raceWindowMs); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INTERRUPTED", e);
        }
        ticket.reserveOne();
        repository.saveAndFlush(ticket);
        return new ReserveResponse(eventId, "RESERVED", ticket.getRemaining());
    }
    private Ticket find(Long eventId) {
        return repository.findByEventId(eventId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
    }
}
