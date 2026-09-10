package za.co.fnb.dcre.ptv.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.co.fnb.dcre.ptv.data.model.AccountEntity;
import za.co.fnb.dcre.ptv.data.model.AccountReferenceLoadEntity;
import za.co.fnb.dcre.ptv.data.repo.AccountReferenceDao;
import za.co.fnb.dcre.ptv.data.repo.AccountReferenceLoadRepo;
import za.co.fnb.dcre.ptv.domain.AccountReferenceManifest;

import java.util.List;

/**
 * Step 9 of the loader contract, and the ONLY part of it that touches the database: delete
 * every row, insert the projected rows, insert the load record, all in ONE transaction.
 *
 * <p>It is a separate bean from {@link AccountReferenceLoadService} on purpose. Spring's
 * {@code @Transactional} is proxy-based, so a self-invocation inside one class would run
 * with no transaction at all and the atomicity contract would be silently absent while
 * every happy-path test stayed green.
 *
 * <p>There is no per-row skip and no partial application. A constraint violation on any row
 * rolls the whole thing back and the table keeps its PREVIOUS contents, load record
 * included: a run that half-applied would leave the table in a state no dataset version
 * describes.
 */
@Component
public class AccountReferenceApplier {

    private final AccountReferenceDao accounts;
    private final AccountReferenceLoadRepo loads;

    public AccountReferenceApplier(final AccountReferenceDao accounts,
                                   final AccountReferenceLoadRepo loads) {
        this.accounts = accounts;
        this.loads = loads;
    }

    @Transactional
    public int apply(final AccountReferenceManifest manifest, final List<AccountEntity> rows,
                     final Long jobExecutionId) {
        accounts.deleteAll();
        int applied = accounts.insertAll(rows);
        loads.save(AccountReferenceLoadEntity.of(manifest.datasetVersion(),
                manifest.schemaVersion(), manifest.sourceId(), manifest.effectiveTs(),
                manifest.publicationTs(), manifest.rowCount(), manifest.checksum(), applied,
                jobExecutionId));
        return applied;
    }
}
