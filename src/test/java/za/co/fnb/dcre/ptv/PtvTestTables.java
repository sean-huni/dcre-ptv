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
