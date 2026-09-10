package za.co.fnb.dcre.ptv;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every constraint of the collections shape, proved by REJECTION.
 *
 * <p>Presence in the catalogue is not evidence that a constraint bites, and this shape has a
 * specific reason to distrust presence: Liquibase's column-level {@code checkConstraint}
 * attribute parses and is a verified NO-OP in liquibase-core 5.0.3, so a changeset that
 * looked correct would produce a table with none of these three invariants and every test
 * that merely inserted good rows would stay green.
 *
 * <p>Each test inserts a row that differs from a known-good row in exactly ONE way, so the
 * rejection can only be the constraint under test. A bare "it threw" assertion passes for
 * every reason an insert can fail, including a typo in the fixture, so every rejection is
 * pinned twice: the constraint must exist in the catalogue under the NAME the changeset gives
 * it, and the error must quote the EXPRESSION that constraint carries.
 *
 * <p>Both halves are needed because CockroachDB does not name a check constraint in its
 * violation message: it prints the reconstructed predicate. Asserting only the name would
 * have been unsatisfiable, and asserting only the expression would leave the name free to
 * drift away from what the rollback and every operator runbook refer to. Verified against
 * cockroachdb v26.2.3; the unique-constraint violation, by contrast, DOES name its
 * constraint, which is why that test reads differently.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false",
        "dcre.exchange-root=build/test-exchange"})
class AccountReferenceConstraintIT {

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

    private static final String INSERT = """
            INSERT INTO account (account_number, product_code, status, app_no, acc_type,
                                 branch_code, balance, max_credit_limit, cancel_reason,
                                 country_id, edr_ind, pre_ind, process_status, status_reason,
                                 ucn, client_id)
            VALUES (?,?,'AAUT',?,'CACC','250205',?,?,NULL,1,false,false,'ACTIVE',NULL,?,2)""";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aWellFormedRowIsAccepted() {
        // The control every rejection below is measured against. Without it, "the insert
        // failed" could mean the row shape is wrong rather than the constraint working.
        assertEquals(1, insert("65000000000001", "FNBRF", "100.00", null));
        jdbc.update("DELETE FROM account WHERE account_number='65000000000001'");
    }

    @Test
    void anUnknownProductCodeIsRejectedByChkAccountProduct() {
        assertRejectedBy("chk_account_product", PRODUCT_PREDICATE,
                () -> insert("65000000000002", "FNBXX", "100.00", null));
    }

    @Test
    void aBalanceCarryingProductWithNoBalanceIsRejectedByChkAccountProductAmount() {
        assertRejectedBy("chk_account_product_amount", PRODUCT_AMOUNT_PREDICATE,
                () -> insert("65000000000003", "FNBRF", null, null));
    }

    @Test
    void aBalanceCarryingProductAlsoCarryingALimitIsRejectedByChkAccountProductAmount() {
        assertRejectedBy("chk_account_product_amount", PRODUCT_AMOUNT_PREDICATE,
                () -> insert("65000000000004", "FNBRF", "100.00", "50.00"));
    }

    @Test
    void aCreditProductWithNoLimitIsRejectedByChkAccountProductAmount() {
        assertRejectedBy("chk_account_product_amount", PRODUCT_AMOUNT_PREDICATE,
                () -> insert("65000000000005", "FNBCC", null, null));
    }

    @Test
    void aNegativeAmountIsRejectedByChkAccountAmountsNonneg() {
        assertRejectedBy("chk_account_amounts_nonneg", NONNEG_PREDICATE,
                () -> insert("65000000000006", "FNBRF", "-1.00", null));
    }

    @Test
    void aRepeatedAccountNumberIsRejectedByTheUniqueConstraint() {
        assertEquals(1, insert("65000000000007", "FNBRF", "100.00", null));
        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                () -> insert("65000000000007", "FNBCC", null, "100.00"));
        assertTrue(failure.getMessage().contains("uq_account_account_number"),
                "CockroachDB resolves UPSERT on the PRIMARY KEY only, so this constraint is the"
                        + " only thing making account_number the business identity, was: "
                        + failure.getMessage());
        jdbc.update("DELETE FROM account WHERE account_number='65000000000007'");
    }

    @Test
    void aRowMissingANotNullColumnIsRejected() {
        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                () -> insert("65000000000008", "FNBRF", "100.00", null, null));
        assertTrue(failure.getMessage().contains("ucn"),
                "the NOT NULL set is the collections shape's own and is not relaxed to a"
                        + " nullable union, was: " + failure.getMessage());
    }

    /**
     * The three predicates as CockroachDB reconstructs them. Each fragment appears in exactly
     * one of the three, so a rejection quoting it cannot be another constraint's.
     */
    private static final String PRODUCT_PREDICATE = "product_code IN ('FNBRF'";
    private static final String PRODUCT_AMOUNT_PREDICATE = "(balance IS NOT NULL)";
    private static final String NONNEG_PREDICATE = "(balance >= 0";

    private void assertRejectedBy(String constraint, String predicate, Runnable insert) {
        assertEquals(1, constraintsNamed(constraint),
                "the changeset must create this constraint under exactly this name, because the"
                        + " rollback file and every runbook refer to it by name");
        DataIntegrityViolationException failure =
                assertThrows(DataIntegrityViolationException.class, insert::run);
        assertTrue(failure.getMessage().contains(predicate),
                "expected the rejection to quote " + constraint + "'s predicate " + predicate
                        + ", was: " + failure.getMessage());
    }

    private int constraintsNamed(String constraint) {
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_name = 'account' AND constraint_name = ?""", Integer.class, constraint);
        return found == null ? 0 : found;
    }

    private int insert(String number, String product, String balance, String limit) {
        return insert(number, product, balance, limit, number);
    }

    private int insert(String number, String product, String balance, String limit, String ucn) {
        return jdbc.update(INSERT, number, product, number,
                balance == null ? null : new java.math.BigDecimal(balance),
                limit == null ? null : new java.math.BigDecimal(limit), ucn);
    }
}
