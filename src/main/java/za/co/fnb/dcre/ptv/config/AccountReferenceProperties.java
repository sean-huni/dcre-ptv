package za.co.fnb.dcre.ptv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import za.co.fnb.dcre.ptv.domain.AccountReferenceLoadException;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Where the account reference artifact is and which dataset this service expects.
 *
 * <p>{@code datasetVersion} is DECLARED, never discovered. There is no "pick the newest
 * directory" behaviour anywhere in this loader, because that is a fail-open: a publisher
 * mistake would be adopted silently by every consumer at once, and no consumer's recorded
 * version would mean anything.
 *
 * <p>{@code maxAge} has NO DEFAULT, in code, in yml, or in any test resource. The
 * freshness contract belongs to A-4 and is not this loader's to invent. Left unset the
 * check is wired and INERT and says so at INFO on every run; set, it enforces.
 */
@ConfigurationProperties(prefix = AccountReferenceProperties.PREFIX)
public class AccountReferenceProperties {

    /** The configuration prefix, so the guard below names exactly what binding reads. */
    static final String PREFIX = "dcre.ptv.reference.account";

    private String root;
    private String datasetVersion;
    private Duration maxAge;

    /**
     * The directory that IS this artifact: {@code <root>/<dataset-version>}.
     *
     * <p>Both halves are checked here, naming the property and the value it held, because
     * unguarded an unset {@code root} dies inside the JDK's filesystem code with a
     * NullPointerException that names neither property nor this stage, and a blank one builds
     * a relative path with no NullPointerException at all, so {@code requireDirectory} then
     * reports the WORKING DIRECTORY as the absent artifact directory. This mirrors CTV's
     * {@code ReferenceManifestReader.resolveDirectory}, the one site in the fleet that already
     * guarded, and which this twin was forked from before that guard existed.
     */
    public Path artifactDirectory() {
        if (root == null || root.isBlank() || datasetVersion == null || datasetVersion.isBlank()) {
            throw new AccountReferenceLoadException("PTV requires " + PREFIX + ".root and "
                    + PREFIX + ".dataset-version: root='" + root + "' dataset-version='"
                    + datasetVersion + "'");
        }
        return Path.of(root).resolve(datasetVersion);
    }

    public String getRoot() { return root; }
    public void setRoot(String root) { this.root = root; }

    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }

    public Duration getMaxAge() { return maxAge; }
    public void setMaxAge(Duration maxAge) { this.maxAge = maxAge; }
}
