package za.co.fnb.dcre.ptv.service;

import za.co.fnb.dcre.ptv.config.AccountReferenceProperties;
import za.co.fnb.dcre.ptv.domain.AccountReferenceRow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds artifacts on disk for the failure-path proofs. Every artifact it writes is VALID
 * by default, checksum included, so a test that wants one thing wrong changes exactly that
 * one thing and nothing else can be the reason the load failed.
 *
 * <p>The 18 cells of a row are passed positionally and the count is CHECKED here, because a
 * miscounted comma in a fixture produces a header/arity failure that reads exactly like the
 * defect under test.
 */
public final class ArtifactFixture {

    public static final String VERSION = "2026.08.09-001";

    /** The real committed artifact, at the same relative root the yml default names. */
    public static final Path REAL_ROOT =
            Path.of("../../../../../../infra/dcre-infra/fixtures/reference/account");

    private ArtifactFixture() {
    }

    public static String header() {
        return String.join(",", AccountReferenceRow.HEADER);
    }

    public static String row(final String... cells) {
        int expected = AccountReferenceRow.HEADER.size();
        if (cells.length != expected) {
            throw new IllegalArgumentException(
                    "fixture row has " + cells.length + " cells, the artifact header has " + expected);
        }
        return String.join(",", cells);
    }

    /** A well-formed balance-carrying collections row. */
    public static String collections(final String accountNumber, final String balance) {
        return row("COLLECTIONS", accountNumber, "FNBRF", "AAUT", "", accountNumber, "CACC",
                "250205", balance, "", "", "1", "false", "false", "ACTIVE", "", accountNumber, "2");
    }

    /** A collections row whose product_code no CHECK constraint admits. */
    public static String collectionsWithUnknownProduct(final String accountNumber) {
        return row("COLLECTIONS", accountNumber, "FNBXX", "AAUT", "", accountNumber, "CACC",
                "250205", "10.00", "", "", "1", "false", "false", "ACTIVE", "", accountNumber, "2");
    }

    /** A mandates-shape row: no branch code, no amounts, an account type instead. */
    public static String mandates(final String accountNumber) {
        return row("MANDATES", accountNumber, "FNBRF", "AAUT", "SAV", "", "", "", "", "", "", "",
                "", "", "", "", "", "");
    }

    public static String csv(final String... rows) {
        return header() + "\n" + String.join("\n", rows) + "\n";
    }

    /**
     * Writes {@code <root>/<version>/} with a manifest that is correct for the csv given,
     * then applies {@code tweak} so a test can corrupt exactly one key.
     */
    public static Path write(final Path root, final String version, final String csv,
                      final Consumer<Map<String, String>> tweak) {
        try {
            Path directory = Files.createDirectories(root.resolve(version));
            byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
            Files.write(directory.resolve("account.csv"), bytes);
            Map<String, String> keys = new LinkedHashMap<>();
            keys.put("dataset.version", version);
            keys.put("schema.version", "1");
            keys.put("source.id", "fixture:test/ArtifactFixture");
            keys.put("effective.ts", "2026-08-09T00:00:00Z");
            keys.put("publication.ts", "2026-08-09T00:00:00Z");
            keys.put("row.count", String.valueOf(csv.lines().count() - 1));
            keys.put("checksum.sha256", sha256(bytes));
            tweak.accept(keys);
            try (Writer out = Files.newBufferedWriter(directory.resolve("manifest.properties"))) {
                for (Map.Entry<String, String> key : keys.entrySet()) {
                    out.write(key.getKey() + "=" + key.getValue() + "\n");
                }
            }
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static AccountReferenceProperties properties(final Path root, final String version,
                                                 final Duration maxAge) {
        AccountReferenceProperties properties = new AccountReferenceProperties();
        properties.setRoot(root.toString());
        properties.setDatasetVersion(version);
        properties.setMaxAge(maxAge);
        return properties;
    }

    /** A fresh, empty artifact root, so no earlier fixture can be the reason a load behaved. */
    public static Path tempRoot(final String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
