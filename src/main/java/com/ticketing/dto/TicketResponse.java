package com.ticketing.dto;
import com.ticketing.entity.Ticket;
public record TicketResponse(Long eventId, int totalStock, int remaining) {
    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(ticket.getEventId(), ticket.getTotalStock(), ticket.getRemaining());
    }
}
