package za.co.fnb.dcre.ptv.config;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import za.co.fnb.dcre.ptv.service.DupScanTasklet;
import za.co.fnb.dcre.ptv.service.HeaderCheckTasklet;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the shared CRDB 40001 retry handler (platform-batch) is registered on a
 * verdict-WRITING step exactly as the production config builds it: the injected
 * failure is thrown from PlatformTransactionManager.doCommit, the boundary where
 * these jobs die under contention.
 *
 * <p>Why this replaces CTV's CrdbRetryExceptionHandlerTest rather than porting it.
 * CTV keeps a service-local copy of the handler and tests the MECHANISM three ways;
 * that copy has since been promoted into platform-batch, which is where CIR and PRR
 * consume it and where the mechanism belongs. Duplicating a platform class into a
 * brand-new repo would re-fork it on day one. So PTV consumes the shared handler and
 * pins its own WIRING, which is the part a fork can get wrong. The mechanism's own
 * three cases (retry, budget exhaustion, non-transient passthrough) remain proven in
 * CTV against the identical code.
 *
 * <p>Read-only steps (headerCheck, rollup) deliberately carry no handler: see the
 * config javadoc.
 */
class PtvJobConfigRetryTest {

    /** Fails the first {@code failures} COMMITS the way JdbcTransactionManager surfaces a CRDB 40001. */
    static final class CommitFailingTxManager extends ResourcelessTransactionManager {

        private final int failures;
        private int commits;

        CommitFailingTxManager(final int failures) {
            this.failures = failures;
        }

        @Override
        protected void doCommit(final DefaultTransactionStatus status) {
            if (++commits <= failures) {
                throw new CannotAcquireLockException(
                        "JDBC commit; ERROR: restart transaction: RETRY_SERIALIZABLE");
            }
            super.doCommit(status);
        }
    }

    static final class CountingDupScanTasklet extends DupScanTasklet {

        final AtomicInteger executions = new AtomicInteger();

        CountingDupScanTasklet() {
            super(null);
        }

        @Override
        public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
            executions.incrementAndGet();
            return RepeatStatus.FINISHED;
        }
    }

    @Test
    void dupScanStepRetriesCommitTimeSerializationAborts() throws Exception {
        var repo = new ResourcelessJobRepository();
        var tasklet = new CountingDupScanTasklet();
        Step step = new PtvJobConfig().dupScanStep(repo, new CommitFailingTxManager(2), tasklet);

        JobInstance instance = repo.createJobInstance("retryJob", new JobParameters());
        JobExecution jobExecution = repo.createJobExecution(instance, new JobParameters(), new ExecutionContext());
        StepExecution stepExecution = repo.createStepExecution("dupScanStep", jobExecution);
        step.execute(stepExecution);

        assertEquals(BatchStatus.COMPLETED, stepExecution.getStatus(),
                "two commit-time 40001 aborts must be retried, not fail the step");
        assertEquals(3, tasklet.executions.get(), "tasklet transaction re-runs once per aborted commit");
    }

    static final class CountingHeaderCheckTasklet extends HeaderCheckTasklet {

        final AtomicInteger executions = new AtomicInteger();

        CountingHeaderCheckTasklet() {
            super(null);
        }

        @Override
        public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
            executions.incrementAndGet();
            return RepeatStatus.FINISHED;
        }
    }

    @Test
    void headerCheckStepIsDeliberatelyNotRetried() throws Exception {
        var repo = new ResourcelessJobRepository();
        var tasklet = new CountingHeaderCheckTasklet();
        Step step = new PtvJobConfig().headerCheckStep(repo, new CommitFailingTxManager(1), tasklet);

        JobInstance instance = repo.createJobInstance("noRetryJob", new JobParameters());
        JobExecution jobExecution = repo.createJobExecution(instance, new JobParameters(), new ExecutionContext());
        StepExecution stepExecution = repo.createStepExecution("headerCheckStep", jobExecution);
        step.execute(stepExecution);

        assertEquals(BatchStatus.FAILED, stepExecution.getStatus(),
                "the read-only header check carries no retry handler, so a commit abort fails it;"
                        + " this is the control that proves the dupScan result above is the handler"
                        + " and not something the step builder does for every step");
        assertEquals(1, tasklet.executions.get(), "no re-run without a handler");
    }
}
