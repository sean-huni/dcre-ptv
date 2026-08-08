package za.co.fnb.dcre.ptv.data.model;

import java.math.BigDecimal;

/**
 * The sixteen business columns of {@code dcre_pay.account}, typed. The UUID key and the
 * two audit timestamps carry database defaults and are not modelled here: the loader
 * never names them, so modelling them would invite a caller to set one.
 *
 * <p>This is deliberately NOT a {@code BaseEntity} aggregate. {@code account} carries no
 * {@code version} column in the collections shape, and the loader's write is a whole-table
 * replacement in one transaction rather than a per-row save, so Spring Data JDBC's
 * optimistic locking would have nothing to lock against. {@code AccountReferenceDao}
 * writes these rows directly.
 */
public record AccountEntity(String accountNumber,
                            String productCode,
                            String status,
                            String appNo,
                            String accType,
                            String branchCode,
                            BigDecimal balance,
                            BigDecimal maxCreditLimit,
                            String cancelReason,
                            Long countryId,
                            boolean edrInd,
                            boolean preInd,
                            String processStatus,
                            String statusReason,
                            String ucn,
                            Long clientId) {
}
