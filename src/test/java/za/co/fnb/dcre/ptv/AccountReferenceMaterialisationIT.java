package za.co.fnb.dcre.ptv;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.ptv.data.repo.AccountReferenceNotMaterialisedException;
import za.co.fnb.dcre.ptv.service.ArtifactFixture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCRUM-107 repair 2, all three states of the account reference store, in the order a real
 * environment passes through them.
 *
 * <ol>
 *   <li>the artifact is ABSENT: the LOADER job fails, naming the directory it looked for;
 *   <li>the table was NEVER LOADED: the validation job fails technically, naming the
 *       materialisation step, and writes no verdict at all;
 *   <li>a load record applied ZERO rows: the same halt, naming the dataset that applied
 *       nothing, because "nobody ran the loader" and "the loader ran and found nothing for
 *       this context" need different words to an operator;
 *   <li>the artifact is staged and loaded: an account it does not carry is now a BUSINESS
 *       rejection, {@code FAIL_ACCOUNT_NOT_FOUND}, and the job SUCCEEDS.
 * </ol>
 *
 * <p>State 4 is the control for states 2 and 3 and states 2 and 3 are the control for state
 * 4. Without the pairing, "no FAIL_ACCOUNT_NOT_FOUND rows were written" is satisfied by a
 * job that never got as far as verdicting anything, which is exactly the trap: the defect
 * being fixed here reported an arrival's worth of FAIL_ACCOUNT_NOT_FOUND for a deployment
 * step that never ran, and nothing distinguished it from a genuinely unknown account.
 *
 * <p>The artifact root is a fresh temp directory that starts EMPTY, and state 4 STAGES the
 * committed source into it exactly as the infra deploy step stages it into the exchange
 * root. That makes the ordering a real sequence rather than four independent fixtures, and
 * it means the rows loaded in state 4 are the published bytes, not a fixture this suite
 * wrote.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountReferenceMaterialisationIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    /** Empty at class start: state 1 depends on there being no artifact here yet. */
    static final Path ARTIFACT_ROOT = tempDirectory("ptv-materialisation-artifact");

    static final Path EXCHANGE = tempDirectory("ptv-materialisation-exchange");

    /** An account number the committed artifact does not carry. */
    private static final String UNKNOWN_ACCOUNT = "62999999999901";

    /** An account number the committed artifact DOES carry, FNBRF, balance 100000.00. */
    private static final String KNOWN_ACCOUNT = "62085856711390458";

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.exchange-root", EXCHANGE::toString);
        registry.add("dcre.ptv.reference.account.root", ARTIFACT_ROOT::toString);
    }

    @Autowired
    Job ptvJob;

    @Autowired
    Job ptvAccountReferenceLoadJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    // ---- state 1: the artifact is absent --------------------------------------------

    @Test
    @Order(1)
    void anAbsentArtifactFailsTheLoaderJobAndNamesTheDirectoryItLookedFor() throws Exception {
        assertTrue(!Files.exists(ARTIFACT_ROOT.resolve(ArtifactFixture.VERSION)),
                "control: the artifact genuinely is not staged yet, looked at: "
                        + ARTIFACT_ROOT.resolve(ArtifactFixture.VERSION).toAbsolutePath());

        JobExecution run = jobOperator.start(ptvAccountReferenceLoadJob,
                new JobParametersBuilder().addString("run.id", "absent", true).toJobParameters());

        assertEquals(BatchStatus.FAILED, run.getStatus(),
                "nothing may swallow the loader's failure: a load that finds no artifact and"
                        + " completes is the fail-open this whole contract forbids");
        String failure = failureText(run);
        assertTrue(failure.contains("account reference artifact directory is absent"),
                "the message must name THIS arm rather than merely being non-empty, was: " + failure);
        assertTrue(failure.contains(ArtifactFixture.VERSION),
                "and the dataset version it looked for, was: " + failure);
        assertTrue(failure.contains(ARTIFACT_ROOT.toAbsolutePath().toString()),
                "and the absolute path, which is the whole diagnosis when a relative default"
                        + " resolves differently in a pod than in a checkout, was: " + failure);
    }

    // ---- state 2: the table was never loaded ----------------------------------------

    @Test
    @Order(2)
    void anUnloadedDatabaseHaltsValidationAndWritesNoAccountNotFoundVerdict() throws Exception {
        // POSITIVE CONTROL, first, and it is the reason this test proves anything: if the
        // artifact had happened to load, every assertion below would pass while testing
        // nothing at all.
        assertEquals(0, queryInt("SELECT count(*) FROM account"),
                "control: the account table is genuinely EMPTY at this moment");
        assertEquals(0, queryInt("SELECT count(*) FROM account_reference_load"),
                "control: and no load has ever been recorded, so this is 'never loaded' and"
                        + " not 'loaded and applied nothing'");

        UUID arrival = seedArrival(UNKNOWN_ACCOUNT);
        JobExecution run = start(arrival);

        assertEquals(BatchStatus.FAILED, run.getStatus(),
                "an unmaterialised reference store is a TECHNICAL failure: the job halts for AGT"
                        + " to relaunch once the materialisation step has actually run");
        assertTrue(raised(run, AccountReferenceNotMaterialisedException.class),
                "and it is this specific failure, not any exception a job can die of, was: "
                        + failureText(run));
        String failure = failureText(run);
        assertTrue(failure.contains("NEVER been loaded"),
                "the message distinguishes the two empty states, was: " + failure);
        assertTrue(failure.contains("ptvAccountReferenceLoadJob"),
                "and NAMES the loader run an operator has to launch, was: " + failure);
        assertTrue(failure.contains("cutover-v1.sh") && failure.contains("env-reset.sh"),
                "and the infra step that stages the artifact, was: " + failure);

        // THE POINT OF THE WHOLE WAVE. The defect being fixed reported this arrival as a
        // file of business rejections. Not one may be written.
        assertEquals(0, queryInt("SELECT count(*) FROM validation_log WHERE arrival_id='"
                        + arrival + "' AND outcome='FAIL_ACCOUNT_NOT_FOUND'"),
                "a deployment step that did not run must never be reported as a business"
                        + " rejection of every payment in the arrival");
        assertEquals(0, queryInt("SELECT count(*) FROM validation_log WHERE arrival_id='" + arrival + "'"),
                "and no verdict of any kind: the run never had reference data to judge against");
        assertEquals(0, seamFiles(),
                "no outcome seam either: AGT reads that file as a BUSINESS verdict");
    }

    // ---- state 3: a load record that applied nothing --------------------------------

    @Test
    @Order(3)
    void aLoadThatAppliedZeroRowsHaltsValidationAndNamesTheDataset() throws Exception {
        jdbc.update("""
                INSERT INTO account_reference_load (dataset_version, schema_version, source_id,
                        effective_ts, publication_ts, row_count, checksum, applied_row_count)
                VALUES ('2026.08.09-000',1,'fixture:test/empty-projection', now(), now(),
                        110, 'fixture', 0)""");
        assertEquals(0, queryInt("SELECT count(*) FROM account"),
                "control: the table is still empty, so the only thing that changed since the"
                        + " previous case is that a load record now claims to have run");
        assertEquals(0, queryInt("SELECT applied_row_count FROM account_reference_load"
                        + " ORDER BY created_at DESC LIMIT 1"),
                "control: and the newest record is the zero-row one");

        UUID arrival = seedArrival(UNKNOWN_ACCOUNT);
        JobExecution run = start(arrival);

        assertEquals(BatchStatus.FAILED, run.getStatus());
        assertTrue(raised(run, AccountReferenceNotMaterialisedException.class),
                "was: " + failureText(run));
        String failure = failureText(run);
        assertTrue(failure.contains("applied ZERO rows"),
                "this is a DIFFERENT sentence from the never-loaded one, because the operator"
                        + " action differs: re-run a loader that ran, was: " + failure);
        assertTrue(failure.contains("2026.08.09-000"),
                "and it names the dataset that applied nothing, was: " + failure);
        assertTrue(failure.contains("ptvAccountReferenceLoadJob"),
                "and still names the materialisation step, was: " + failure);
        assertEquals(0, queryInt("SELECT count(*) FROM validation_log WHERE arrival_id='"
                        + arrival + "' AND outcome='FAIL_ACCOUNT_NOT_FOUND'"),
                "still not one business rejection from a table nothing filled");
    }

    // ---- state 4: staged, loaded, and a genuine no-match -----------------------------

    @Test
    @Order(4)
    void onceStagedAndLoadedAnAccountTheArtifactDoesNotCarryIsABusinessRejection() throws Exception {
        stageArtifact();

        JobExecution load = jobOperator.start(ptvAccountReferenceLoadJob,
                new JobParametersBuilder().addString("run.id", "staged", true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, load.getStatus(),
                "the staged copy is byte-identical to the committed source, so it must load");
        assertEquals(10, queryInt("SELECT count(*) FROM account"),
                "control: the store is genuinely populated now");
        assertEquals(10, queryInt("SELECT applied_row_count FROM account_reference_load"
                        + " ORDER BY created_at DESC LIMIT 1"),
                "control: and the newest load record says so");

        UUID arrival = seedArrival(UNKNOWN_ACCOUNT, KNOWN_ACCOUNT);
        JobExecution run = start(arrival);

        assertEquals(BatchStatus.COMPLETED, run.getStatus(),
                "a populated store with no row for one account is BUSINESS, so the job succeeds"
                        + " and reports its verdicts");
        assertEquals("FAIL_ACCOUNT_NOT_FOUND", verdict(arrival, 1),
                "the same absent account that halted the run in states 2 and 3 is a rejection"
                        + " here, and the ONLY difference is that the store was loaded");
        assertEquals("PASS", verdict(arrival, 2),
                "control: an account the artifact DOES carry passes, so the rejection above is"
                        + " about that account and not about the whole read");
        assertEquals(ArtifactFixture.VERSION,
                run.getExecutionContext().getString("accountDatasetVersion"),
                "the run RECORDS which dataset it validated against: the account table is"
                        + " replaced wholesale by each load, so it cannot answer this later");
    }

    // ---- helpers ---------------------------------------------------------------------

    /** Exactly what the infra deploy step does: copy the validated source into the root. */
    private static void stageArtifact() throws IOException {
        Path source = ArtifactFixture.REAL_ROOT.resolve(ArtifactFixture.VERSION);
        Path target = Files.createDirectories(ARTIFACT_ROOT.resolve(ArtifactFixture.VERSION));
        for (String file : List.of("manifest.properties", "account.csv")) {
            Files.copy(source.resolve(file), target.resolve(file),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private UUID seedArrival(String... accounts) {
        PtvTestTables.create(jdbc);
        UUID arrival = UUID.randomUUID();
        for (int i = 0; i < accounts.length; i++) {
            PtvTestTables.insertEntry(jdbc, arrival, i + 1,
                    "E2E-MAT-" + arrival.toString().substring(0, 8) + "-" + (i + 1),
                    accounts[i], null, "100.00");
        }
        PtvTestTables.insertHeader(jdbc, arrival, accounts.length, "FNBEN01");
        return arrival;
    }

    private JobExecution start(UUID arrival) throws Exception {
        return jobOperator.start(ptvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());
    }

    private String verdict(UUID arrival, int sequence) {
        return jdbc.queryForObject(
                "SELECT outcome FROM validation_log WHERE arrival_id=? AND sequence=?",
                String.class, arrival, sequence);
    }

    private int queryInt(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    /** Every failure the run recorded, causes included, as one greppable string. */
    private static String failureText(JobExecution run) {
        StringBuilder text = new StringBuilder();
        for (Throwable failure : run.getAllFailureExceptions()) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                text.append(current.getClass().getSimpleName()).append(": ")
                        .append(current.getMessage()).append(" | ");
                if (current.getCause() == current) {
                    break;
                }
            }
        }
        return text.toString();
    }

    private static boolean raised(JobExecution run, Class<? extends Throwable> type) {
        for (Throwable failure : run.getAllFailureExceptions()) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (type.isInstance(current)) {
                    return true;
                }
                if (current.getCause() == current) {
                    break;
                }
            }
        }
        return false;
    }

    private static long seamFiles() {
        Path outcomes = EXCHANGE.resolve("outcomes");
        if (!Files.isDirectory(outcomes)) {
            return 0;
        }
        try (var files = Files.list(outcomes)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path tempDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
