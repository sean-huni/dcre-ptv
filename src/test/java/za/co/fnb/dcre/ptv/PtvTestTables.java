package za.co.fnb.dcre.ptv;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared seeding helpers for the PRR-owned spine tables that PTV reads out of
 * {@code dcre_pay}, and for the account reference rows the verdict chain reads.
 * {@code validation_log} and, since SCRUM-107, {@code account} itself both come from
 * this service's Liquibase changelog.
 *
 * <p><b>This harness no longer creates {@code account}.</b> It used to, with a
 * six-column shape that existed nowhere in the estate, because nothing created the
 * relation for real. 003-account-reference.xml now does, in the full collections shape
 * with its NOT NULLs and its three CHECK constraints, so a test seeding a row has to
 * seed a row the real table would accept. That is the point: a fixture narrower than the
 * table it stands for is a drift class no green suite can see.
 *
 * <p>No {@code mandate_ref} column and no mandateRef overload. CTV's harness grew both
 * so its suites could exercise the projection gate; PTV has no such gate, and a column
 * nothing maps would let a future test seed a value that silently means nothing.
 */
public final class PtvTestTables {

    /** The fixture's business date. */
    public static final String BUSINESS_DATE = "20260711";

    /** What a hand-seeded reference table claims it was loaded from. */
    public static final String DATASET_VERSION = "2026.08.09-001";

    /**
     * The account {@link #materialiseReference} seeds so the table is non-empty. Outside
     * every range the suites verdict against, so it can never be mistaken for a match.
     */
    public static final String REFERENCE_SENTINEL = "63960000000001";

    private PtvTestTables() {
    }

    public static void create(JdbcTemplate jdbc) {
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
    }

    /**
     * Seeds one account in the REAL collections shape. {@code status} is the row's
     * process status, which is what the verdict chain reads; the settlement {@code
     * status} column carries the fixture's AAUT so the row is a whole row rather than
     * the four columns this suite happens to care about.
     *
     * <p>It also records the load ({@link #markMaterialised}), because the real applier
     * writes both in one transaction and SCRUM-107 repair 2 now reads that record to tell
     * an unloaded table from an unknown account.
     *
     * <p>{@code cap="none"} is REJECTED rather than mapped to NULL. That row cannot exist
     * in {@code dcre_pay.account}, and the alternative, dropping the constraint from inside
     * this helper, would weaken every later insert in the caller's container for the rest of
     * its life and do it silently. Throwing makes the impossibility loud at the call site.
     * Same shape as {@code CtvTestTables.insertAccount}; these two services are twins and a
     * divergence here is the drift class this wave exists to remove.
     */
    public static void insertAccount(JdbcTemplate jdbc, String number, String productCode,
                                     String cap, String status) {
        if ("none".equals(cap)) {
            throw new IllegalArgumentException("dcre_pay.account cannot hold a row with no cap:"
                    + " chk_account_product_amount requires a balance for FNBRF and a limit for"
                    + " FNBCC. Assert the unset-cap arm in VerdictChainAccountTierTest instead.");
        }
        BigDecimal capValue = new BigDecimal(cap);
        boolean balanceCarrying = productCode.startsWith("FNBRF");
        jdbc.update("""
                INSERT INTO account (account_number, product_code, status, app_no, acc_type,
                                     branch_code, balance, max_credit_limit, cancel_reason,
                                     country_id, edr_ind, pre_ind, process_status, status_reason,
                                     ucn, client_id)
                VALUES (?,?,'AAUT',?,'CACC','250205',?,?,NULL,1,false,false,?,NULL,?,2)""",
                number, productCode, number,
                balanceCarrying ? capValue : null,
                balanceCarrying ? null : capValue,
                status, number);
        markMaterialised(jdbc);
    }

    /**
     * Records that a load happened, with {@code applied_row_count} equal to what
     * {@code account} ACTUALLY holds at this moment.
     *
     * <p>SCRUM-107 repair 2 made this part of the fixture rather than an optional extra. The
     * real applier writes the load record in the SAME transaction as the rows, so a table
     * holding rows with no load record is a state {@code dcre_pay} cannot reach, and a
     * harness that produced it would be seeding an impossible database. Since the guard now
     * reads that record to tell "never loaded" from "no such account", a suite seeding
     * accounts by hand has to seed the record too, which is why {@link #insertAccount} calls
     * this itself and why suites that insert accounts their own way call it directly.
     *
     * <p>The count is read rather than passed in on purpose: a caller-supplied number is one
     * more thing that can disagree with the table it describes.
     */
    public static void markMaterialised(JdbcTemplate jdbc) {
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);
        jdbc.update("""
                INSERT INTO account_reference_load (dataset_version, schema_version, source_id,
                        effective_ts, publication_ts, row_count, checksum, applied_row_count)
                VALUES (?,1,'fixture:test/PtvTestTables', now(), now(), ?, 'fixture', ?)""",
                DATASET_VERSION, applied == null ? 0 : applied, applied == null ? 0 : applied);
    }

    /**
     * Puts the database in the state a DEPLOYED environment is in: the reference table
     * materialised, holding at least one account. Idempotent, because the suites that need
     * it share one container across their tests.
     *
     * <p>The sentinel account is deliberately outside every range the suites verdict
     * against, so "materialised" never accidentally becomes "and this payment's account
     * exists".
     */
    public static void materialiseReference(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO account (account_number, product_code, status, app_no, acc_type,
                                     branch_code, balance, max_credit_limit, cancel_reason,
                                     country_id, edr_ind, pre_ind, process_status, status_reason,
                                     ucn, client_id)
                VALUES (?,'FNBRF','AAUT',?,'CACC','250205',5000.00,NULL,NULL,1,false,false,
                        'ACTIVE',NULL,?,2)
                ON CONFLICT (account_number) DO NOTHING""",
                REFERENCE_SENTINEL, REFERENCE_SENTINEL, REFERENCE_SENTINEL);
        markMaterialised(jdbc);
    }

    public static void insertHeader(JdbcTemplate jdbc, UUID arrival, int txCount) {
        insertHeader(jdbc, arrival, txCount, "FNBCC01");
    }

    public static void insertHeader(JdbcTemplate jdbc, UUID arrival, int txCount, String initgPty) {
        jdbc.update("INSERT INTO tx_header (arrival_id, tx_count, initg_pty, business_date) VALUES (?,?,?,?)",
                arrival, txCount, initgPty, BUSINESS_DATE);
    }

    public static void insertEntry(JdbcTemplate jdbc, UUID arrival, int sequence, String e2e,
                                   String account, String contract, String amount) {
        insertEntry(jdbc, arrival, sequence, e2e, account, contract, amount, null);
    }

    public static void insertEntry(JdbcTemplate jdbc, UUID arrival, int sequence, String e2e,
                                   String account, String contract, String amount, String contentHash) {
        jdbc.update("""
                INSERT INTO tx_entry (arrival_id, sequence, e2e, creditor_account, contract_ref,
                                      amount, content_hash)
                VALUES (?,?,?,?,?,?,?)""",
                arrival, sequence, e2e, account, contract, new BigDecimal(amount), contentHash);
    }
}
