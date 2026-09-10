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
 * are therefore unreachable here.
 *
 * <p><b>SCRUM-107 repair 1: the account tier fails CLOSED.</b> An account the reference
 * store does not hold is {@link CtvOutcome#FAIL_ACCOUNT_NOT_FOUND}. It previously returned
 * PASS, on the A-20 draft reasoning that PAI created absent accounts downstream
 * (create-if-absent, R-11), which left the one tier this chain has answering PASS in
 * exactly the case it exists to catch. A control that passes when it finds nothing is not
 * a control, and it fails silently: nothing errors and no suite goes red.
 *
 * <p><b>"Absent" is not "unreadable".</b> This method only ever sees a map, so it cannot
 * tell the difference, and it does not have to: a reference store that could not be read
 * never produces a map at all. {@code ReferenceSnapshotDao} raises
 * {@code ReferenceUnavailableException} and the step fails, so a technical fault becomes a
 * FAILED job rather than an arrival's worth of business rejections. The separation is
 * structural, not a convention this class has to remember.
 *
 * <p>A NULL cap on an EXISTING account still passes the cap tier: that row is present and
 * only its limit is unset, which is a different question from existence and one the open
 * account-model decision owns. An existing account that is inactive or over its cap fails
 * as before.
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
            // No row for this creditor account in the snapshot: a business REJECTION with
            // its own reason. The map is only ever built from a read that SUCCEEDED, so
            // an absence here is a fact about the store's contents, never about its
            // reachability (see the class javadoc).
            return CtvOutcome.FAIL_ACCOUNT_NOT_FOUND;
        }
        if (!"ACTIVE".equals(account.processStatus())) {
            return CtvOutcome.FAIL_ACCOUNT_NOT_ACTIVE;
        }
        boolean balanceCarrying = ProductType.fromProductCode(account.productCode()) == ProductType.BALANCE_CARRYING;
        BigDecimal cap = balanceCarrying ? account.balance() : account.maxCreditLimit();
        if (cap == null) {
            // The row EXISTS and its limit is unset, so there is no cap to breach. Unlike
            // the absence arm above, this is not the tier failing open: existence and
            // activity have both been checked and passed. Whether an unset cap should
            // itself be a rejection is an account-model question, recorded not decided.
            return CtvOutcome.PASS;
        }
        if (entry.amount().compareTo(cap) > 0) {
            return balanceCarrying ? CtvOutcome.FAIL_EXCEEDS_RF_BALANCE : CtvOutcome.FAIL_EXCEEDS_CC_LIMIT;
        }
        return CtvOutcome.PASS;
    }
}
