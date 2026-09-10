package za.co.fnb.dcre.ptv.domain;

import java.util.List;

/**
 * One CSV data row, still RAW: every cell is the string the artifact carries, with an
 * empty cell normalised to null because an empty cell means ABSENT. Typing and
 * projection happen in the service layer, so the reader stays a reader and a malformed
 * number fails where the mapping is, not somewhere inside the parser.
 *
 * <p>The component order IS {@link #HEADER}, which is the artifact's own column order
 * and is asserted against the file before any row is built.
 */
public record AccountReferenceRow(String shape,
                                  String accountNumber,
                                  String productCode,
                                  String status,
                                  String accountTypeCode,
                                  String appNo,
                                  String accType,
                                  String branchCode,
                                  String balance,
                                  String maxCreditLimit,
                                  String cancelReason,
                                  String countryId,
                                  String edrInd,
                                  String preInd,
                                  String processStatus,
                                  String statusReason,
                                  String ucn,
                                  String clientId) {

    /** The 18 columns, in the exact order the artifact declares them. */
    public static final List<String> HEADER = List.of(
            "shape", "account_number", "product_code", "status", "account_type_code", "app_no",
            "acc_type", "branch_code", "balance", "max_credit_limit", "cancel_reason", "country_id",
            "edr_ind", "pre_ind", "process_status", "status_reason", "ucn", "client_id");

    public boolean isShape(final ReferenceShape wanted) {
        return wanted.name().equals(shape);
    }
}
