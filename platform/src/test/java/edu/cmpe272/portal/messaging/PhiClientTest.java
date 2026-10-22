package edu.cmpe272.portal.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PhiClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private PhiClient clientFor(int port, Duration readTimeout) {
        return new PhiClient(new PhiProperties("http://127.0.0.1:" + port, Duration.ofMillis(500), readTimeout),
                RestClient.builder());
    }

    private int serve(int delayMs, String body, AtomicInteger calls) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/process", ex -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void parsesRedaction() throws IOException {
        UUID id = UUID.randomUUID();
        int port = serve(0, "{\"message_id\":\"" + id + "\",\"redacted_text\":\"Hi <PERSON>\",\"phi_entity_count\":1,"
                + "\"entity_counts\":{\"PERSON\":1},\"entities\":[],\"latency_ms\":3}", new AtomicInteger());

        var result = clientFor(port, Duration.ofSeconds(2)).redact(id, "Hi Maria");

        assertThat(result.redactedText()).isEqualTo("Hi <PERSON>");
        assertThat(result.toString()).doesNotContain("PERSON>");
    }

    @Test
    void readTimeoutIsNotRetried() throws IOException {
        var calls = new AtomicInteger();
        int port = serve(1500, "{}", calls);

        assertThatThrownBy(() -> clientFor(port, Duration.ofMillis(300)).redact(UUID.randomUUID(), "x"))
                .isInstanceOf(RedactionException.class)
                .satisfies(e -> assertThat(((RedactionException) e).reason()).isEqualTo(RedactionException.Reason.TIMEOUT));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void mismatchedMessageIdIsRejected() throws IOException {
        int port = serve(0, "{\"message_id\":\"" + UUID.randomUUID() + "\",\"redacted_text\":\"x\",\"phi_entity_count\":0}",
                new AtomicInteger());

        assertThatThrownBy(() -> clientFor(port, Duration.ofSeconds(2)).redact(UUID.randomUUID(), "x"))
                .isInstanceOf(RedactionException.class)
                .satisfies(e -> assertThat(((RedactionException) e).reason())
                        .isEqualTo(RedactionException.Reason.INVALID_RESPONSE));
    }

    @Test
    void connectionRefusedIsRetriedOnceThenFails() {
        // Nothing listens on this port: both attempts are refused.
        assertThatThrownBy(() -> clientFor(1, Duration.ofSeconds(1)).redact(UUID.randomUUID(), "x"))
                .isInstanceOf(RedactionException.class)
                .satisfies(e -> assertThat(((RedactionException) e).reason()).isEqualTo(RedactionException.Reason.ERROR));
    }
}
