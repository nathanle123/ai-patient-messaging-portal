package edu.cmpe272.portal.auth;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import edu.cmpe272.portal.user.AppUser;
import edu.cmpe272.portal.user.AppUserRepository;
import edu.cmpe272.portal.user.Role;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates demo accounts at startup: one patient per row of {@code fake_phi.csv}, plus fixed clinician
 * and admin accounts. Idempotent (existing usernames are skipped). All accounts share
 * {@code DEMO_USER_PASSWORD}, which comes from {@code .env} and is never committed.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** Fictional staff accounts. */
    static final List<StaffAccount> STAFF = List.of(
            new StaffAccount("dr.alvarez", "Dr. Elena Alvarez", Role.CLINICIAN),
            new StaffAccount("np.okafor", "Chidi Okafor, NP", Role.CLINICIAN),
            new StaffAccount("admin", "Portal Administrator", Role.ADMIN));

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SeedProperties properties;

    public DemoUserSeeder(AppUserRepository users, PasswordEncoder passwordEncoder, SeedProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        String password = properties.demoPassword();
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("app.seed.enabled=true requires DEMO_USER_PASSWORD of at least "
                    + MIN_PASSWORD_LENGTH + " characters");
        }
        // One hash shared by all demo accounts: BCrypt is deliberately slow, and per-user hashes add seconds to startup.
        String hash = passwordEncoder.encode(password);
        int created = 0;
        for (StaffAccount staff : STAFF) {
            created += createIfAbsent(staff.username(), hash, staff.role(), staff.displayName(), null);
        }
        for (FakePhiRow row : readFakePhi(properties.fakePhiPath())) {
            created += createIfAbsent(usernameFor(row.name()), hash, Role.PATIENT, row.name(), row.mrn());
        }
        log.info("Demo user seeding complete: {} created, {} total", created, users.count());
    }

    private int createIfAbsent(String username, String hash, Role role, String displayName, String mrn) {
        if (users.existsByUsername(username)) {
            return 0;
        }
        users.save(new AppUser(username, hash, role, displayName, mrn));
        return 1;
    }

    static List<FakePhiRow> readFakePhi(Path path) throws IOException {
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("fake PHI file not readable: " + path);
        }
        CsvMapper mapper = new CsvMapper();
        CsvSchema schema = CsvSchema.emptySchema().withHeader();
        try (MappingIterator<Map<String, String>> it =
                     mapper.readerForMapOf(String.class).with(schema).readValues(path.toFile())) {
            return it.readAll().stream()
                    .map(m -> new FakePhiRow(m.get("name").trim(), m.get("mrn").trim()))
                    .toList();
        }
    }

    /**
     * {@code "Maria Lopez"} becomes {@code maria.lopez}; middle names and suffixes are dropped
     * ({@code "Anthony J. Russo Jr."} becomes {@code anthony.russo}); hyphens are kept.
     */
    static String usernameFor(String fullName) {
        List<String> parts = java.util.Arrays.stream(fullName.trim().split("\\s+"))
                .map(p -> p.toLowerCase(Locale.ROOT).replaceAll("[^a-z-]", ""))
                .filter(p -> !p.isEmpty())
                .filter(p -> !List.of("jr", "sr", "ii", "iii", "iv").contains(p))
                .toList();
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Cannot derive a username from an empty name");
        }
        return parts.size() == 1 ? parts.get(0) : parts.get(0) + "." + parts.get(parts.size() - 1);
    }

    record StaffAccount(String username, String displayName, Role role) {
    }

    record FakePhiRow(String name, String mrn) {
    }
}
