package za.co.fnb.dcre.ptv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import za.co.fnb.dcre.ptv.config.AccountReferenceProperties;
import za.co.fnb.dcre.ptv.data.model.AccountEntity;
import za.co.fnb.dcre.ptv.domain.AccountReferenceLoadException;
import za.co.fnb.dcre.ptv.domain.AccountReferenceManifest;
import za.co.fnb.dcre.ptv.domain.AccountReferenceRow;
import za.co.fnb.dcre.ptv.domain.ReferenceShape;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The loader contract, in its mandated order: resolve, manifest, dataset version, schema
 * version, checksum, parse, freshness, project, apply. Every arm that says FAIL raises
 * {@link AccountReferenceLoadException}; none of them degrades to "carry on with what is
 * already in the table", because a loader that finds nothing and reports success is the
 * exact defect class this wave removes.
 */
@Service
public class AccountReferenceLoadService {

    /** Payments materialises the collections projection. See {@link ReferenceShape}. */
    static final ReferenceShape SHAPE = ReferenceShape.COLLECTIONS;

    private static final Logger log = LoggerFactory.getLogger(AccountReferenceLoadService.class);

    private final AccountReferenceProperties properties;
    private final ManifestReader manifests;
    private final AccountCsvReader csv;
    private final AccountReferenceMapper mapper;
    private final AccountReferenceApplier applier;

    public AccountReferenceLoadService(final AccountReferenceProperties properties,
                                       final ManifestReader manifests, final AccountCsvReader csv,
                                       final AccountReferenceMapper mapper,
                                       final AccountReferenceApplier applier) {
        this.properties = properties;
        this.manifests = manifests;
        this.csv = csv;
        this.mapper = mapper;
        this.applier = applier;
    }

    public int load(final Long jobExecutionId) {
        Path directory = requireDirectory();
        AccountReferenceManifest manifest = manifests.read(directory);
        requireDeclaredVersion(manifest);
        requireSupportedSchema(manifest);
        byte[] bytes = csv.readBytes(directory);
        requireChecksum(manifest, bytes);
        List<AccountReferenceRow> rows = csv.parse(bytes);
        requireRowCount(manifest, rows.size());
        requireFreshness(manifest);
        List<AccountEntity> projected = project(rows);
        int applied = applier.apply(manifest, projected, jobExecutionId);
        log.info("account-reference-loaded stage=PTV dataset={} schema={} shape={} applied={} of={}",
                manifest.datasetVersion(), manifest.schemaVersion(), SHAPE, applied,
                manifest.rowCount());
        return applied;
    }

    private Path requireDirectory() {
        Path directory = properties.artifactDirectory();
        if (!Files.isDirectory(directory)) {
            throw new AccountReferenceLoadException("account reference artifact directory is absent"
                    + " or not a directory: " + directory.toAbsolutePath());
        }
        return directory;
    }

    private void requireDeclaredVersion(final AccountReferenceManifest manifest) {
        String declared = properties.getDatasetVersion();
        if (!declared.equals(manifest.datasetVersion())) {
            throw new AccountReferenceLoadException("account reference dataset.version mismatch:"
                    + " this service expects '" + declared + "' but the manifest declares '"
                    + manifest.datasetVersion() + "'");
        }
    }

    private void requireSupportedSchema(final AccountReferenceManifest manifest) {
        int supported = AccountReferenceManifest.SUPPORTED_SCHEMA_VERSION;
        if (manifest.schemaVersion() != supported) {
            throw new AccountReferenceLoadException("account reference schema.version mismatch:"
                    + " this loader supports " + supported + " but the manifest declares "
                    + manifest.schemaVersion());
        }
    }

    private void requireChecksum(final AccountReferenceManifest manifest, final byte[] bytes) {
        String actual = csv.sha256(bytes);
        if (!manifest.checksum().equalsIgnoreCase(actual)) {
            throw new AccountReferenceLoadException("account reference checksum.sha256 mismatch:"
                    + " the manifest declares " + manifest.checksum() + " but account.csv hashes to "
                    + actual);
        }
    }

    private void requireRowCount(final AccountReferenceManifest manifest, final int parsed) {
        if (manifest.rowCount() != parsed) {
            throw new AccountReferenceLoadException("account reference row.count mismatch:"
                    + " the manifest declares " + manifest.rowCount() + " data rows but account.csv"
                    + " carries " + parsed);
        }
    }

    /**
     * Wired and INERT until A-4 defines the freshness contract. Unset means the loader says
     * so at INFO on EVERY run and proceeds; nothing here invents a number.
     */
    private void requireFreshness(final AccountReferenceManifest manifest) {
        Duration maxAge = properties.getMaxAge();
        if (maxAge == null) {
            log.info("account-reference-freshness-inert stage=PTV dataset={} publication={}"
                            + " reason=dcre.ptv.reference.account.max-age unset pending A-4",
                    manifest.datasetVersion(), manifest.publicationTs());
            return;
        }
        Instant oldestAllowed = Instant.now().minus(maxAge);
        if (manifest.publicationTs().isBefore(oldestAllowed)) {
            throw new AccountReferenceLoadException("account reference artifact is stale:"
                    + " publication.ts " + manifest.publicationTs() + " is older than the configured"
                    + " max-age " + maxAge + " (oldest allowed " + oldestAllowed + ")");
        }
    }

    private List<AccountEntity> project(final List<AccountReferenceRow> rows) {
        List<AccountEntity> projected = rows.stream()
                .filter(row -> row.isShape(SHAPE))
                .map(mapper::toEntity)
                .toList();
        if (projected.isEmpty()) {
            throw new AccountReferenceLoadException("account reference projection for shape " + SHAPE
                    + " is empty: the artifact carries " + rows.size() + " rows and none of this"
                    + " context's shape, so there is nothing to materialise");
        }
        return projected;
    }
}
