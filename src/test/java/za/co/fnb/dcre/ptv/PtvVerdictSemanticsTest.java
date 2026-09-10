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
 * <p>SCRUM-107 repair 1 replaced the A-20 draft pass-through on an ABSENT account. An
 * account the reference store does not hold is now {@code FAIL_ACCOUNT_NOT_FOUND}: a
 * rejection carrying its own reason, not a PASS. The old rationale was that PAI would
 * create the account downstream (create-if-absent, R-11), which made the account tier
 * a control that answered PASS in exactly the case it exists to catch.
 *
 * <p>The unset-cap arm is NOT asserted here. That row EXISTS and only its cap is unset,
 * which is a different question and one the open account-model decision owns, but
 * {@code chk_account_product_amount} on the real relation forbids such a row outright, so
 * it is asserted in {@code VerdictChainAccountTierTest} where the chain is DB-free.
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
    void unknownAccountsAreRejectedWhileNullCapPassesAndOverCapStillFails() throws Exception {
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
                1, "FAIL_ACCOUNT_NOT_FOUND",   // absent from the reference store: rejected
                2, "FAIL_ACCOUNT_NOT_FOUND",   // second absent account, same rejection
                3, "PASS",                    // known, under cap
                4, "PASS",                    // known FNBCC, 300.00 under its 5000.00 limit
                5, "FAIL_EXCEEDS_RF_BALANCE", // existing over-cap account still fails
                6, "FAIL_ACCOUNT_NOT_ACTIVE"  // existing but SUSPENDED
        );
        Map<Integer, String> actual = new HashMap<>();
        jdbc.query("SELECT sequence, outcome FROM validation_log WHERE arrival_id=?",
                r -> {
                    actual.put(r.getInt(1), r.getString(2));
                }, arrival);
        assertEquals(expected, actual, "payments account-tier verdicts, fail closed on absence");

        // R-38 exclusion visibility: exactly four FAIL verdicts -> exactly four WARNs at
        // decision time. These WARNs are the BUSINESS channel; the technical channel is the
        // ERROR carrying reference-store-unavailable, asserted in AccountStoreUnavailableIT.
        // Nothing may appear in both.
        List<String> exclusionWarns = warns.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("excluded stage=PTV"))
                .sorted()
                .toList();
        assertEquals(4, exclusionWarns.size(), "one WARN per FAIL verdict (R-38)");
        assertEquals(List.of(
                        "excluded stage=PTV arrival=" + arrival + " seq=1 e2e=ENDO-E2E-00001"
                                + " reason=PTV_FAIL_ACCOUNT_NOT_FOUND",
                        "excluded stage=PTV arrival=" + arrival + " seq=2 e2e=ENDO-E2E-00002"
                                + " reason=PTV_FAIL_ACCOUNT_NOT_FOUND",
                        "excluded stage=PTV arrival=" + arrival + " seq=5 e2e=ENDO-E2E-00005"
                                + " reason=PTV_FAIL_EXCEEDS_RF_BALANCE",
                        "excluded stage=PTV arrival=" + arrival + " seq=6 e2e=ENDO-E2E-00006"
                                + " reason=PTV_FAIL_ACCOUNT_NOT_ACTIVE"),
                exclusionWarns, "uniform R-38 WARN shape, stage token is PTV not CTV");
        validationLogger.detachAppender(warns);
    }

    void seedReferenceData() {
        // The REAL relation, created by 003-account-reference.xml in the full collections
        // shape. This suite used to stand up a six-column imitation of it, which meant
        // every verdict below was read off a table that existed nowhere.
        //
        // Every row here is one dcre_pay.account would actually accept, all three CHECK
        // constraints armed. 62000000000002 was a NULL-cap FNBCC row until SCRUM-107; that
        // row is unrepresentable under chk_account_product_amount, so it is now an FNBCC
        // account UNDER its limit, which keeps what this case was testing (a credit product
        // passing the cap tier) and stops manufacturing a state no loader can write. The
        // unset-cap arm moved to VerdictChainAccountTierTest, where the chain is DB-free.
        upsertAccount("62000000000001", "FNBRF", new BigDecimal("5000.00"), null, "ACTIVE");
        upsertAccount("62000000000002", "FNBCC", null, new BigDecimal("5000.00"), "ACTIVE");
        upsertAccount("62000000000003", "FNBRF", new BigDecimal("100.00"), null, "ACTIVE");
        upsertAccount("62000000000004", "FNBRF", new BigDecimal("5000.00"), null, "SUSPENDED");
        // SCRUM-107 repair 2: the applier writes the load record in the SAME transaction as
        // the rows, so a table holding rows with no record is a state dcre_pay cannot reach.
        // Without it the run would halt as "never loaded" and the verdicts below, which are
        // the point of this suite, would never be reached.
        PtvTestTables.markMaterialised(jdbc);
    }

    void upsertAccount(String number, String productCode, BigDecimal balance, BigDecimal limit,
                       String status) {
        jdbc.update("""
                UPSERT INTO account (account_number, product_code, status, app_no, acc_type,
                                     branch_code, balance, max_credit_limit, cancel_reason,
                                     country_id, edr_ind, pre_ind, process_status, status_reason,
                                     ucn, client_id)
                VALUES (?,?,'AAUT',?,'CACC','250205',?,?,NULL,1,false,false,?,NULL,?,2)""",
                number, productCode, number, balance, limit, status, number);
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
