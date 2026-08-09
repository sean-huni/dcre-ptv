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
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.ptv.config.AccountReferenceJobConfig;
import za.co.fnb.dcre.ptv.service.AccountReferenceApplier;
import za.co.fnb.dcre.ptv.service.AccountReferenceLoadService;
import za.co.fnb.dcre.ptv.service.AccountCsvReader;
import za.co.fnb.dcre.ptv.service.AccountReferenceMapper;
import za.co.fnb.dcre.ptv.service.ArtifactFixture;
import za.co.fnb.dcre.ptv.service.ManifestReader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader against a real CockroachDB, through the real Liquibase-created relation.
 *
 * <p>Two things only a database can prove live here. First, that the REAL committed artifact
 * materialises into the REAL collections shape: every constraint the shape carries is a
 * chance for the published data to be rejected, and a unit test over the same file cannot
 * see that. Second, ATOMICITY: a load whose Nth row violates a constraint must leave the
 * table exactly as it was, previous contents included.
 *
 * <p>The tests are ORDERED because they share one table by design: the atomicity proof needs
 * a known PREVIOUS state, and the cheapest honest previous state is the one a successful load
 * just produced.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange",
        // The runtime root now DERIVES from the exchange root (the staged copy), and this
        // suite loads the committed SOURCE out of the checkout, so it says so explicitly
        // rather than leaning on a default that no longer names the fixtures path.
        "dcre.ptv.reference.account.root=" + ArtifactFixture.REAL_ROOT_PATH})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountReferenceLoadIT {

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
    JobOperator jobOperator;

    @Autowired
    Job ptvAccountReferenceLoadJob;

    @Autowired
    Job ptvJob;

    @Autowired
    AccountReferenceApplier applier;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment environment;

    // ---- both jobs coexist and are chosen by NAME ---------------------------------------

    @Test
    @Order(1)
    void theTwoJobsCoexistAndTheCommittedDefaultStaysTheValidationFlow() {
        assertEquals("ptvJob", ptvJob.getName());
        assertEquals(AccountReferenceJobConfig.JOB_NAME, ptvAccountReferenceLoadJob.getName());
        assertEquals("ptvJob", environment.getProperty("spring.batch.job.name"),
                "the loader must not become the default: a launch that says nothing still runs"
                        + " the validation flow, exactly as it did before this job existed");
    }

    // ---- the real artifact, through the real job ----------------------------------------

    @Test
    @Order(2)
    void theCommittedArtifactLoadsTenRowsAndRecordsWhatItConsumed() throws Exception {
        JobExecution run = jobOperator.start(ptvAccountReferenceLoadJob,
                new JobParametersBuilder().addString("run.id", "load-1", true).toJobParameters());

        assertEquals(BatchStatus.COMPLETED, run.getStatus(),
                "the committed artifact must satisfy the collections shape it is materialised into");
        assertEquals(10, count("SELECT count(*) FROM account"));
        assertEquals(10, count("SELECT count(DISTINCT account_number) FROM account"),
                "zero duplicates: the business identity is account_number");

        Map<String, Object> record = jdbc.queryForMap(
                "SELECT * FROM account_reference_load ORDER BY created_at DESC LIMIT 1");
        assertEquals("2026.08.09-001", record.get("dataset_version"));
        assertEquals(1, ((Number) record.get("schema_version")).intValue());
        assertEquals("fixture:fnb_dcre_ctv_toolkit/dcre_accounts.csv", record.get("source_id"));
        assertEquals(110, ((Number) record.get("row_count")).intValue(),
                "row_count is the WHOLE artifact");
        assertEquals(10, ((Number) record.get("applied_row_count")).intValue(),
                "applied_row_count is what THIS context materialised; the two differ by design"
                        + " and a run where they were equal would mean the projection stopped");
        assertEquals("5831d612cbcce77f17f5f4ee50dd05cfed14bfc6ce72182649f4eccdb64e75a6",
                record.get("checksum"));
        assertNotNull(record.get("effective_ts"));
        assertNotNull(record.get("publication_ts"));
        assertEquals(run.getId(), ((Number) record.get("job_execution_id")).longValue(),
                "a materialised table must be traceable to the run that filled it");
    }

    @Test
    @Order(3)
    void aSecondLoadReplacesRatherThanAccumulates() throws Exception {
        jdbc.update("""
                INSERT INTO account (account_number, product_code, status, app_no, acc_type,
                                     branch_code, balance, max_credit_limit, cancel_reason,
                                     country_id, edr_ind, pre_ind, process_status, status_reason,
                                     ucn, client_id)
                VALUES ('69990000000001','FNBRF','AAUT','A','CACC','250205',1.00,NULL,NULL,1,
                        false,false,'ACTIVE',NULL,'U',2)""");
        assertEquals(11, count("SELECT count(*) FROM account"));

        JobExecution run = jobOperator.start(ptvAccountReferenceLoadJob,
                new JobParametersBuilder().addString("run.id", "load-2", true).toJobParameters());

        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        assertEquals(10, count("SELECT count(*) FROM account"),
                "the artifact is the truth about which accounts exist, so a row it does not"
                        + " carry must not survive the load");
        assertEquals(0, count("SELECT count(*) FROM account WHERE account_number='69990000000001'"));
        assertEquals(2, count("SELECT count(*) FROM account_reference_load"),
                "one record per run, appended");
    }

    // ---- 5. partial application ----------------------------------------------------------

    @Test
    @Order(4)
    void aLoadWhoseThirdRowViolatesAConstraintLeavesTheTableExactlyAsItWas() {
        // The known PREVIOUS contents: the 10 rows the last successful load applied.
        List<String> before = accountNumbers();
        assertEquals(10, before.size(), "control: there IS a previous state to preserve");
        int loadsBefore = count("SELECT count(*) FROM account_reference_load");

        Path root = ArtifactFixture.tempRoot("ptv-partial");
        String version = "2026.08.09-999";
        ArtifactFixture.write(root, version, ArtifactFixture.csv(
                ArtifactFixture.collections("64000000000001", "10.00"),
                ArtifactFixture.collections("64000000000002", "20.00"),
                ArtifactFixture.collectionsWithUnknownProduct("64000000000003")), keys -> { });

        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                () -> serviceFor(root, version).load(null));

        // CockroachDB quotes the reconstructed predicate rather than the constraint name
        // (verified against v26.2.3), so the SPECIFIC rejection is pinned by the predicate.
        // Naming it here would be unsatisfiable; asserting only "it threw" would pass for a
        // connection error, which is the failure this whole arm must not be confused with.
        assertTrue(failure.getMessage().contains("product_code IN ('FNBRF'"),
                "the rejection must be chk_account_product's, was: " + failure.getMessage());
        assertEquals(before, accountNumbers(),
                "there is no partial application: the whole load rolls back and the table keeps"
                        + " its PREVIOUS contents, byte for byte");
        assertEquals(0, count("SELECT count(*) FROM account WHERE account_number LIKE '64%'"),
                "not one of the new rows survives, including the two that inserted cleanly"
                        + " before the third was rejected");
        assertEquals(loadsBefore, count("SELECT count(*) FROM account_reference_load"),
                "and no load record claims a load that did not happen");
    }

    @Test
    @Order(5)
    void theSameCraftedArtifactLoadsWhenItsThirdRowIsWellFormed() {
        // Control for the arm above: the harness CAN load a crafted artifact, so the rollback
        // is a fact about the constraint and not about the fixture being unloadable.
        Path root = ArtifactFixture.tempRoot("ptv-partial-control");
        String version = "2026.08.09-998";
        ArtifactFixture.write(root, version, ArtifactFixture.csv(
                ArtifactFixture.collections("64000000000001", "10.00"),
                ArtifactFixture.collections("64000000000002", "20.00"),
                ArtifactFixture.collections("64000000000003", "30.00")), keys -> { });

        assertEquals(3, serviceFor(root, version).load(null));
        assertEquals(3, count("SELECT count(*) FROM account"));
    }

    private AccountReferenceLoadService serviceFor(Path root, String version) {
        // The APPLIER is the container's proxied bean, so the transaction under test is the
        // real one. Only the properties are swapped, which is the one thing a crafted
        // artifact needs.
        return new AccountReferenceLoadService(ArtifactFixture.properties(root, version, null),
                new ManifestReader(), new AccountCsvReader(), new AccountReferenceMapper(), applier);
    }

    private List<String> accountNumbers() {
        return jdbc.queryForList("SELECT account_number FROM account ORDER BY account_number",
                String.class);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
