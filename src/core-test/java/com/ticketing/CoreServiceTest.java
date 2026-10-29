package com.ticketing;
import com.ticketing.finalphase.*;
import com.ticketing.finalphase.ApiModels.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Unit tests with mocked database/cache. Do not establish real DB concurrency or integration. */
class CoreServiceTest {
    JdbcTemplate db; AdmissionGate gate; ReservationService service;
    java.util.concurrent.atomic.AtomicInteger commits, rollbacks;
    @BeforeEach void setup() {
        db=mock(JdbcTemplate.class);gate=mock(AdmissionGate.class);
        commits=new java.util.concurrent.atomic.AtomicInteger();rollbacks=new java.util.concurrent.atomic.AtomicInteger();
        var manager=new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction,TransactionDefinition definition) {}
            protected void doCommit(DefaultTransactionStatus status) { commits.incrementAndGet(); }
            protected void doRollback(DefaultTransactionStatus status) { rollbacks.incrementAndGet(); }
        };
        service=new ReservationService(db,new TransactionTemplate(manager),gate,300);
    }
    private Reservation prior(String status) { return new Reservation(UUID.randomUUID(),2,"user",status,OffsetDateTime.now().plusMinutes(5)); }
    private void status409(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
    }
    @Test void outboxFailureRequestsRollbackAndReturnsCacheAdmission() {
        when(gate.acquire(2,"outbox-failure")).thenReturn(1L);
        when(db.update(startsWith("UPDATE tickets SET remaining=remaining-1"),eq(2L))).thenReturn(1);
        when(db.update(startsWith("INSERT INTO outbox"),any(UUID.class),any(UUID.class)))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated outbox failure"));
        assertThatThrownBy(()->service.reserve(2,"outbox-failure","user",true,true))
            .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(rollbacks.get()).isEqualTo(1);assertThat(commits.get()).isZero();
        verify(gate).release(2,"outbox-failure");
    }
    @Test void committedReplayWinsOverExhaustedCache() {
        var previous=prior("PENDING_PAYMENT");
        when(db.<Reservation>query(contains("idempotency_key"),any(RowMapper.class),eq("racing-key")))
            .thenReturn(List.of(),List.of(previous));
        when(gate.acquire(2,"racing-key")).thenReturn(0L);
        assertThat(service.reserve(2,"racing-key","user",true,true)).isSameAs(previous);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void malformedAdmissionCannotReachDatabaseOrCache() {
        assertThatThrownBy(()->service.reserve(2,null,"user",true,true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.reserve(2,"key"," ",true,true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.reserve(1,"key","user",true,true)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(db,gate);
    }
    @Test void sameKeyReplayReturnsOriginalWithoutTakingMoreInventory() {
        var previous=prior("PAID");
        when(db.<Reservation>query(contains("idempotency_key"),any(RowMapper.class),eq("same"))).thenReturn(List.of(previous));
        assertThat(service.reserve(2,"same","user",true,true)).isSameAs(previous);
        verifyNoInteractions(gate);verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void keyCannotBeReboundToAnotherPayload() {
        when(db.<Reservation>query(contains("idempotency_key"),any(RowMapper.class),eq("same"))).thenReturn(List.of(prior("PENDING_PAYMENT")));
        status409(()->service.reserve(2,"same","other",true,true));verifyNoInteractions(gate);
    }
    @Test void paidIsTerminalEvenForLaterFailedPayment() {
        var paid=prior("PAID");
        when(db.<Reservation>query(contains("FOR UPDATE"),any(RowMapper.class),eq(paid.id()))).thenReturn(List.of(paid));
        assertThat(service.payment(paid.id(),false)).isSameAs(paid);
        verify(db,never()).update(anyString(),any(Object[].class));verifyNoInteractions(gate);
    }
    @Test void paymentBodyCannotOmitExplicitSuccess() {
        var mockService=mock(ReservationService.class);var api=new TicketApi(mockService);
        assertThatThrownBy(()->api.payment(UUID.randomUUID(),new PaymentBody(null)))
            .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(mockService);
    }
    @Test void lateSuccessDoesNotReviveAnExpiredReservation() {
        var expired=prior("EXPIRED");
        when(db.<Reservation>query(contains("FOR UPDATE"),any(RowMapper.class),eq(expired.id()))).thenReturn(List.of(expired));
        assertThat(service.payment(expired.id(),true)).isSameAs(expired);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
