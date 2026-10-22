package edu.cmpe272.portal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoUserSeederTest {

    @Test
    void usernameUsesFirstAndLastNameTokens() {
        assertThat(DemoUserSeeder.usernameFor("Maria Lopez")).isEqualTo("maria.lopez");
        assertThat(DemoUserSeeder.usernameFor("Michael O'Brien")).isEqualTo("michael.obrien");
        assertThat(DemoUserSeeder.usernameFor("Luis Fernando Ortega")).isEqualTo("luis.ortega");
        assertThat(DemoUserSeeder.usernameFor("Anthony J. Russo Jr.")).isEqualTo("anthony.russo");
        assertThat(DemoUserSeeder.usernameFor("Mei-Ling Zhou")).isEqualTo("mei-ling.zhou");
        assertThat(DemoUserSeeder.usernameFor("  Cher  ")).isEqualTo("cher");
    }

    @Test
    void readsNameAndMrnFromCsvWithQuotedAddresses(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("fake_phi.csv");
        Files.writeString(csv, """
                name,dob,phone,email,mrn,address
                Maria Lopez,1962-03-14,(408) 555-0142,maria.lopez@example.com,MRN-482913,"1420 Alder Ln, San Jose, CA 95112"
                Robert Chen,1955-01-30,(510) 555-0164,rchen55@example.com,MRN-274038,"47 Dogwood Dr, Fremont, CA 94536"
                """);

        assertThat(DemoUserSeeder.readFakePhi(csv)).containsExactly(
                new DemoUserSeeder.FakePhiRow("Maria Lopez", "MRN-482913"),
                new DemoUserSeeder.FakePhiRow("Robert Chen", "MRN-274038"));
    }

    @Test
    void repositoryFakePhiFileHasUniqueUsernamesAndMrns() throws IOException {
        Path csv = Path.of("..", "fake_phi.csv");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isReadable(csv), "fake_phi.csv not mounted");

        var rows = DemoUserSeeder.readFakePhi(csv);
        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().map(r -> DemoUserSeeder.usernameFor(r.name()))).doesNotHaveDuplicates();
        assertThat(rows.stream().map(DemoUserSeeder.FakePhiRow::mrn)).doesNotHaveDuplicates();
    }
}
