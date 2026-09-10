package za.co.fnb.dcre.ptv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.platform.model.CtvOutcome;
import za.co.fnb.dcre.ptv.data.model.TxEntryView;
import za.co.fnb.dcre.ptv.data.model.TxHeaderView;
import za.co.fnb.dcre.ptv.data.model.ValidationLogEntity;
import za.co.fnb.dcre.ptv.data.repo.ReferenceSnapshotDao;
import za.co.fnb.dcre.ptv.data.repo.TxEntryViewRepo;
import za.co.fnb.dcre.ptv.data.repo.TxHeaderViewRepo;
import za.co.fnb.dcre.ptv.data.repo.ValidationLogBatchDao;
import za.co.fnb.dcre.ptv.data.repo.ValidationLogRepo;
import za.co.fnb.dcre.ptv.service.VerdictChain.Account;
import za.co.fnb.dcre.ptv.service.VerdictChain.Entry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business tier (configuration.md point 21): the R-19 two-tier verdict pass over one
 * arrival, split for R-41 partitioning. Tier 1 (header/count check) runs once in the
 * headerCheck step and captures the F51 as-of snapshot timestamp; the dup scan runs
 * set-based once; tier 2 runs per sequence range against account reference data read
 * AS OF that single timestamp, so every partition sees one consistent snapshot even
 * under a mid-job account mutation. Upserts keyed (arrival_id, sequence): R-05.
 *
 * <p>ONE snapshot, not two. CTV captures a second as-of timestamp against {@code dcre_man}
 * for the mandate projection; PTV has no mandate gate, so there is no second store to pin
 * and no second timestamp to thread through the partitioner.
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    public sealed interface HeaderCheck {
        record FileFatal(String reason) implements HeaderCheck { }
        record Ok(int txCount, String clientToken, String asOfTimestamp,
                  String accountDatasetVersion) implements HeaderCheck { }
    }

    private final TxHeaderViewRepo headers;
    private final TxEntryViewRepo entries;
    private final ReferenceSnapshotDao referenceSnapshot;
    private final AccountReferenceGuard accountReference;
    private final ValidationLogRepo verdicts;
    private final ValidationLogBatchDao verdictBatch;

    public ValidationService(TxHeaderViewRepo headers, TxEntryViewRepo entries,
                             ReferenceSnapshotDao referenceSnapshot,
                             AccountReferenceGuard accountReference,
                             ValidationLogRepo verdicts, ValidationLogBatchDao verdictBatch) {
        this.headers = headers;
        this.entries = entries;
        this.referenceSnapshot = referenceSnapshot;
        this.accountReference = accountReference;
        this.verdicts = verdicts;
        this.verdictBatch = verdictBatch;
    }

    /**
     * Tier 1: header presence + declared-vs-carried count (R-19), plus the F51 snapshot
     * capture shared by every partition range.
     *
     * <p>SCRUM-107 repair 2: the reference materialisation check runs HERE, once, against
     * the very snapshot the ranges will read. An unmaterialised account table raises and
     * fails the job rather than turning into an arrival's worth of FAIL_ACCOUNT_NOT_FOUND.
     * The FILE_FATAL arm returns before it, deliberately: a spine that contradicts its own
     * header is decidable without reference data, and it has always been the first answer.
     */
    public HeaderCheck checkHeader(UUID arrivalId) {
        TxHeaderView header = headers.findByArrivalId(arrivalId).orElseThrow();
        long spineCount = entries.countByArrivalId(arrivalId);
        if (spineCount != header.getTxCount()) {
            return new HeaderCheck.FileFatal("spine count " + spineCount + " != declared " + header.getTxCount());
        }
        String clientToken = header.getInitgPty() == null ? "" : header.getInitgPty().strip();
        String asOfTimestamp = referenceSnapshot.snapshotTimestamp();
        String datasetVersion = accountReference.requireMaterialised(asOfTimestamp);
        return new HeaderCheck.Ok(header.getTxCount(), clientToken, asOfTimestamp, datasetVersion);
    }

    /**
     * Tier 2 for one partition range (sequence bounds inclusive), against the account
     * snapshot AS OF {@code asOfTimestamp} (captured once at headerCheck, shared by every
     * range). Rows already verdicted by the dup scan (or an earlier run, R-05 replay) are
     * skipped. Returns the number of FAIL verdicts written by this range.
     */
    public int validateRange(UUID arrivalId, int fromSeq, int toSeq, String asOfTimestamp) {
        Set<Integer> alreadyVerdicted =
                Set.copyOf(verdicts.sequencesForArrivalInRange(arrivalId, fromSeq, toSeq));
        List<TxEntryView> rows =
                entries.findByArrivalIdAndSequenceBetweenOrderBySequence(arrivalId, fromSeq, toSeq)
                        .stream()
                        .filter(row -> !alreadyVerdicted.contains(row.getSequence()))
                        .toList();
        if (rows.isEmpty()) {
            return 0;
        }

        Set<String> accountNumbers = rows.stream()
                .map(TxEntryView::getCreditorAccount)
                .collect(Collectors.toSet());
        Map<String, Account> accountsByNumber = referenceSnapshot.accountsByNumber(asOfTimestamp, accountNumbers);

        List<ValidationLogEntity> batch = new ArrayList<>(rows.size());
        int fails = 0;
        for (TxEntryView row : rows) {
            Entry entry = new Entry(row.getSequence(), row.getE2e(), row.getCreditorAccount(),
                    row.getContractRef(), row.getAmount());
            CtvOutcome outcome = VerdictChain.classify(entry, accountsByNumber);
            if (outcome != CtvOutcome.PASS) {
                fails++;
                // R-38 exclusion visibility: WARN at decision time; validation_log
                // remains the durable record.
                log.warn("excluded stage=PTV arrival={} seq={} e2e={} reason=PTV_{}",
                        arrivalId, row.getSequence(), row.getE2e(), outcome.name());
            }
            batch.add(ValidationLogEntity.of(arrivalId, row.getSequence(), outcome.name()));
        }
        verdictBatch.upsertAll(batch);
        return fails;
    }
}
