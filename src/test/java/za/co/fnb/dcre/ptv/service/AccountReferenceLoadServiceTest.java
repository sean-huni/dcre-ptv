package za.co.fnb.dcre.ptv.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import za.co.fnb.dcre.ptv.data.model.AccountEntity;
import za.co.fnb.dcre.ptv.domain.AccountReferenceLoadException;
import za.co.fnb.dcre.ptv.domain.AccountReferenceManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader's file-level failure arms, each asserting the SPECIFIC discrepancy rather than
 * that something threw. A test satisfied by any exception passes for every reason a load can
 * die, including the ones that mean the guard under test never ran.
 *
 * <p>No database here on purpose: arms 1 to 4, 6 and 7 all decide before a single row is
 * written, and proving them without a container is what makes each one cheap enough to run
 * on every build. The atomicity arm is the one that genuinely needs a database and lives in
 * {@code AccountReferenceLoadIT}.
 *
 * <p>The applier is a recording stand-in so that a load reaching it is VISIBLE. Several of
 * these tests would pass against a loader that silently applied nothing, which is precisely
 * the defect class this wave removes, so each failing case also asserts the applier was
 * never called.
 */
class AccountReferenceLoadServiceTest {

    /**
     * Records rather than writes. It extends the real applier so the service is wired
     * exactly as production wires it; the superclass's collaborators are null because
     * {@link #apply} is fully overridden and nothing else in the class touches them.
     */
    static final class RecordingApplier extends AccountReferenceApplier {

        private final List<List<AccountEntity>> applications = new ArrayList<>();

        RecordingApplier() {
            super(null, null);
        }

        @Override
        public int apply(AccountReferenceManifest manifest, List<AccountEntity> rows,
                         Long jobExecutionId) {
            applications.add(rows);
            return rows.size();
        }

        List<AccountEntity> onlyApplication() {
            assertEquals(1, applications.size(), "expected exactly one application");
            return applications.getFirst();
        }

        int applicationCount() {
            return applications.size();
        }
    }

    private final RecordingApplier applier = new RecordingApplier();

    private AccountReferenceLoadService serviceFor(Path root, String version, Duration maxAge) {
        return new AccountReferenceLoadService(ArtifactFixture.properties(root, version, maxAge),
                new ManifestReader(), new AccountCsvReader(), new AccountReferenceMapper(), applier);
    }

    // ---- 1. artifact directory absent -------------------------------------------------

    @Test
    void anAbsentArtifactDirectoryFailsAndNeverApplies(@TempDir Path root) {
        AccountReferenceLoadService service = serviceFor(root, ArtifactFixture.VERSION, null);

        AccountReferenceLoadException failure =
                assertThrows(AccountReferenceLoadException.class, () -> service.load(null));

        assertTrue(failure.getMessage().startsWith("account reference artifact directory is absent"),
                "the message must name THIS arm, was: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(ArtifactFixture.VERSION),
                "and the directory it looked for, was: " + failure.getMessage());
        assertEquals(0, applier.applicationCount(),
                "an absent artifact must never reach the database; continuing with whatever is"
                        + " already in the table is the fail-open this contract forbids");
    }

    // ---- 2. checksum mismatch ---------------------------------------------------------

