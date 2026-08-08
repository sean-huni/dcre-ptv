package za.co.fnb.dcre.ptv.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.ptv.service.AccountReferenceLoadTasklet;

/**
 * PTV's SECOND job: the account reference loader. It coexists with {@code ptvJob} and the
 * two are chosen by NAME through {@code spring.batch.job.name}, which is Boot's own
 * selector and the mechanism MRG already uses for its report/sweep pair. The yml binds it
 * to {@code DCRE_PTV_JOB_NAME} with the validation flow as the committed default, so a
 * launch that says nothing still runs {@code ptvJob} and nothing about the validation
 * flow's behaviour moves.
 *
 * <p>No {@code OutcomeSeamListener}. A seam file is a BUSINESS verdict about an arrival and
 * AGT reads it as one; a reference load is not an arrival and has no verdict to report. The
 * step's own status is the whole outcome.
 */
@Configuration
@EnableConfigurationProperties(AccountReferenceProperties.class)
public class AccountReferenceJobConfig {

    /** The launch selector value. One pivot, so config and bean cannot drift apart. */
    public static final String JOB_NAME = "ptvAccountReferenceLoadJob";

    @Bean
    public Step accountReferenceLoadStep(final JobRepository repo, final PlatformTransactionManager tx,
                                         final AccountReferenceLoadTasklet tasklet) {
        return new StepBuilder("accountReferenceLoadStep", repo).tasklet(tasklet, tx).build();
    }

    /**
     * Deliberately WITHOUT the CRDB 40001 retry handler that guards the validation flow's
     * writing steps. This load is a single whole-table replacement in one transaction: a
     * retry would re-run the delete and the inserts wholesale, and the honest answer to a
     * contended reference load is to fail and be relaunched.
     */
    @Bean
    public Job ptvAccountReferenceLoadJob(final JobRepository repo,
                                          final Step accountReferenceLoadStep,
                                          final HeartbeatWriter heartbeatWriter) {
        return new JobBuilder(JOB_NAME, repo)
                .listener(heartbeatWriter)
                .start(accountReferenceLoadStep)
                .build();
    }
}
