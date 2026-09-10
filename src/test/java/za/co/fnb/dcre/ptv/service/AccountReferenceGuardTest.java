package za.co.fnb.dcre.ptv.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import za.co.fnb.dcre.ptv.data.model.AccountReferenceLoad;
import za.co.fnb.dcre.ptv.data.repo.AccountReferenceNotMaterialisedException;
import za.co.fnb.dcre.ptv.data.repo.ReferenceSnapshotDao;
import za.co.fnb.dcre.ptv.data.repo.ReferenceUnavailableException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard's three answers, without a database. The end-to-end proof lives in
 * {@code AccountReferenceMaterialisationIT}; this class pins the WORDING, which is the part
 * an operator reads, and the fourth case that is easy to lose: a read that could not run
 * must propagate as technical, never be reinterpreted as "never loaded".
 */
class AccountReferenceGuardTest {

    /**
     * Answers a fixed load record. It extends the real DAO so the guard is wired exactly as
     * production wires it; the superclass collaborators are null because
     * {@link #latestLoad} is fully overridden and the guard calls nothing else.
     */
    static final class StubDao extends ReferenceSnapshotDao {

        private final Optional<AccountReferenceLoad> load;
        private final RuntimeException failure;

        StubDao(Optional<AccountReferenceLoad> load, RuntimeException failure) {
            super(null, null);
            this.load = load;
            this.failure = failure;
        }

        @Override
        public Optional<AccountReferenceLoad> latestLoad(String asOf) {
            if (failure != null) {
                throw failure;
            }
            return load;
        }
    }

    private static final String AS_OF = "1750000000.0000000000";

    @Test
    void noLoadRecordIsNeverLoadedAndNamesTheMaterialisationStep() {
        AccountReferenceNotMaterialisedException raised = assertThrows(
                AccountReferenceNotMaterialisedException.class,
                () -> guard(Optional.empty()).requireMaterialised(AS_OF));

        assertTrue(raised.getMessage().contains("NEVER been loaded"),
                "was: " + raised.getMessage());
        assertTrue(raised.getMessage().contains("ptvAccountReferenceLoadJob")
                        && raised.getMessage().contains("cutover-v1.sh"),
                "an operator must read the fix off the message: the staging step and the"
                        + " loader run, was: " + raised.getMessage());
    }

    @Test
    void aRecordThatAppliedNothingIsADifferentSentenceAndNamesTheDataset() {
        AccountReferenceNotMaterialisedException raised = assertThrows(
                AccountReferenceNotMaterialisedException.class,
                () -> guard(Optional.of(new AccountReferenceLoad("2026.08.09-001", 0)))
                        .requireMaterialised(AS_OF));

        assertTrue(raised.getMessage().contains("applied ZERO rows"),
                "the two empty states must not share a sentence: one means nobody ran the"
                        + " loader, the other means the loader ran, was: " + raised.getMessage());
        assertTrue(raised.getMessage().contains("2026.08.09-001"),
                "and the dataset that applied nothing, was: " + raised.getMessage());
    }

    @Test
    void aPopulatedTableReturnsItsDatasetVersionAndLogsOneInfoLine() {
        Logger guardLogger = (Logger) LoggerFactory.getLogger(AccountReferenceGuard.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        guardLogger.addAppender(events);
        try {
            String version = guard(Optional.of(new AccountReferenceLoad("2026.08.09-001", 10)))
                    .requireMaterialised(AS_OF);

            assertEquals("2026.08.09-001", version,
                    "the run carries the version forward so Batch metadata can answer which"
                            + " data it used, long after the table has been replaced");
            List<String> materialised = events.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("account-reference-materialised"))
                    .toList();
            assertEquals(1, materialised.size(),
                    "exactly ONE line per run, not one per range or per row, INFOs seen: "
                            + events.list);
            assertEquals("account-reference-materialised stage=PTV dataset=2026.08.09-001"
                    + " appliedRows=10", materialised.getFirst());
        } finally {
            guardLogger.detachAppender(events);
        }
    }

    @Test
    void aReadThatCouldNotRunStaysTechnicalAndIsNotReadAsNeverLoaded() {
        // The failure this guard could most easily introduce: an unreachable store answering
        // Optional.empty() would be reported as "the loader was never run", sending an
        // operator to re-run a job that is not the problem.
        ReferenceSnapshotDao dao =
                new StubDao(Optional.empty(), new ReferenceUnavailableException("account_reference_load",
                        new java.sql.SQLException("connection refused", "08001")));

        assertThrows(ReferenceUnavailableException.class,
                () -> new AccountReferenceGuard(dao).requireMaterialised(AS_OF));
    }

    private static AccountReferenceGuard guard(Optional<AccountReferenceLoad> load) {
        return new AccountReferenceGuard(new StubDao(load, null));
    }
}
