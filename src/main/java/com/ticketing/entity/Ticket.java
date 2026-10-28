package com.ticketing.entity;
import jakarta.persistence.*;
@Entity
@Table(name = "tickets")
public class Ticket {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true)
    private Long eventId;
    @Column(name = "total_stock", nullable = false)
    private int totalStock;
    @Column(nullable = false)
    private int remaining;
    protected Ticket() {}
    public Long getEventId() { return eventId; }
    public int getTotalStock() { return totalStock; }
    public int getRemaining() { return remaining; }
    // Intentionally no @Version, lock, or atomic conditional UPDATE in Phase 1.
    public void reserveOne() { remaining = remaining - 1; }
}
