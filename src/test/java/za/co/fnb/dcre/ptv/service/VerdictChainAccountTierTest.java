package za.co.fnb.dcre.ptv.service;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.platform.model.CtvOutcome;
import za.co.fnb.dcre.ptv.service.VerdictChain.Account;
import za.co.fnb.dcre.ptv.service.VerdictChain.Entry;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SCRUM-107 repair 1: the account tier is a fail-CLOSED control. The chain is DB-free,
 * so the "no matching row" arm is asserted here directly rather than inferred from a
 * job run. The companion technical arm (the reference store could not be read at all)
 * cannot appear here by construction: it never reaches the chain, because the DAO
 * raises {@code ReferenceUnavailableException} before a map is ever built. That
 * structural separation IS the distinction the repair is about.
 */
class VerdictChainAccountTierTest {

    private static final Entry ENTRY =
            new Entry(1, "E2E-UNIT-1", "62999999999901", "CT-UNIT-1", new BigDecimal("100.00"));

    @Test
    void noMatchingRowIsABusinessRejectionNotAPass() {
        assertEquals(CtvOutcome.FAIL_ACCOUNT_NOT_FOUND, VerdictChain.classify(ENTRY, Map.of()),
                "an account absent from the reference store is a rejection with its own reason;"
                        + " a control that answers PASS when it found nothing is not a control");
    }

    @Test
    void anEmptyReferenceMapRejectsEveryEntryIndividually() {
        Entry second = new Entry(2, "E2E-UNIT-2", "62999999999902", null, new BigDecimal("1.00"));
        assertEquals(CtvOutcome.FAIL_ACCOUNT_NOT_FOUND, VerdictChain.classify(second, Map.of()));
    }

    @Test
    void aMatchedActiveAccountUnderCapStillPasses() {
        Account account = new Account("62999999999901", "FNBRF", new BigDecimal("500.00"), null, "ACTIVE");
        assertEquals(CtvOutcome.PASS, VerdictChain.classify(ENTRY, Map.of(account.accountNumber(), account)),
                "control: the rejection above is the absence arm, not a chain that rejects everything");
    }
}
