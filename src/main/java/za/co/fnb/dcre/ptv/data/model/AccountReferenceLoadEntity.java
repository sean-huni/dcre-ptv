package za.co.fnb.dcre.ptv.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.time.Instant;

/**
 * What one load consumed, written in the SAME transaction as the rows it applied. A load
 * record committed apart from its data would be a claim about a state the database might
 * not be in.
 *
 * <p>{@code rowCount} is the manifest's count for the WHOLE artifact; {@code
 * appliedRowCount} is what THIS context materialised. Keeping both is the point: they
 * differ by design here (110 against 10) and a run where they were equal would mean the
 * projection had stopped projecting.
 */
@Table("account_reference_load")
public class AccountReferenceLoadEntity extends BaseEntity {

    private String datasetVersion;
    private Integer schemaVersion;
    private String sourceId;
    private Instant effectiveTs;
    private Instant publicationTs;
    private Integer rowCount;
    private String checksum;
    private Integer appliedRowCount;
    private Long jobExecutionId;

    public static AccountReferenceLoadEntity of(String datasetVersion, int schemaVersion,
                                                String sourceId, Instant effectiveTs,
                                                Instant publicationTs, int rowCount,
                                                String checksum, int appliedRowCount,
                                                Long jobExecutionId) {
        AccountReferenceLoadEntity e = new AccountReferenceLoadEntity();
        e.datasetVersion = datasetVersion;
        e.schemaVersion = schemaVersion;
        e.sourceId = sourceId;
        e.effectiveTs = effectiveTs;
        e.publicationTs = publicationTs;
        e.rowCount = rowCount;
        e.checksum = checksum;
        e.appliedRowCount = appliedRowCount;
        e.jobExecutionId = jobExecutionId;
        return e;
    }

    public String getDatasetVersion() { return datasetVersion; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public String getSourceId() { return sourceId; }
    public Instant getEffectiveTs() { return effectiveTs; }
    public Instant getPublicationTs() { return publicationTs; }
    public Integer getRowCount() { return rowCount; }
    public String getChecksum() { return checksum; }
    public Integer getAppliedRowCount() { return appliedRowCount; }
    public Long getJobExecutionId() { return jobExecutionId; }
}
