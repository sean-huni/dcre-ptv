package za.co.fnb.dcre.ptv.service;

import org.springframework.stereotype.Component;
import za.co.fnb.dcre.ptv.domain.AccountReferenceLoadException;
import za.co.fnb.dcre.ptv.domain.AccountReferenceManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/**
 * Reads {@code manifest.properties} with {@link Properties}. That file is DATA, not Spring
 * configuration, which is why the yml-only rule does not reach it and why nothing binds it
 * into the environment.
 *
 * <p>All seven keys are mandatory and a missing one FAILS naming the key. There are no
 * defaults here at all: every default would be this loader inventing a fact about somebody
 * else's dataset.
 */
@Component
public class ManifestReader {

    static final String FILE_NAME = "manifest.properties";

    public AccountReferenceManifest read(final Path artifactDirectory) {
        Path file = artifactDirectory.resolve(FILE_NAME);
        Properties properties = load(file);
        return new AccountReferenceManifest(
                required(properties, "dataset.version", file),
                integer(properties, "schema.version", file),
                required(properties, "source.id", file),
                instant(properties, "effective.ts", file),
                instant(properties, "publication.ts", file),
                integer(properties, "row.count", file),
                required(properties, "checksum.sha256", file));
    }

    private static Properties load(final Path file) {
        if (!Files.isRegularFile(file)) {
            throw new AccountReferenceLoadException(
                    "account reference manifest not found at " + file.toAbsolutePath());
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new AccountReferenceLoadException(
                    "account reference manifest unreadable at " + file.toAbsolutePath(), e);
        }
        return properties;
    }

    private static String required(final Properties properties, final String key, final Path file) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new AccountReferenceLoadException(
                    "account reference manifest is missing mandatory key '" + key + "' in "
                            + file.toAbsolutePath());
        }
        return value.strip();
    }

    private static int integer(final Properties properties, final String key, final Path file) {
        String value = required(properties, key, file);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AccountReferenceLoadException("account reference manifest key '" + key
                    + "' is not an integer: '" + value + "' in " + file.toAbsolutePath(), e);
        }
    }

    private static Instant instant(final Properties properties, final String key, final Path file) {
        String value = required(properties, key, file);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new AccountReferenceLoadException("account reference manifest key '" + key
                    + "' is not an ISO-8601 instant: '" + value + "' in " + file.toAbsolutePath(), e);
        }
    }
}
