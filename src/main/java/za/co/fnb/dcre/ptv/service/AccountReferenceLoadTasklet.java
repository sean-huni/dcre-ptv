package za.co.fnb.dcre.ptv.service;

import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Thin adapter, service does the work (the 3-tier batch rule). It carries the Batch
 * execution id into the load record so a materialised table can be traced back to the run
 * that filled it, and it lets every failure propagate: a load that could not be applied
 * must fail the step, never complete with a warning.
 */
@Component
public class AccountReferenceLoadTasklet implements Tasklet {

    private final AccountReferenceLoadService service;

    public AccountReferenceLoadTasklet(final AccountReferenceLoadService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext context) {
        Long executionId = context.getStepContext().getStepExecution().getJobExecutionId();
        int applied = service.load(executionId);
        contribution.incrementWriteCount(applied);
        return RepeatStatus.FINISHED;
    }
}
