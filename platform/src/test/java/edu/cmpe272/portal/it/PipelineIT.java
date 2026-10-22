package edu.cmpe272.portal.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The platform against real infrastructure: Postgres 16 + pgvector, Kafka (KRaft) and the mock FHIR
 * server run in containers (same images as Compose); the PHI service is a stub and the test itself
 * plays the four AI workers. Covers intake, Kafka publishing, result persistence (idempotency, DLQ),
 * the FHIR patient lookup and the redacted Communication write-back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PipelineIT {

    static final Path FAKE_PHI = Path.of("../fake_phi.csv").toAbsolutePath().normalize();
    static final String PASSWORD = "it-demo-password";
    static final String ORIGINAL = "Hi, this is Maria Lopez (MRN-482913, DOB 1962-03-14). I have chest pain and need "
            + "my lisinopril refilled. Call me at (408) 555-0142 or maria.lopez@example.com.";
    static final List<String> PHI = List.of("Maria Lopez", "MRN-482913", "1962-03-14", "(408) 555-0142",
            "maria.lopez@example.com");
    static final List<String> TOPICS = List.of("messages.redacted", "messages.redacted.dlq", "service.events",
            "service.events.dlq", "results.urgency", "results.routing", "results.summarization", "results.search",
            "results.urgency.dlq", "results.routing.dlq", "results.summarization.dlq", "results.search.dlq");

    // Started once for the class (singleton pattern) so topics exist before the listeners start.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg16").asCompatibleSubstituteFor("postgres"));
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
    static final GenericContainer<?> FHIR = new GenericContainer<>(
            new ImageFromDockerfile("portal/fhir-mock-it", false).withFileFromPath(".", Path.of("../fhir-mock")))
            .withCopyFileToContainer(MountableFile.forHostPath(FAKE_PHI, 0644), "/app/seed/fake_phi.csv")
            .withExposedPorts(8000)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200));
    static final PhiServiceStub PHI_SERVICE;

    static {
        POSTGRES.start();
        KAFKA.start();
        FHIR.start();
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(TOPICS.stream().map(t -> new NewTopic(t, 3, (short) 1)).toList()).all().get();
            PHI_SERVICE = new PhiServiceStub(FAKE_PHI);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.placeholders.search_db_password", () -> "0123456789abcdef");
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("app.phi.url", PHI_SERVICE::url);
        r.add("app.fhir.base-url", () -> "http://" + FHIR.getHost() + ":" + FHIR.getMappedPort(8000) + "/fhir");
        r.add("app.fhir.sweep-interval", () -> "2s");
        r.add("app.fhir.sweep-initial-delay", () -> "1s");
        r.add("app.outbox.interval", () -> "2s");
        r.add("app.jwt.secret", () -> "integration-test-secret-at-least-32-bytes!!");
        r.add("app.seed.enabled", () -> "true");
        r.add("app.seed.demo-password", () -> PASSWORD);
        r.add("app.seed.fake-phi-path", FAKE_PHI::toString);
    }

    @AfterAll
    static void stopStub() {
        PHI_SERVICE.close();
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient fhirHttp = HttpClient.newHttpClient();

    // ---------- intake --------------------------------------------------------------------------

    @Test
    void intakeStoresOriginalRestrictedAndPublishesRedactedTextOnly() throws Exception {
        UUID id = submit("maria.lopez", ORIGINAL);

        assertThat(jdbc.queryForObject("SELECT original_text FROM message_original WHERE message_id = ?", String.class, id))
                .isEqualTo(ORIGINAL);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, redacted_text, phi_entity_count, fhir_patient_id "
                + "FROM message WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat((String) row.get("redacted_text")).contains("<PERSON>", "<MRN>", "<PHONE_NUMBER>");
        PHI.forEach(v -> assertThat((String) row.get("redacted_text")).doesNotContain(v));
        assertThat(row.get("phi_entity_count")).isEqualTo(5);
        // FHIR patient lookup on intake: the account's MRN resolved to the mock's Patient.
        assertThat(row.get("fhir_patient_id")).isEqualTo("pat-482913");

        ConsumerRecord<String, String> record = awaitRecord("messages.redacted", id.toString());
        assertThat(record.key()).isEqualTo(id.toString());
        JsonNode published = json.readTree(record.value());
        assertThat(published.path("redacted_text").asText()).isEqualTo(row.get("redacted_text"));
        PHI.forEach(v -> assertThat(record.value()).doesNotContain(v));

        // The PHI call is reported on service.events with the fixed schema.
        JsonNode event = json.readTree(awaitRecord("service.events", id.toString(),
                r -> r.value().contains("phi-detection")).value());
        assertThat(iterator(event.fieldNames())).containsExactlyInAnyOrder("event_id", "message_id", "service", "status",
                "latency_ms", "timestamp");
        assertThat(event.path("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void redactionFailureIsRecordedAndNothingIsPublished() throws Exception {
        PHI_SERVICE.failing.set(true);
        UUID id;
        try {
            id = submit("james.carter", "Please call James Carter at (650) 555-0187.");
        } finally {
            PHI_SERVICE.failing.set(false);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM message WHERE id = ?", String.class, id))
                .isEqualTo("REDACTION_FAILED");
        assertThat(jdbc.queryForObject("SELECT redacted_text FROM message WHERE id = ?", String.class, id)).isNull();
        awaitRecord("service.events", id.toString(), r -> r.value().contains("\"FAILED\""));
        assertThat(records("messages.redacted", Duration.ofSeconds(3))).noneMatch(r -> id.toString().equals(r.key()));
    }

    // ---------- Kafka result processing --------------------------------------------------------

    @Test
    void resultsArePersistedIdempotentlyAndCompleteTheMessage() {
        UUID id = submit("priya.natarajan", "Can I get my metformin refilled before Friday?");
        publishAllResults(id, "routine", "medication_refill");

        await().atMost(Duration.ofSeconds(30)).until(() -> "PROCESSED".equals(status(id)));
        Map<String, Object> row = jdbc.queryForMap("SELECT category, urgency_label, urgency_rank FROM message WHERE id = ?", id);
        assertThat(row).containsEntry("category", "medication_refill").containsEntry("urgency_label", "routine");
        assertThat(((Number) row.get("urgency_rank")).intValue()).isEqualTo(2);

        // Redelivery (at-least-once): the unique (message_id, service_name) constraint absorbs it.
        publishResult(id, "urgency", Map.of("label", "urgent", "flags", List.of()));
        publishResult(id, "routing", Map.of("category", "billing", "confidence", 0.5, "scores", Map.of()));
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_result WHERE message_id = ?", Integer.class, id))
                    .isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT urgency_label FROM message WHERE id = ?", String.class, id))
                    .isEqualTo("routine");
        });
    }

    @Test
    void invalidResultIsDeadLetteredWithoutStackTrace() {
        UUID id = UUID.randomUUID();
        kafka.send("results.urgency", id.toString(), "{\"message_id\":\"" + id + "\",\"service\":\"urgency\","
                + "\"processed_at\":\"2026-10-20T10:00:00Z\",\"result\":{\"label\":\"panic\"}}");

        ConsumerRecord<String, String> dead = awaitRecord("results.urgency.dlq", id.toString());
        List<String> headers = StreamSupport.stream(dead.headers().spliterator(), false).map(h -> h.key()).toList();
        assertThat(headers).contains("kafka_dlt-exception-fqcn").doesNotContain("kafka_dlt-exception-stacktrace");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_result WHERE message_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void clinicianInboxPutsUrgentFirst() {
        UUID routine = submit("robert.chen", "Could I reschedule my physical to next month?");
        UUID urgent = submit("aisha.johnson", "I have crushing chest pain right now.");
        publishAllResults(routine, "routine", "scheduling");
        publishAllResults(urgent, "urgent", "symptoms");
        await().atMost(Duration.ofSeconds(30)).until(() -> "PROCESSED".equals(status(urgent)) && "PROCESSED".equals(status(routine)));

        JsonNode inbox = getJson("/api/clinician/inbox?view=UNREVIEWED", login("dr.alvarez"));
        List<String> order = new ArrayList<>();
        inbox.path("items").forEach(i -> order.add(i.path("id").asText()));
        assertThat(order).contains(urgent.toString(), routine.toString());
        assertThat(order.indexOf(urgent.toString())).isLessThan(order.indexOf(routine.toString()));
    }

    // ---------- FHIR R4 -------------------------------------------------------------------------

    @Test
    void processedMessageIsWrittenBackAsRedactedCommunication() throws Exception {
        UUID id = submit("maria.lopez", ORIGINAL);
        String redacted = jdbc.queryForObject("SELECT redacted_text FROM message WHERE id = ?", String.class, id);
        publishResult(id, "urgency", Map.of("label", "urgent", "flags", List.of(Map.of("phrase", "chest pain"))));
        publishResult(id, "routing", Map.of("category", "symptoms", "confidence", 0.87, "scores", Map.of()));

        await().atMost(Duration.ofSeconds(30)).until(() -> "WRITTEN".equals(
                jdbc.queryForObject("SELECT fhir_status FROM message WHERE id = ?", String.class, id)));
        JsonNode bundle = fhirGet("/Communication?identifier=" + URLEncoder.encode("urn:ietf:rfc:3986|urn:uuid:" + id,
                StandardCharsets.UTF_8));
        assertThat(bundle.path("total").asInt()).isEqualTo(1);
        JsonNode communication = bundle.path("entry").get(0).path("resource");
        String raw = communication.toString();

        assertThat(communication.path("payload")).hasSize(1);
        assertThat(communication.path("payload").get(0).path("contentString").asText()).isEqualTo(redacted);
        PHI.forEach(v -> assertThat(raw).doesNotContain(v));
        assertThat(raw).doesNotContain(ORIGINAL);
        assertThat(communication.path("subject").path("reference").asText()).isEqualTo("Patient/pat-482913");
        assertThat(communication.path("status").asText()).isEqualTo("completed");
        assertThat(communication.path("category").get(0).path("coding").get(0).path("code").asText()).isEqualTo("symptoms");
        assertThat(raw).contains("ai-urgency-flag", "\"valueCode\":\"urgent\"");
        assertThat(jdbc.queryForObject("SELECT fhir_communication_id FROM message WHERE id = ?", String.class, id))
                .isEqualTo(communication.path("id").asText());

        // Re-triggering (duplicate result + sweeper) never creates a second Communication.
        jdbc.update("UPDATE message SET fhir_status = NULL WHERE id = ?", id);
        publishResult(id, "routing", Map.of("category", "symptoms", "confidence", 0.87, "scores", Map.of()));
        await().atMost(Duration.ofSeconds(30)).until(() -> "WRITTEN".equals(
                jdbc.queryForObject("SELECT fhir_status FROM message WHERE id = ?", String.class, id)));
        assertThat(fhirGet("/Communication?identifier=urn:uuid:" + id).path("total").asInt()).isEqualTo(1);
        assertThat(fhirHealth().path("deduplicated").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(fhirHealth().path("rejected_phi").asInt()).isZero();
    }

    @Test
    void fhirCallsAreReportedAsServiceEvents() {
        UUID id = submit("david.nguyen", "Is my blood test back yet?");
        publishAllResults(id, "routine", "test_results");
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM service_event WHERE message_id = ? AND service = 'fhir' AND status = 'SUCCESS'",
                Integer.class, id) >= 1);
    }

    // ---------- helpers -------------------------------------------------------------------------

    private String login(String username) {
        ResponseEntity<JsonNode> res = http.postForEntity("/api/auth/login",
                Map.of("username", username, "password", PASSWORD), JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody().path("token").asText();
    }

    private UUID submit(String username, String text) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login(username));
        ResponseEntity<JsonNode> res = http.exchange("/api/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("text", text), headers), JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString(res.getBody().path("id").asText());
    }

    private JsonNode getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<JsonNode> res = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody();
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM message WHERE id = ?", String.class, id);
    }

    private void publishAllResults(UUID id, String urgency, String category) {
        publishResult(id, "urgency", Map.of("label", urgency, "flags", List.of()));
        publishResult(id, "routing", Map.of("category", category, "confidence", 0.9, "scores", Map.of()));
        publishResult(id, "summarization", Map.of("summary", "stub summary", "sentences", List.of()));
        publishResult(id, "search", Map.of("neighbors", List.of()));
    }

    private void publishResult(UUID id, String service, Map<String, Object> result) {
        try {
            kafka.send("results." + service, id.toString(), json.writeValueAsString(Map.of("message_id", id.toString(),
                    "service", service, "model_version", "it-stub", "processed_at", OffsetDateTime.now().toString(),
                    "result", result))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        return awaitRecord(topic, key, r -> true);
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key,
                                                       java.util.function.Predicate<ConsumerRecord<String, String>> match) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            records(topic, Duration.ofSeconds(2)).stream().filter(r -> key.equals(r.key())).filter(match)
                    .findFirst().ifPresent(found::add);
            return !found.isEmpty();
        });
        return found.get(0);
    }

    /** Everything currently on the topic, read with a throwaway consumer group. */
    private static List<ConsumerRecord<String, String>> records(String topic, Duration pollFor) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + pollFor.toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach(out::add);
            }
        }
        return out;
    }

    private JsonNode fhirGet(String path) throws Exception {
        URI uri = URI.create("http://" + FHIR.getHost() + ":" + FHIR.getMappedPort(8000) + "/fhir" + path);
        HttpResponse<String> res = fhirHttp.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(res.body());
    }

    private JsonNode fhirHealth() throws Exception {
        URI uri = URI.create("http://" + FHIR.getHost() + ":" + FHIR.getMappedPort(8000) + "/health");
        return json.readTree(fhirHttp.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private static List<String> iterator(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
