package za.co.fnb.dcre.ptv.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read model over PRR's {@code tx_entry} in {@code dcre_pay} (grants-based read, R-04/R-06).
 *
 * <p>No {@code mandateRef}. The column exists on the shared DETAIL_V3 physical layout and
 * PRR persists it, but a payment never targets a bank-registered mandate, so PTV has no
 * mandate tier to feed and mapping the field would advertise a gate that does not exist.
 */
@Table("tx_entry")
public class TxEntryView {

    @Id
    private UUID id;
    private UUID arrivalId;
    private Integer sequence;
    private String e2e;
    private String creditorAccount;
    private String contractRef;
    private BigDecimal amount;

    public Integer getSequence() { return sequence; }
    public String getE2e() { return e2e; }
    public String getCreditorAccount() { return creditorAccount; }
    public String getContractRef() { return contractRef; }
    public BigDecimal getAmount() { return amount; }
}
