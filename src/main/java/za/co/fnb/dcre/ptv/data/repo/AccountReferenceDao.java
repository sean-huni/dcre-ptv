package za.co.fnb.dcre.ptv.data.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ptv.data.model.AccountEntity;

import java.util.List;

/**
 * The only writer of {@code dcre_pay.account}. The materialisation is a whole-table
 * replacement, never a merge: the artifact is the truth about which accounts exist, so a
 * row this dataset does not carry must not survive the load.
 *
 * <p>Rows are inserted ONE AT A TIME rather than through a JDBC batch. A batch reports a
 * constraint violation as an aggregate {@code BatchUpdateException} whose message names
 * the failure but not the row, and the whole point of the atomicity contract is that an
 * operator can see WHICH row of the artifact is malformed. Ten rows make the cost of the
 * loop nil.
 */
@Component
public class AccountReferenceDao {

    private static final String INSERT = """
            INSERT INTO account (account_number, product_code, status, app_no, acc_type,
                                 branch_code, balance, max_credit_limit, cancel_reason,
                                 country_id, edr_ind, pre_ind, process_status, status_reason,
                                 ucn, client_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";

    private final JdbcTemplate jdbc;

    public AccountReferenceDao(final JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int deleteAll() {
        return jdbc.update("DELETE FROM account WHERE 1 = 1");
    }

    public int insertAll(final List<AccountEntity> rows) {
        int inserted = 0;
        for (AccountEntity row : rows) {
            inserted += jdbc.update(INSERT, row.accountNumber(), row.productCode(), row.status(),
                    row.appNo(), row.accType(), row.branchCode(), row.balance(),
                    row.maxCreditLimit(), row.cancelReason(), row.countryId(), row.edrInd(),
                    row.preInd(), row.processStatus(), row.statusReason(), row.ucn(),
                    row.clientId());
        }
        return inserted;
    }

    public int count() {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);
        return rows == null ? 0 : rows;
    }
}
