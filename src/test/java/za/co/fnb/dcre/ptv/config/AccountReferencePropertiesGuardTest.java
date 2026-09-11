package za.co.fnb.dcre.ptv.config;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.ptv.domain.AccountReferenceLoadException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Red-proofs the guard on {@code artifactDirectory()}, the PTV half of the fleet-wide defect
 * measured on a live cluster on 2026-09-11: a path built from an unsupplied value dies inside the
 * JDK's filesystem code with a NullPointerException naming neither the property nor the stage.
 *
 * <p>This site is the twin of CTV's {@code ReferenceManifestReader.resolveDirectory}, the one site
 * in the fleet that already guarded, and PTV was forked from it before that guard existed. The
 * shape and the message are copied from it deliberately.
 *
 * <p>The blank arm is the one {@code requireDirectory} could never report honestly: a blank root
 * builds a RELATIVE path, so the loader's own "directory is absent" message would have named the
 * process working directory as the artifact directory, which sends an operator to look at the
 * wrong thing entirely.
 */
class AccountReferencePropertiesGuardTest {

    private static AccountReferenceProperties properties(final String root, final String version) {
        AccountReferenceProperties properties = new AccountReferenceProperties();
        properties.setRoot(root);
        properties.setDatasetVersion(version);
        return properties;
    }

    @Test
    void anUnsetRootNamesBothPropertiesAndTheStage() {
        AccountReferenceLoadException thrown = assertThrows(AccountReferenceLoadException.class,
                () -> properties(null, "2026.08.09-001").artifactDirectory());

        assertEquals("PTV requires dcre.ptv.reference.account.root and"
                        + " dcre.ptv.reference.account.dataset-version: root='null'"
                        + " dataset-version='2026.08.09-001'",
                thrown.getMessage(),
                "the message must name the properties and show both values; the"
                        + " NullPointerException it replaces named neither");
    }

    @Test
    void anUnsetDatasetVersionIsRefusedToo() {
        AccountReferenceLoadException thrown = assertThrows(AccountReferenceLoadException.class,
                () -> properties("/dcre/exchange/reference/account", null).artifactDirectory());

        assertEquals("PTV requires dcre.ptv.reference.account.root and"
                        + " dcre.ptv.reference.account.dataset-version:"
                        + " root='/dcre/exchange/reference/account' dataset-version='null'",
                thrown.getMessage(),
                "resolve(null) is the second way this site can raise a NullPointerException and it"
                        + " must be refused by the same guard");
    }

    @Test
    void aBlankRootIsMissingToo() {
        AccountReferenceLoadException thrown = assertThrows(AccountReferenceLoadException.class,
                () -> properties("   ", "2026.08.09-001").artifactDirectory());

        assertEquals("PTV requires dcre.ptv.reference.account.root and"
                        + " dcre.ptv.reference.account.dataset-version: root='   '"
                        + " dataset-version='2026.08.09-001'",
                thrown.getMessage(),
                "a blank root builds a RELATIVE path with no NullPointerException, and the loader"
                        + " would then report the working directory as the absent artifact");
    }

    @Test
    void aBlankDatasetVersionIsMissingToo() {
        AccountReferenceLoadException thrown = assertThrows(AccountReferenceLoadException.class,
                () -> properties("/dcre/exchange/reference/account", "\t").artifactDirectory());

        assertEquals("PTV requires dcre.ptv.reference.account.root and"
                        + " dcre.ptv.reference.account.dataset-version:"
                        + " root='/dcre/exchange/reference/account' dataset-version='\t'",
                thrown.getMessage(),
                "a blank version resolves to the root itself, so the loader would read whatever"
                        + " dataset happened to sit there");
    }

    @Test
    void aConfiguredArtifactStillResolvesToTheSameDirectory() {
        assertEquals(Path.of("/dcre/exchange/reference/account/2026.08.09-001"),
                properties("/dcre/exchange/reference/account", "2026.08.09-001").artifactDirectory(),
                "the healthy path must be byte-identical: <root>/<dataset-version>");
    }

    @Test
    void theGuardNamesTheSamePrefixThatBindingReads() {
        assertEquals("dcre.ptv.reference.account", AccountReferenceProperties.PREFIX,
                "one home for the prefix: the guard's message and @ConfigurationProperties must"
                        + " not be able to drift apart");
    }
}
