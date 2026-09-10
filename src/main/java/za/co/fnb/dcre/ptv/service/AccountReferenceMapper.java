package za.co.fnb.dcre.ptv.service;

import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ptv.data.model.AccountEntity;
import za.co.fnb.dcre.ptv.domain.AccountReferenceLoadException;
import za.co.fnb.dcre.ptv.domain.AccountReferenceRow;

import java.math.BigDecimal;

/**
 * Artifact row to persistence row. Mapping lives in the service layer (architecture.md),
 * so the reader stays a reader and the DAO stays a writer.
 *
 * <p>{@code account_type_code} is NOT mapped and has no column here: it belongs to the
 * mandates projection. Dropping it is the projection, not an omission.
 *
 * <p>Every conversion failure names the column and the offending value. A malformed number
 * in reference data is a defect in somebody's artifact and the message has to be enough to
 * find the cell.
 */
@Component
public class AccountReferenceMapper {

    public AccountEntity toEntity(final AccountReferenceRow row) {
        return new AccountEntity(
                row.accountNumber(), row.productCode(), row.status(), row.appNo(), row.accType(),
                row.branchCode(), decimal(row.balance(), "balance", row),
                decimal(row.maxCreditLimit(), "max_credit_limit", row), row.cancelReason(),
                integral(row.countryId(), "country_id", row), flag(row.edrInd(), "edr_ind", row),
                flag(row.preInd(), "pre_ind", row), row.processStatus(), row.statusReason(),
                row.ucn(), integral(row.clientId(), "client_id", row));
    }

    private static BigDecimal decimal(final String value, final String column,
                                      final AccountReferenceRow row) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw malformed(column, value, row, e);
        }
    }

    private static Long integral(final String value, final String column,
                                 final AccountReferenceRow row) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw malformed(column, value, row, e);
        }
    }

    /**
     * Strictly {@code true} or {@code false}. {@link Boolean#parseBoolean} answers false to
     * anything it does not recognise, which would turn a typo into a silent flag flip.
     */
    private static boolean flag(final String value, final String column,
                                final AccountReferenceRow row) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw malformed(column, value, row, null);
    }

    private static AccountReferenceLoadException malformed(final String column, final String value,
                                                           final AccountReferenceRow row,
                                                           final Throwable cause) {
        return new AccountReferenceLoadException("account reference row for account_number '"
                + row.accountNumber() + "' carries a malformed " + column + ": '" + value + "'",
                cause);
    }
}
