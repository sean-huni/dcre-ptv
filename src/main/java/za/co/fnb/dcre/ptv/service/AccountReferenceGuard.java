package za.co.fnb.dcre.ptv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ptv.data.model.AccountReferenceLoad;
import za.co.fnb.dcre.ptv.data.repo.AccountReferenceNotMaterialisedException;
import za.co.fnb.dcre.ptv.data.repo.ReferenceSnapshotDao;

/**
 * SCRUM-107 repair 2: the run-level check that separates "no reference data" from "no such
 * account". It runs ONCE per run, where the as-of snapshot is captured, and never per range
 * or per row: it is a fact about the database, not about a payment.
 *
 * <p>The authority is the {@code account_reference_load} record, not a count of
 * {@code account}. The record is written in the same transaction as the rows it applied, so
 * it is a statement about the table's CURRENT contents, and it distinguishes the two empty
 * states a count cannot: never loaded, and loaded but applied nothing.
 */
@Component
public class AccountReferenceGuard {

    private static final Logger log = LoggerFactory.getLogger(AccountReferenceGuard.class);

    private final ReferenceSnapshotDao snapshot;

    public AccountReferenceGuard(final ReferenceSnapshotDao snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * Returns the dataset version this run will validate against, so the run RECORDS what it
     * consumed. Raises {@link AccountReferenceNotMaterialisedException} when there is nothing
     * to validate against, which fails the step and therefore the job.
     */
    public String requireMaterialised(final String asOf) {
        AccountReferenceLoad load = snapshot.latestLoad(asOf)
                .orElseThrow(AccountReferenceNotMaterialisedException::neverLoaded);
        if (load.appliedRowCount() == 0) {
            throw AccountReferenceNotMaterialisedException.loadedEmpty(load.datasetVersion());
        }
        log.info("account-reference-materialised stage=PTV dataset={} appliedRows={}",
                load.datasetVersion(), load.appliedRowCount());
        return load.datasetVersion();
    }
}
