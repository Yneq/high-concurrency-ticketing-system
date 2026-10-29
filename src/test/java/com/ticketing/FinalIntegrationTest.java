package com.ticketing;

import com.ticketing.finalphase.*;
import com.ticketing.finalphase.ApiModels.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

// Deliberately no disabledWithoutDocker: the final acceptance suite must NOT silently skip.
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "ticketing.jobs-initial-delay-ms=3600000", "ticketing.admin-token=test-admin", "ticketing.reservation-ttl-seconds=300"})
@Testcontainers
class FinalIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Container static KafkaContainer broker=new KafkaContainer("apache/kafka:3.9.1");
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host",redis::getHost);
        registry.add("spring.data.redis.port",()->redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers",broker::getBootstrapServers);
    }
    @Autowired ReservationService service;
    @Autowired RecoveryJobs jobs;
    @Autowired OrderConsumer consumer;
    @Autowired RedisGate gate;
    @Autowired JdbcTemplate db;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired StringRedisTemplate cache;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired TestRestTemplate http;
    @BeforeEach void reset() {
        db.execute("TRUNCATE dead_letters,outbox,orders,reservations");
        db.update("UPDATE tickets SET remaining=total_stock WHERE event_id=2");
        try(var connection=cache.getConnectionFactory().getConnection()) { connection.serverCommands().flushAll(); }
        jobs.reconcile();
    }
    private Reservation reserve(String key) { return service.reserve(2,key,"user",false,false); }
    private int remaining() { return service.inventory(2).remaining(); }
    private long count(String table) { return db.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private void waitFor(BooleanSupplier ready) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(!ready.getAsBoolean()) { if(System.nanoTime()>deadline) fail("Timed out waiting for asynchronous behavior");Thread.sleep(100); }
    }
    @Test void publicHttpContractSupportsReplayAndPayment() {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("Idempotency-Key","http-case");
        var body=new HttpEntity<>(new ReserveBody("http-user"),headers);
        var first=http.postForEntity("/api/tickets/2/reserve",body,Reservation.class);
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        UUID id=first.getBody().id();
        var replay=http.postForEntity("/api/tickets/2/reserve",body,Reservation.class);
        assertThat(replay.getBody().id()).isEqualTo(id);
        consumer.receive(id.toString());
        assertThat(http.getForEntity("/api/reservations/"+id,Reservation.class).getBody().status()).isEqualTo("PENDING_PAYMENT");
        var paid=http.postForEntity("/api/orders/"+id+"/payment",new PaymentBody(true),Reservation.class);
        assertThat(paid.getStatusCode().value()).isEqualTo(200);assertThat(paid.getBody().status()).isEqualTo("PAID");
        assertThat(remaining()).isEqualTo(99);assertThat(count("orders")).isEqualTo(1);
    }
    @Test void atomicSqlNeverOversellsUnderConcurrentAdmission() throws Exception {
        try(var pool=Executors.newFixedThreadPool(32)) {
            var futures=IntStream.range(0,250).mapToObj(i->pool.submit(()->{
                try { reserve("atomic-"+i);return 1; }
                catch(ResponseStatusException e) { assertThat(e.getStatusCode().value()).isEqualTo(409);return 0; }
            })).toList();
            int accepted=0;for(var future:futures) accepted+=future.get(30,TimeUnit.SECONDS);
            assertThat(accepted).isEqualTo(100);
        }
        assertThat(remaining()).isZero();assertThat(count("orders")).isEqualTo(100);
        assertThat(((Number)service.audit(2).getFirst().get("discrepancy")).intValue()).isZero();
    }
    @Test void concurrentIdempotencyCreatesExactlyOneOrder() throws Exception {
        try(var pool=Executors.newFixedThreadPool(20)) {
            var futures=IntStream.range(0,50).mapToObj(i->pool.submit(()->reserve("same-key").id())).toList();
            Set<UUID> ids=new HashSet<>();for(var f:futures) ids.add(f.get(30,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(remaining()).isEqualTo(99);assertThat(count("orders")).isEqualTo(1);assertThat(count("reservations")).isEqualTo(1);
    }
    @Test void changedPayloadAndMalformedRequestsAreRejected() {
        reserve("bound-key");
        assertThatThrownBy(()->service.reserve(2,"bound-key","another-user",false,false))
            .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(http.postForEntity("/api/tickets/2/reserve",Map.of("userId","u"),String.class).getStatusCode().value()).isEqualTo(400);
        var r=reserve("payment-input");
        assertThat(http.postForEntity("/api/orders/"+r.id()+"/payment",Map.of(),String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/api/admin/events/2/audit",String.class).getStatusCode().value()).isEqualTo(403);
        assertThat(http.postForEntity("/api/v1/tickets/2/reserve",null,String.class).getStatusCode().value()).isEqualTo(404);
    }
    @Test void redisLuaAtomicallyAdmitsOneHundredAndDeduplicatesTokens() throws Exception {
        try(var pool=Executors.newFixedThreadPool(32)) {
            var futures=IntStream.range(0,200).mapToObj(i->pool.submit(()->gate.acquire(2,"lua-"+i))).toList();
            long accepted=0;for(var f:futures) if(f.get(10,TimeUnit.SECONDS)==1) accepted++;
            assertThat(accepted).isEqualTo(100);
        }
        assertThat(cache.opsForValue().get("ticket:{2}:stock")).isEqualTo("0");
        assertThat(gate.acquire(2,"lua-0")).isIn(0L,2L);
        // Isolated cache experiment: it does not create DB reservations.
        assertThat(remaining()).isEqualTo(100);
    }
    @Test void outboxAndDuplicateKafkaDeliveryCreateOneOrder() throws Exception {
        var r=service.reserve(2,"async-key","user",true,true);
        assertThat(r.status()).isEqualTo("QUEUED");assertThat(count("outbox")).isEqualTo(1);
        jobs.publish();waitFor(()->service.get(r.id()).status().equals("PENDING_PAYMENT"));
        kafka.send("ticket-reservations",r.id().toString(),r.id().toString()).get(10,TimeUnit.SECONDS);
        consumer.receive(r.id().toString());consumer.receive(r.id().toString());
        assertThat(count("orders")).isEqualTo(1);assertThat(remaining()).isEqualTo(99);
    }
    @Test void repeatedPaymentAndLaterFailureCannotReleasePaidInventory() {
        var r=reserve("paid-key");
        assertThat(service.payment(r.id(),true).status()).isEqualTo("PAID");
        UUID reference=db.queryForObject("SELECT payment_reference FROM orders WHERE id=?",UUID.class,r.id());
        service.payment(r.id(),true);service.payment(r.id(),false);
        assertThat(service.get(r.id()).status()).isEqualTo("PAID");assertThat(remaining()).isEqualTo(99);
        assertThat(db.queryForObject("SELECT payment_reference FROM orders WHERE id=?",UUID.class,r.id())).isEqualTo(reference);
    }
    @Test void failedPaymentReleasesOnce() {
        var r=reserve("failed-key");service.payment(r.id(),false);service.payment(r.id(),false);service.payment(r.id(),true);
        assertThat(service.get(r.id()).status()).isEqualTo("FAILED");assertThat(remaining()).isEqualTo(100);
    }
    @Test void queuedAndPendingExpireAndLateDeliveryCannotCreateOrders() {
        var queued=service.reserve(2,"queued-expiry","user",false,true);
        var pending=reserve("pending-expiry");
        db.update("UPDATE reservations SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        jobs.expire();jobs.expire();consumer.receive(queued.id().toString());
        assertThat(service.get(queued.id()).status()).isEqualTo("EXPIRED");
        assertThat(service.get(pending.id()).status()).isEqualTo("EXPIRED");
        assertThat(count("orders")).isEqualTo(1);assertThat(remaining()).isEqualTo(100);
    }
    @Test void waitingForRowLockCannotAuthorizePaymentAfterDeadline() throws Exception {
        var r=reserve("waiting-payment");
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->transactions.execute(status->{
                db.queryForObject("SELECT id FROM reservations WHERE id=? FOR UPDATE",UUID.class,r.id());
                db.update("UPDATE reservations SET expires_at=clock_timestamp()+INTERVAL '1 second' WHERE id=?",r.id());
                locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Lock release timed out"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
                return true;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var payment=pool.submit(()->service.payment(r.id(),true));
            Thread.sleep(1500);release.countDown();
            holder.get(10,TimeUnit.SECONDS);
            assertThat(payment.get(10,TimeUnit.SECONDS).status()).isEqualTo("EXPIRED");
        } finally { release.countDown(); }
        assertThat(remaining()).isEqualTo(100);
    }
    @Test void paymentAndExpiryRaceConservesInventory() throws Exception {
        var r=reserve("race-payment");
        db.update("UPDATE reservations SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",r.id());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var payment=pool.submit(()->service.payment(r.id(),true));var expiry=pool.submit(()->{jobs.expire();return true;});
            payment.get(10,TimeUnit.SECONDS);expiry.get(10,TimeUnit.SECONDS);
        }
        assertThat(service.get(r.id()).status()).isEqualTo("EXPIRED");assertThat(remaining()).isEqualTo(100);
    }
    @Test void poisonedMessageGoesToDlqAndIsPersisted() throws Exception {
        kafka.send("ticket-reservations","poison","invalid-uuid").get(10,TimeUnit.SECONDS);
        waitFor(()->db.queryForObject("SELECT count(*) FROM dead_letters WHERE payload='invalid-uuid'",Long.class)>0);
        assertThat(db.queryForObject("SELECT payload FROM dead_letters WHERE payload='invalid-uuid' LIMIT 1",String.class)).isEqualTo("invalid-uuid");
    }
    @Test void databaseAndRedisDriftCanBeRepaired() {
        reserve("repair-key");db.update("UPDATE tickets SET remaining=50 WHERE event_id=2");
        cache.opsForValue().set("ticket:{2}:stock","500");jobs.repairInventory(2);
        assertThat(remaining()).isEqualTo(99);assertThat(cache.opsForValue().get("ticket:{2}:stock")).isEqualTo("99");
    }
    @Test void kafkaFailureRetainsOutboxUntilBrokerRecovers() throws Exception {
        var r=service.reserve(2,"broker-outage","user",false,true);
        broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
        try { jobs.publish();assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL",Long.class)).isEqualTo(1); }
        finally { broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec(); }
        jobs.publish();waitFor(()->service.get(r.id()).status().equals("PENDING_PAYMENT"));
        assertThat(count("orders")).isEqualTo(1);
    }
    @Test void redisFailureFallsBackToAtomicDatabaseAndProjectionRecovers() {
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try { assertThat(service.reserve(2,"redis-outage","user",true,false).status()).isEqualTo("PENDING_PAYMENT"); }
        finally { redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec(); }
        jobs.reconcile();assertThat(remaining()).isEqualTo(99);
        assertThat(cache.opsForValue().get("ticket:{2}:stock")).isEqualTo("99");
    }
}
