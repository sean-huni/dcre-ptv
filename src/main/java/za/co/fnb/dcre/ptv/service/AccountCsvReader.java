package za.co.fnb.dcre.ptv.service;

import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ptv.domain.AccountReferenceLoadException;
import za.co.fnb.dcre.ptv.domain.AccountReferenceRow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Reads {@code account.csv} as BYTES first and text second, because the manifest's digest
 * is over the bytes exactly as committed and re-encoding before hashing would make the
 * check answer a different question.
 *
 * <p>The header must match the artifact's 18 columns EXACTLY and in order. A column set
 * that merely contains the right names is not the same file, and positional parsing
 * against a reordered header is how a branch code ends up in a status column.
 */
@Component
public class AccountCsvReader {

    static final String FILE_NAME = "account.csv";

    public byte[] readBytes(final Path artifactDirectory) {
        Path file = artifactDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            throw new AccountReferenceLoadException(
                    "account reference csv not found at " + file.toAbsolutePath());
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new AccountReferenceLoadException(
                    "account reference csv unreadable at " + file.toAbsolutePath(), e);
        }
    }

    public String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    /** Parses the verified bytes into raw rows; the header is asserted, never assumed. */
    public List<AccountReferenceRow> parse(final byte[] bytes) {
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        if (lines.isEmpty()) {
            throw new AccountReferenceLoadException("account reference csv is empty: no header row");
        }
        String header = String.join(",", AccountReferenceRow.HEADER);
        if (!header.equals(lines.getFirst())) {
            throw new AccountReferenceLoadException("account reference csv header mismatch:"
                    + " expected '" + header + "' but found '" + lines.getFirst() + "'");
        }
        List<AccountReferenceRow> rows = new ArrayList<>(lines.size() - 1);
        for (int i = 1; i < lines.size(); i++) {
            rows.add(row(lines.get(i), i + 1));
        }
        return rows;
    }

    private static AccountReferenceRow row(final String line, final int lineNumber) {
        String[] cells = line.split(",", -1);
        int expected = AccountReferenceRow.HEADER.size();
        if (cells.length != expected) {
            throw new AccountReferenceLoadException("account reference csv line " + lineNumber
                    + " has " + cells.length + " cells, expected " + expected);
        }
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i].strip();
            cells[i] = cell.isEmpty() ? null : cell;   // an empty cell means ABSENT
        }
        return new AccountReferenceRow(cells[0], cells[1], cells[2], cells[3], cells[4], cells[5],
                cells[6], cells[7], cells[8], cells[9], cells[10], cells[11], cells[12], cells[13],
                cells[14], cells[15], cells[16], cells[17]);
    }
}
