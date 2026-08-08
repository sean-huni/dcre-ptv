package za.co.fnb.dcre.ptv;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import za.co.fnb.dcre.ptv.data.repo.ReferenceSnapshotDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCRUM-107 repair 1, technical arm at JOB level: when the account reference store
 * cannot be read at all, the job HALTS. It does not verdict.
 *
 * <p>The fixture is the state {@code dcre_pay} is actually in today: the spine
 * relations exist, and {@code account} does not, because no changelog in the payments
 * family creates it and the decision about where the account master lives is open.
 * Before this repair that state produced a file of PASS verdicts, which is the worst
 * available answer: a fail-closed control reporting success while blind. The
 * assertions below are deliberately about ABSENCE of verdicts as much as about the
 * failure, because "halted" and "verdicted everything" are the two outcomes that must
 * never be confused.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class AccountStoreUnavailableIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    /** Absolute and fresh per class, so "no seam file" cannot be a stale-directory artefact. */
    static final Path EXCHANGE = freshExchangeRoot();

    static {
        CRDB.start();
    }

    static Path freshExchangeRoot() {
        try {
            return Files.createTempDirectory("ptv-unavailable-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.exchange-root", EXCHANGE::toString);
    }

    @Autowired
    Job ptvJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Both tests count seam files, and JUnit guarantees no order between them, so each
     * starts from an empty outcomes directory rather than trusting it to be empty.
     */
    @BeforeEach
    void clearOutcomes() throws IOException {
        Path outcomes = EXCHANGE.resolve("outcomes");
        if (!Files.isDirectory(outcomes)) {
            return;
        }
        try (Stream<Path> files = Files.list(outcomes)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void anAbsentAccountRelationHaltsTheJobAndWritesNoVerdict() throws Exception {
        UUID arrival = UUID.randomUUID();
        seedSpineOnly(arrival);
        jdbc.execute("DROP TABLE IF EXISTS account");

        Logger daoLogger = (Logger) LoggerFactory.getLogger(ReferenceSnapshotDao.class);
        ListAppender<ILoggingEvent> errors = new ListAppender<>();
        errors.start();
        daoLogger.addAppender(errors);

        JobExecution run = jobOperator.start(ptvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());

        assertEquals(BatchStatus.FAILED, run.getStatus(),
                "an unreadable reference store is a technical failure, so the job halts for AGT"
                        + " to relaunch; it is never a business verdict on the payments");
        assertEquals(0, jdbc.queryForObject(
                        "SELECT count(*) FROM validation_log WHERE arrival_id=?", Integer.class, arrival),
                "not one verdict may be written from a read that did not happen");

        List<String> technical = errors.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("reference-store-unavailable"))
                .toList();
        assertTrue(!technical.isEmpty(),
                "the operator must be able to tell an outage from a rejection in the log,"
                        + " ERRORs seen: " + errors.list);
        assertTrue(technical.getFirst().contains("relation=account"),
                "the ERROR names the relation, was: " + technical.getFirst());
        assertEquals(0, seamFiles(),
                "no outcome seam may be written: AGT reads that file as a BUSINESS verdict, and"
                        + " the default it would carry here is BUSINESS_ACCEPTED");
        daoLogger.detachAppender(errors);
    }

    @Test
    void theSameFixtureWithTheRelationPresentVerdictsNormally() throws Exception {
        // Control (rule: a broken harness and a broken subject look identical without one).
        // Same spine, same job, the ONLY difference is that the reference store is readable.
        UUID arrival = UUID.randomUUID();
        seedSpineOnly(arrival);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS account (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    account_number VARCHAR(34) NOT NULL UNIQUE,
                    product_code VARCHAR(8) NOT NULL,
                    balance DECIMAL(18,2) NULL,
                    max_credit_limit DECIMAL(18,2) NULL,
                    process_status VARCHAR(16) NOT NULL)""");
        jdbc.update("""
                UPSERT INTO account (account_number, product_code, balance, max_credit_limit, process_status)
                VALUES (?,'FNBRF',?,NULL,'ACTIVE')""", "62880000000001", new BigDecimal("5000.00"));

        JobExecution run = jobOperator.start(ptvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());

        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("PASS", jdbc.queryForObject(
                "SELECT outcome FROM validation_log WHERE arrival_id=? AND sequence=1",
                String.class, arrival));
        assertEquals(1, seamFiles(),
                "control for the absence assertion above: a COMPLETED run DOES write a seam,"
                        + " so counting zero there is a fact about the failure, not about the harness");
        jdbc.execute("DROP TABLE IF EXISTS account");
    }

    private static long seamFiles() {
        Path outcomes = EXCHANGE.resolve("outcomes");
        if (!Files.isDirectory(outcomes)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(outcomes)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void seedSpineOnly(UUID arrival) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tx_header (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL UNIQUE,
                    tx_count INT NOT NULL,
                    initg_pty VARCHAR(35) NOT NULL,
                    business_date VARCHAR(8) NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tx_entry (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    arrival_id UUID NOT NULL, sequence INT NOT NULL,
                    e2e VARCHAR(35) NOT NULL, creditor_account VARCHAR(23) NOT NULL,
                    contract_ref VARCHAR(14), amount DECIMAL(18,2) NOT NULL,
                    content_hash CHAR(64),
                    UNIQUE (arrival_id, sequence))""");
        jdbc.update("UPSERT INTO tx_header (arrival_id, tx_count, initg_pty, business_date) VALUES (?,1,?,?)",
                arrival, "FNBEN01", PtvTestTables.BUSINESS_DATE);
        jdbc.update("""
                UPSERT INTO tx_entry (arrival_id, sequence, e2e, creditor_account, contract_ref, amount)
                VALUES (?,1,?,?,NULL,?)""",
                arrival, "E2E-UNAVAIL-1", "62880000000001", new BigDecimal("100.00"));
    }
}
