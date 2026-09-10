package za.co.fnb.dcre.ptv.data.repo;

import java.io.Serial;

/**
 * TECHNICAL failure: the account reference table was never materialised in this database, so
 * there is nothing to validate against. It is a statement about a DEPLOYMENT STEP that did
 * not run, never a finding about a payment.
 *
 * <p>SCRUM-107 repair 2. Three states must never be conflated. An artifact that is absent or
 * invalid fails the LOADER job. A populated table with no row for this creditor account is a
 * business rejection, {@code FAIL_ACCOUNT_NOT_FOUND}. An EMPTY table is neither: every
 * payment in the arrival would be rejected on the strength of a step nobody ran, and the
 * operator would read an arrival's worth of business rejections where the truth is that the
 * reference data was never staged or never loaded. That is precisely how this defect
 * presented in a deployed environment.
 *
 * <p>It is deliberately NOT a {@code CtvOutcome}: "there is no reference data" is not a
 * verdict on a payment, and putting it in the outcome enum would make it writable to
 * {@code validation_log} alongside real verdicts.
 *
 * <p>It is a plain {@link RuntimeException} and NOT a {@code TransientDataAccessException},
 * for the same reason as {@link ReferenceUnavailableException}: the platform's CRDB retry
 * handler retries transient aborts, and an unloaded table is not made loaded by five more
 * attempts. It propagates on the first occurrence, fails the step, and the job flow turns
 * that into a FAILED job for AGT to relaunch once the materialisation step has actually run.
 *
 * <p>Both messages NAME that step, so an operator reads the cause off the message rather
 * than off a runbook.
 */
public class AccountReferenceNotMaterialisedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The two halves of the materialisation, named so the message is self-diagnosing. */
    private static final String STEP = " The artifact is staged into the exchange root by the"
            + " infra deploy step (infra/dcre-infra/scripts/cutover-v1.sh, or"
            + " scripts/env-reset.sh for a local environment) and materialised into this"
            + " database by the loader run DCRE_PTV_JOB_NAME=ptvAccountReferenceLoadJob.";

    private AccountReferenceNotMaterialisedException(final String detail) {
        super(detail + STEP);
    }

    /** No load record at all: this database has never held account reference data. */
    public static AccountReferenceNotMaterialisedException neverLoaded() {
        return new AccountReferenceNotMaterialisedException(
                "account reference data has NEVER been loaded into this database:"
                        + " account_reference_load holds no record, so the account table is empty"
                        + " because the materialisation step did not run, not because these"
                        + " accounts are unknown.");
    }

    /** A load record exists and applied nothing: the artifact projected to zero rows here. */
    public static AccountReferenceNotMaterialisedException loadedEmpty(final String datasetVersion) {
        return new AccountReferenceNotMaterialisedException(
                "account reference data was loaded and applied ZERO rows: the newest"
                        + " account_reference_load record names dataset '" + datasetVersion
                        + "', so the account table is empty because that load materialised"
                        + " nothing, not because these accounts are unknown.");
    }
}
