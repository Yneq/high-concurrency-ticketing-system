package com.ticketing;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ticketing.race-window-ms=200")
@Testcontainers(disabledWithoutDocker = true)
class TicketIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void reset() {
        jdbc.update("UPDATE tickets SET remaining = 100 WHERE event_id = 1");
    }
    @Test void readsSeedReservesAndHandlesErrors() {
        var initial = http.getForEntity("/api/tickets/1", String.class);
        assertThat(initial.getStatusCode().value()).isEqualTo(200);
        assertThat(initial.getBody()).contains("\"remaining\":100");
        assertThat(http.postForEntity("/api/tickets/1/reserve", null, String.class)
            .getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT remaining FROM tickets WHERE event_id=1", Integer.class)).isEqualTo(99);
        jdbc.update("UPDATE tickets SET remaining = 0 WHERE event_id = 1");
        assertThat(http.postForEntity("/api/tickets/1/reserve", null, String.class)
            .getStatusCode().value()).isEqualTo(409);
        assertThat(http.getForEntity("/api/tickets/999", String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(http.postForEntity("/api/tickets/999/reserve", null, String.class)
            .getStatusCode().value()).isEqualTo(404);
    }
    @Test void concurrentSuccessesExceedPersistedDecrements() throws Exception {
        int clients = 20;
        var ready = new CountDownLatch(clients);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(clients)) {
            var futures = IntStream.range(0, clients).mapToObj(i -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return http.postForEntity("/api/tickets/1/reserve", null, String.class).getStatusCode().value();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        int remaining = jdbc.queryForObject("SELECT remaining FROM tickets WHERE event_id=1", Integer.class);
        // Twenty successes should leave 80. A larger value demonstrates lost updates.
        assertThat(remaining).isGreaterThan(100 - clients);
    }
}
