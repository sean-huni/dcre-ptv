package za.co.fnb.dcre.ptv.service;

import za.co.fnb.dcre.platform.model.CtvOutcome;
import za.co.fnb.dcre.platform.model.ProductType;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The item-tier precedence chain for ENDO payments: account exists -&gt; account active
 * -&gt; account cap. Duplicate rules (e2e and content hash) run BEFORE this chain as the
 * set-based SQL dup scan ({@link DupScanService}, R-41); rows verdicted there are never
 * re-classified here.
 *
 * <p><b>There is no mandate tier and no flow branch.</b> CTV's chain ends in a mandate
 * state check against {@code dcre_man.man_ctv_view}, gated on a {@code dcFlow} flag, and
 * both halves are collections-only: the gate is DC-only (R-19/R-20) and the flag existed
 * because one service served two families out of one database. Payments has its own
 * database, so the database IS the discriminator and a payment carries no bank-registered
 * mandate to gate on (SPEC-ENDO-COLLECTIONS-FLOW.md, "No mandates, no mandate gate").
 * {@code FAIL_MANDATE_*}, {@code FAIL_CONTRACT_MISMATCH} and {@code FAIL_EXCEEDS_MANDATE_CAP}
 * are therefore unreachable here, and so is {@code FAIL_ACCOUNT_NOT_FOUND}: see below.
 *
 * <p>[SYNTHETIC-CONTRACT R-35] The pass-through semantics are the A-20 draft ones CTV ran
 * under {@code dcre.flow-dc=false}, now unconditional: an unknown account and a NULL cap
 * both PASS, because PAI creates absent accounts downstream (create-if-absent, R-11) and
 * the cap check applies post-init. An EXISTING account that is inactive or over its cap
 * still fails.
 */
public final class VerdictChain {

    public record Account(String accountNumber, String productCode, BigDecimal balance,
                          BigDecimal maxCreditLimit, String processStatus) {
    }

    public record Entry(int sequence, String e2e, String creditorAccount,
                        String contractRef, BigDecimal amount) {
    }

    private VerdictChain() {
    }

    /**
     * The whole chain: the account tier is the only tier. Returns {@link CtvOutcome#PASS}
     * when the entry is admissible.
     */
    public static CtvOutcome classify(Entry entry, Map<String, Account> accounts) {
        Account account = accounts.get(entry.creditorAccount());
        if (account == null) {
            // [SYNTHETIC-CONTRACT R-35] A-20 draft: an unknown account passes through;
            // PAI creates it downstream (create-if-absent). Never FAIL_ACCOUNT_NOT_FOUND:
            // that is the DC verdict, and DC does not run here.
            return CtvOutcome.PASS;
        }
        if (!"ACTIVE".equals(account.processStatus())) {
            return CtvOutcome.FAIL_ACCOUNT_NOT_ACTIVE;
        }
        boolean balanceCarrying = ProductType.fromProductCode(account.productCode()) == ProductType.BALANCE_CARRYING;
        BigDecimal cap = balanceCarrying ? account.balance() : account.maxCreditLimit();
        if (cap == null) {
            // [SYNTHETIC-CONTRACT R-35] A NULL cap marks a freshly-creatable account; the
            // cap check applies post-init, so it passes through.
            return CtvOutcome.PASS;
        }
        if (entry.amount().compareTo(cap) > 0) {
            return balanceCarrying ? CtvOutcome.FAIL_EXCEEDS_RF_BALANCE : CtvOutcome.FAIL_EXCEEDS_CC_LIMIT;
        }
        return CtvOutcome.PASS;
    }
}
