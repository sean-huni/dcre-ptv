package za.co.fnb.dcre.ptv;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared seeding helpers: the minimal PAI-shaped account read model and the
 * PRR-owned spine tables that PTV reads out of {@code dcre_pay}.
 * {@code validation_log} itself comes from this service's Liquibase changelog.
 *
 * <p>No {@code mandate_ref} column and no mandateRef overload. CTV's harness grew
 * both so its suites could exercise the projection gate; PTV has no such gate, and
 * a column nothing maps would let a future test seed a value that silently means
 * nothing.
 */
public final class PtvTestTables {

    /** The fixture's business date. */
    public static final String BUSINESS_DATE = "20260711";

    private PtvTestTables() {
    }

    public static void create(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS account (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    account_number VARCHAR(34) NOT NULL UNIQUE,
                    product_code VARCHAR(8) NOT NULL,
                    balance DECIMAL(18,2) NULL,
                    max_credit_limit DECIMAL(18,2) NULL,
                    process_status VARCHAR(16) NOT NULL)""");
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

    public static void insertAccount(JdbcTemplate jdbc, String number, String productCode,
                                     String cap, String status) {
        BigDecimal capValue = "none".equals(cap) ? null : new BigDecimal(cap);
        boolean balanceCarrying = productCode.startsWith("FNBRF");
        jdbc.update("""
                INSERT INTO account (account_number, product_code, balance, max_credit_limit, process_status)
                VALUES (?,?,?,?,?)""",
                number, productCode,
                balanceCarrying ? capValue : null,
                balanceCarrying ? null : capValue,
                status);
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
