package edu.cmpe272.portal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for the PHI detection service (whose real model is tested inside its image). Replaces
 * every value from fake_phi.csv with an entity tag, which is enough to prove that only redacted text
 * leaves intake. {@link #failing} makes it answer 500.
 */
final class PhiServiceStub implements AutoCloseable {

    final AtomicBoolean failing = new AtomicBoolean(false);
    final AtomicInteger calls = new AtomicInteger();
    private final HttpServer server;
    private final Map<String, String> phiToTag = new LinkedHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    PhiServiceStub(Path fakePhiCsv) throws IOException {
        List<String> lines = Files.readAllLines(fakePhiCsv);
        for (String line : lines.subList(1, lines.size())) {
            // name,dob,phone,email,mrn,"address" (the address is quoted and contains commas)
            String[] f = line.split(",", 6);
            phiToTag.put(f[0], "<PERSON>");
            phiToTag.put(f[1], "<DATE_OF_BIRTH>");
            phiToTag.put(f[2], "<PHONE_NUMBER>");
            phiToTag.put(f[3], "<EMAIL_ADDRESS>");
            phiToTag.put(f[4], "<MRN>");
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/process", exchange -> {
            calls.incrementAndGet();
            if (failing.get()) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            JsonNode req = json.readTree(exchange.getRequestBody());
            String text = req.path("text").asText();
            int count = 0;
            for (var e : phiToTag.entrySet()) {
                while (text.contains(e.getKey())) {
                    text = text.replaceFirst(java.util.regex.Pattern.quote(e.getKey()), e.getValue());
                    count++;
                }
            }
            byte[] body = json.writeValueAsBytes(Map.of("message_id", req.path("message_id").asText(),
                    "redacted_text", text, "phi_entity_count", count, "entity_counts", Map.of()));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
