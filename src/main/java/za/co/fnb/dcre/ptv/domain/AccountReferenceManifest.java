package za.co.fnb.dcre.ptv.domain;

import java.time.Instant;

/**
 * The seven mandatory keys of an artifact's {@code manifest.properties}, parsed. All
 * seven are required: a manifest missing one is not a manifest with a default, it is an
 * artifact whose publisher did not finish, and a loader that guesses the missing value
 * turns that into silent data.
 *
 * <p>{@code checksum} is over the BYTES of {@code account.csv} exactly as committed, and
 * {@code rowCount} counts DATA rows, excluding the header.
 */
public record AccountReferenceManifest(String datasetVersion,
                                       int schemaVersion,
                                       String sourceId,
                                       Instant effectiveTs,
                                       Instant publicationTs,
                                       int rowCount,
                                       String checksum) {

    /** The only schema version this loader understands. Bumping the artifact bumps this. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
}