    @Test
    void aChecksumThatDoesNotMatchTheCsvBytesFails(@TempDir Path root) {
        String csv = ArtifactFixture.csv(ArtifactFixture.collections("62000000000001", "100.00"));
        String declared = "0".repeat(64);
        ArtifactFixture.write(root, ArtifactFixture.VERSION, csv, keys ->
                keys.put("checksum.sha256", declared));
        String actual = ArtifactFixture.sha256(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AccountReferenceLoadException failure = assertThrows(AccountReferenceLoadException.class,
                () -> serviceFor(root, ArtifactFixture.VERSION, null).load(null));

        assertTrue(failure.getMessage().startsWith("account reference checksum.sha256 mismatch"),
                "was: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(declared) && failure.getMessage().contains(actual),
                "BOTH digests must be printed or the operator cannot tell which side moved,"
                        + " was: " + failure.getMessage());
        assertEquals(0, applier.applicationCount());
    }

    // ---- 3. unsupported schema version ------------------------------------------------

    @Test
    void anUnsupportedSchemaVersionFailsNamingBothNumbers(@TempDir Path root) {
        ArtifactFixture.write(root, ArtifactFixture.VERSION,
                ArtifactFixture.csv(ArtifactFixture.collections("62000000000001", "100.00")),
                keys -> keys.put("schema.version", "2"));

        AccountReferenceLoadException failure = assertThrows(AccountReferenceLoadException.class,
                () -> serviceFor(root, ArtifactFixture.VERSION, null).load(null));

        assertEquals("account reference schema.version mismatch: this loader supports 1 but the"
                + " manifest declares 2", failure.getMessage());
        assertEquals(0, applier.applicationCount());
    }

    // ---- 4. dataset version mismatch --------------------------------------------------

    @Test
    void aManifestDeclaringAnotherDatasetVersionFails(@TempDir Path root) {
        // The directory is named as this service expects; only the manifest disagrees, so
        // this cannot pass by the directory simply being absent.
        ArtifactFixture.write(root, ArtifactFixture.VERSION,
                ArtifactFixture.csv(ArtifactFixture.collections("62000000000001", "100.00")),
                keys -> keys.put("dataset.version", "2026.08.10-001"));

        AccountReferenceLoadException failure = assertThrows(AccountReferenceLoadException.class,
                () -> serviceFor(root, ArtifactFixture.VERSION, null).load(null));

        assertEquals("account reference dataset.version mismatch: this service expects"
                + " '2026.08.09-001' but the manifest declares '2026.08.10-001'",
                failure.getMessage());
        assertEquals(0, applier.applicationCount());
    }

    // ---- 6. empty projection ----------------------------------------------------------

    @Test
    void anArtifactCarryingNoRowOfThisContextsShapeFails(@TempDir Path root) {
        ArtifactFixture.write(root, ArtifactFixture.VERSION,
                ArtifactFixture.csv(ArtifactFixture.mandates("62999999999901"),
                        ArtifactFixture.mandates("62999999999902")),
                keys -> { });

        AccountReferenceLoadException failure = assertThrows(AccountReferenceLoadException.class,
                () -> serviceFor(root, ArtifactFixture.VERSION, null).load(null));

        assertTrue(failure.getMessage().startsWith(
                        "account reference projection for shape COLLECTIONS is empty"),
                "was: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("carries 2 rows"),
                "the message distinguishes an empty PROJECTION from an empty artifact, was: "
                        + failure.getMessage());
        assertEquals(0, applier.applicationCount(),
                "a loader that finds no rows of its own shape and reports success is the exact"
                        + " defect this wave removes");
    }

    // ---- 7. max-age, inert then armed -------------------------------------------------

    @Test
    void anUnsetMaxAgeProceedsAndSaysSoAtInfo(@TempDir Path root) {
        ArtifactFixture.write(root, ArtifactFixture.VERSION,
                ArtifactFixture.csv(ArtifactFixture.collections("62000000000001", "100.00")),
                keys -> keys.put("publication.ts", "2020-01-01T00:00:00Z"));

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(AccountReferenceLoadService.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        serviceLogger.addAppender(events);

        int applied = serviceFor(root, ArtifactFixture.VERSION, null).load(null);

        assertEquals(1, applied, "an artifact six years old still loads while the gate is inert");
        List<String> inert = events.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("account-reference-freshness-inert"))
                .toList();
        assertEquals(1, inert.size(),
                "the inert check must announce itself on EVERY run, INFOs seen: " + events.list);
        assertTrue(inert.getFirst().contains("pending A-4"),
                "and say what it is waiting for, was: " + inert.getFirst());
        serviceLogger.detachAppender(events);
    }

    @Test
    void anArmedMaxAgeFailsAnArtifactOlderThanIt(@TempDir Path root) {
        ArtifactFixture.write(root, ArtifactFixture.VERSION,
                ArtifactFixture.csv(ArtifactFixture.collections("62000000000001", "100.00")),
                keys -> keys.put("publication.ts", "2020-01-01T00:00:00Z"));

        AccountReferenceLoadException failure = assertThrows(AccountReferenceLoadException.class,
                () -> serviceFor(root, ArtifactFixture.VERSION, Duration.ofHours(24)).load(null));

        assertTrue(failure.getMessage().startsWith("account reference artifact is stale"),
                "was: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("2020-01-01T00:00:00Z")
                        && failure.getMessage().contains("PT24H"),
                "the message carries the publication instant and the configured limit, was: "
                        + failure.getMessage());
        assertEquals(0, applier.applicationCount());
    }

    @Test
    void anArmedMaxAgeAdmitsAFreshArtifact() {
        // Control for the arm above: the same armed gate must not reject everything.
        Path root = freshRoot();
        ArtifactFixture.write(root, ArtifactFixture.VERSION,
                ArtifactFixture.csv(ArtifactFixture.collections("62000000000001", "100.00")),
                keys -> keys.put("publication.ts", java.time.Instant.now().toString()));

        assertEquals(1, serviceFor(root, ArtifactFixture.VERSION, Duration.ofHours(24)).load(null));
    }

    // ---- the projection itself --------------------------------------------------------

    @Test
    void onlyCollectionsRowsAreProjectedAndTheirCellsAreTyped(@TempDir Path root) {
        ArtifactFixture.write(root, ArtifactFixture.VERSION,
                ArtifactFixture.csv(ArtifactFixture.mandates("62999999999901"),
                        ArtifactFixture.collections("62000000000001", "75000.00"),
                        ArtifactFixture.mandates("62999999999902")),
                keys -> { });

        assertEquals(1, serviceFor(root, ArtifactFixture.VERSION, null).load(null));

        AccountEntity only = applier.onlyApplication().getFirst();
        assertEquals(new AccountEntity("62000000000001", "FNBRF", "AAUT", "62000000000001", "CACC",
                        "250205", new java.math.BigDecimal("75000.00"), null, null, 1L, false,
                        false, "ACTIVE", null, "62000000000001", 2L), only,
                "the WHOLE projected row is asserted, not the two fields that look interesting");
    }

    @Test
    void aCsvWhoseHeaderIsNotTheArtifactsHeaderFails(@TempDir Path root) {
        String reordered = ArtifactFixture.header().replace("shape,account_number", "account_number,shape");
        ArtifactFixture.write(root, ArtifactFixture.VERSION, reordered + "\n", keys -> { });

        AccountReferenceLoadException failure = assertThrows(AccountReferenceLoadException.class,
                () -> serviceFor(root, ArtifactFixture.VERSION, null).load(null));

        assertTrue(failure.getMessage().startsWith("account reference csv header mismatch"),
                "was: " + failure.getMessage());
    }

    @Test
    void aRowCountThatDisagreesWithTheManifestFails(@TempDir Path root) {
        ArtifactFixture.write(root, ArtifactFixture.VERSION,
                ArtifactFixture.csv(ArtifactFixture.collections("62000000000001", "100.00")),
                keys -> keys.put("row.count", "2"));

        AccountReferenceLoadException failure = assertThrows(AccountReferenceLoadException.class,
                () -> serviceFor(root, ArtifactFixture.VERSION, null).load(null));

        assertEquals("account reference row.count mismatch: the manifest declares 2 data rows but"
                + " account.csv carries 1", failure.getMessage());
        assertEquals(0, applier.applicationCount());
    }

    @Test
    void aManifestMissingAMandatoryKeyFailsNamingTheKey(@TempDir Path root) {
        ArtifactFixture.write(root, ArtifactFixture.VERSION,
                ArtifactFixture.csv(ArtifactFixture.collections("62000000000001", "100.00")),
                keys -> keys.remove("source.id"));

        AccountReferenceLoadException failure = assertThrows(AccountReferenceLoadException.class,
                () -> serviceFor(root, ArtifactFixture.VERSION, null).load(null));

        assertTrue(failure.getMessage().startsWith(
                        "account reference manifest is missing mandatory key 'source.id'"),
                "was: " + failure.getMessage());
    }

    private static Path freshRoot() {
        try {
            return Files.createTempDirectory("ptv-artifact-fixture");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
