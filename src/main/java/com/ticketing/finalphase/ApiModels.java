package com.ticketing.finalphase;
import java.time.OffsetDateTime;
import java.util.UUID;
public final class ApiModels {
    private ApiModels() {}
    public record ReserveBody(String userId) {}
    public record PaymentBody(Boolean success) {}
    public record Reservation(UUID id, long eventId, String userId, String status, OffsetDateTime expiresAt) {}
    public record Inventory(long eventId, int totalStock, int remaining) {}
}
