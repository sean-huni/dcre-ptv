package za.co.fnb.dcre.ptv.data.repo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SCRUM-107 repair 1, technical arm: an UNAVAILABLE reference store must raise, never
 * resolve to an empty map. An empty map is now the business signal for "no such account"
 * (FAIL_ACCOUNT_NOT_FOUND), so if a failed read degraded to one, every payment in the
 * arrival would be rejected on the strength of a connection error, and the operator
 * would see a file of business rejections where the truth was an outage.
 *
 * <p>The datasource is driven to fail directly rather than through a container: an
 * unreachable datasource is exactly a {@code getConnection} that throws, and that is
 * both cheaper and less ambiguous than aiming a job at a closed port.
 */
class ReferenceSnapshotDaoTechnicalFailureTest {

    /** Distinct from any verdict text: an operator greps this to tell an outage from a rejection. */
    private static final String TECHNICAL_TOKEN = "reference-store-unavailable";

    @Test
    void anUnreachableDatasourceRaisesTechnicallyAndIsNotAnEmptyResult() {
        SQLException cause = new SQLException("connection refused", "08001");
        ReferenceSnapshotDao dao = new ReferenceSnapshotDao(null, failingDataSource(cause));

        ReferenceUnavailableException raised = assertThrows(ReferenceUnavailableException.class,
                () -> dao.accountsByNumber("1750000000.0000000000", Set.of("62999999999901")),
                "a read that could not run must not answer with an empty map");
        assertSame(cause, raised.getCause(), "the SQL cause is preserved for the operator");
        assertTrue(raised.getMessage().contains("account"),
                "the message names the relation that could not be read, was: " + raised.getMessage());
    }

    @Test
    void theTechnicalFailureIsLoggedAtErrorUnderItsOwnToken() {
        Logger daoLogger = (Logger) LoggerFactory.getLogger(ReferenceSnapshotDao.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        daoLogger.addAppender(appender);
        try {
            ReferenceSnapshotDao dao =
                    new ReferenceSnapshotDao(null, failingDataSource(new SQLException("boom", "08001")));
            assertThrows(ReferenceUnavailableException.class,
                    () -> dao.accountsByNumber("1750000000.0000000000", Set.of("62999999999901")));

            List<String> errors = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(TECHNICAL_TOKEN))
                    .toList();
            assertEquals(1, errors.size(),
                    "exactly one ERROR carrying the technical token, was: " + appender.list);
            assertTrue(errors.getFirst().contains("stage=PTV") && errors.getFirst().contains("relation=account"),
                    "the ERROR names the stage and the relation, was: " + errors.getFirst());
        } finally {
            daoLogger.detachAppender(appender);
        }
    }

    @Test
    void anEmptyAccountSetShortCircuitsAndNeverTouchesTheDatasource() {
        // Control: proves the two assertions above fail because the READ failed, not
        // because this DAO throws for any input at all.
        ReferenceSnapshotDao dao =
                new ReferenceSnapshotDao(null, failingDataSource(new SQLException("boom", "08001")));
        assertTrue(dao.accountsByNumber("1750000000.0000000000", Set.of()).isEmpty());
    }

    private static DataSource failingDataSource(final SQLException cause) {
        DataSource dataSource = mock(DataSource.class);
        try {
            when(dataSource.getConnection()).thenThrow(cause);
        } catch (SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
        return dataSource;
    }
}
