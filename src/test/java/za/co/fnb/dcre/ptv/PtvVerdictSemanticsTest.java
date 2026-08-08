package za.co.fnb.dcre.ptv;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import za.co.fnb.dcre.ptv.service.ValidationService;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The payments verdict semantics, end to end through the real job.
 *
 * <p>[SYNTHETIC-CONTRACT R-35] These are CTV's ENDO-mode semantics (A-20 draft),
 * now unconditional rather than switched on {@code dcre.flow-dc=false}: PAI creates
 * absent accounts downstream (create-if-absent), so an unknown account and a known
 * account with a NULL cap both PASS. An EXISTING over-cap account still fails.
 *
 * <p>Note what is NOT here and cannot be: CTV's ENDO suites have to aim a CLOSED
 * PORT at {@code dcre.ctv.mandates-db-url} to prove nothing opens a dcre_man
 * connection, because {@code MandatesDatasourceConfig} is in every one of its
 * contexts. PTV needs no such trap, because no bean in this context can reach the
 * mandates database at all. {@code PayFlowOnlyTest} asserts that structurally.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange"})
class PtvVerdictSemanticsTest {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    @Autowired
    Job ptvJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void unknownAndNullCapAccountsPassThroughButOverCapStillFails() throws Exception {
        UUID arrival = UUID.randomUUID();
        seedReferenceData();
        seedSpine(arrival);

        Logger validationLogger = (Logger) LoggerFactory.getLogger(ValidationService.class);
        ListAppender<ILoggingEvent> warns = new ListAppender<>();
        warns.start();
        validationLogger.addAppender(warns);

        JobExecution run = jobOperator.start(ptvJob, new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true)
                .toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals("BUSINESS_PARTIAL", run.getExecutionContext().getString("ptvVerdict"),
                "the over-cap entry must still fail");

        Map<Integer, String> expected = Map.of(
                1, "PASS",                    // unknown account: PAI creates it downstream
                2, "PASS",                    // second unknown account, same pass-through
                3, "PASS",                    // known, under cap
                4, "PASS",                    // known, NULL cap: cap check post-init
                5, "FAIL_EXCEEDS_RF_BALANCE", // existing over-cap account still fails
                6, "FAIL_ACCOUNT_NOT_ACTIVE"  // existing but SUSPENDED
        );
        Map<Integer, String> actual = new HashMap<>();
        jdbc.query("SELECT sequence, outcome FROM validation_log WHERE arrival_id=?",
                r -> {
                    actual.put(r.getInt(1), r.getString(2));
                }, arrival);
        assertEquals(expected, actual, "payments verdicts per A-20 draft [SYNTHETIC-CONTRACT R-35]");

        // R-38 exclusion visibility: exactly two FAIL verdicts -> exactly two WARNs at decision time.
        List<String> exclusionWarns = warns.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("excluded stage=PTV"))
                .sorted()
                .toList();
        assertEquals(2, exclusionWarns.size(), "one WARN per FAIL verdict (R-38)");
        assertEquals(List.of(
                        "excluded stage=PTV arrival=" + arrival + " seq=5 e2e=ENDO-E2E-00005"
                                + " reason=PTV_FAIL_EXCEEDS_RF_BALANCE",
                        "excluded stage=PTV arrival=" + arrival + " seq=6 e2e=ENDO-E2E-00006"
                                + " reason=PTV_FAIL_ACCOUNT_NOT_ACTIVE"),
                exclusionWarns, "uniform R-38 WARN shape, stage token is PTV not CTV");
        validationLogger.detachAppender(warns);
    }

    void seedReferenceData() {
        // Minimal PAI-shaped account read model (PTV maps only these columns).
        // [SYNTHETIC-CONTRACT R-35] Deliberately WITHOUT the fixture seed's
        // product/cap CHECK constraint: the NULL-cap row models exactly the
        // pre-init account state that constraint forbids for settled rows.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS account (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    account_number VARCHAR(34) NOT NULL UNIQUE,
                    product_code VARCHAR(8) NOT NULL,
                    balance DECIMAL(18,2) NULL,
                    max_credit_limit DECIMAL(18,2) NULL,
                    process_status VARCHAR(16) NOT NULL)""");
        upsertAccount("62000000000001", "FNBRF", new BigDecimal("5000.00"), null, "ACTIVE");
        upsertAccount("62000000000002", "FNBCC", null, null, "ACTIVE");
        upsertAccount("62000000000003", "FNBRF", new BigDecimal("100.00"), null, "ACTIVE");
        upsertAccount("62000000000004", "FNBRF", new BigDecimal("5000.00"), null, "SUSPENDED");
    }

    void upsertAccount(String number, String productCode, BigDecimal balance, BigDecimal limit,
                       String status) {
        jdbc.update("""
                UPSERT INTO account (account_number, product_code, balance, max_credit_limit, process_status)
                VALUES (?,?,?,?,?)""", number, productCode, balance, limit, status);
    }

    void seedSpine(UUID arrival) {
        // PRR owns the spine in production (R-04); the test stands it up here with
        // the columns PTV actually reads. No mandate_ref: PTV maps none.
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
        jdbc.update("UPSERT INTO tx_header (arrival_id, tx_count, initg_pty, business_date) VALUES (?,?,?,?)",
                arrival, 6, "FNBEN01", "20260711");
        insertEntry(arrival, 1, "ENDO-E2E-00001", "62999999999901", "150.00"); // unknown
        insertEntry(arrival, 2, "ENDO-E2E-00002", "62999999999902", "220.00"); // unknown
        insertEntry(arrival, 3, "ENDO-E2E-00003", "62000000000001", "100.00"); // under cap
        insertEntry(arrival, 4, "ENDO-E2E-00004", "62000000000002", "300.00"); // NULL cap
        insertEntry(arrival, 5, "ENDO-E2E-00005", "62000000000003", "250.00"); // over cap
        insertEntry(arrival, 6, "ENDO-E2E-00006", "62000000000004", "100.00"); // suspended
    }

    void insertEntry(UUID arrival, int sequence, String e2e, String account, String amount) {
        jdbc.update("""
                UPSERT INTO tx_entry (arrival_id, sequence, e2e, creditor_account, contract_ref, amount)
                VALUES (?,?,?,?,NULL,?)""",
                arrival, sequence, e2e, account, new BigDecimal(amount));
    }
}
