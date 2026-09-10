package za.co.fnb.dcre.ptv;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.ptv.PtvApplication;
import za.co.fnb.dcre.ptv.data.model.TxEntryView;
import za.co.fnb.dcre.ptv.service.VerdictChain;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PTV validates the ENDO payment spine ONLY. The whole point of the split is that
 * payments stops sharing a validator with collections, so a flow discriminator here
 * would mean the split had not happened, and a mandate read here would mean the
 * cross-context coupling had been carried across rather than left behind.
 *
 * <p>Same pattern as PRR's PayFlowOnlyTest, extended for the second thing PTV must
 * not have. A scan for string literals is satisfied by DELETING three literals rather
 * than by removing the concept (PRR review finding I2), so the literal scans below are
 * accompanied by STRUCTURAL assertions read off the classes themselves: the entity's
 * field list, the verdict chain's signature, and the application's imported configs.
 * Each has been seen red on its own.
 */
class PayFlowOnlyTest {

    /**
     * The literal scan. Cheap, and it catches the flow constants and the flow-gated
     * config key by name, but see the class javadoc for what it cannot see alone.
     */
    @Test
    void ptvCarriesNoFlowDiscriminator() throws Exception {
        assertThat(offendingSources("FLOW_PAY", "validatedFlow", "\"COL\"", "dcFlow", "flow-dc"))
                .as("PTV serves one family. A flow branch here means collections logic"
                        + " was carried across instead of left behind")
                .isEmpty();
    }

    /**
     * The MANDATE coupling, which is the collections-only path this fork exists to
     * drop. R-19 makes the mandate gate DC-only and SPEC-ENDO-COLLECTIONS-FLOW.md says
     * plainly "No mandates, no mandate gate". Leaving it in would open a connection to
     * the mandates database on every payments arrival for a snapshot nothing reads.
     */
    @Test
    void noSourceReadsTheMandateProjection() throws Exception {
        assertThat(offendingSources("man_ctv_view", "MandateGate", "MandateProjection",
                "mandates-db-url", "dcre_man"))
                .as("payments carries no bank-registered mandates: no source may name the"
                        + " projection, its datasource, or the mandates database")
                .isEmpty();
    }

    /**
     * The APPLICATION's imported configuration, read off the annotation rather than off
     * the file text. This is the assertion that actually closes the mandates hole: a
     * datasource config reintroduced by {@code @Import} is a live cross-context
     * connection in every Spring context, whatever the comments say.
     */
    @Test
    void applicationImportsNoMandatesDatasource() {
        List<String> imported = Arrays.stream(
                        PtvApplication.class.getAnnotation(org.springframework.context.annotation.Import.class)
                                .value())
                .map(Class::getSimpleName)
                .toList();
        assertThat(imported)
                .as("no mandates datasource may exist in a PTV context")
                .doesNotContain("MandatesDatasourceConfig");
        // Control: this reflection CAN see the imports, so the absence above is a
        // missing entry and not an empty read of the wrong annotation.
        assertThat(imported)
                .as("control: the reflection reads the real @Import list")
                .contains("JdbcConfig", "BatchJdbcConfig", "HeartbeatDatasourceConfig");
    }

    /**
     * The MAPPED spine shape, read off the class itself rather than off its text.
     * A field named mandateRef survives any amount of comment rewording, and it is the
     * one that would put the mandate tier back into the verdict chain.
     */
    @Test
    void spineEntryViewDeclaresNoMandateField() {
        List<String> fields = Arrays.stream(TxEntryView.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertThat(fields)
                .as("PTV maps no mandate_ref: there is no gate to feed it to")
                .doesNotContain("mandateRef");
        // Control: the reflection reads real fields.
        assertThat(fields)
                .as("control: the reflection reads real fields")
                .contains("arrivalId", "creditorAccount", "amount");
    }

    /**
     * The VERDICT CHAIN's signature. CTV's classify takes a projection map and a
     * dcFlow boolean; both are the discriminator in argument form, and either one back
     * on this method is the whole coupling back.
     */
    @Test
    void verdictChainTakesNoProjectionMapAndNoFlowFlag() {
        List<Method> classify = Arrays.stream(VerdictChain.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("classify"))
                .toList();
        assertThat(classify).as("control: classify exists to be inspected").hasSize(1);
        List<String> parameterTypes = Arrays.stream(classify.get(0).getParameterTypes())
                .map(Class::getSimpleName)
                .toList();
        assertThat(parameterTypes)
                .as("the entry and the account snapshot decide a payment verdict, nothing else")
                .containsExactly("Entry", "Map");
    }

    /**
     * The RESOURCES, which a java-only walk cannot reach. A flow key or a mandates
     * datasource wired through application.yml is exactly as much of a discriminator
     * as a Java constant, and a changeset re-creating a mandate table is worse.
     */
    @Test
    void noResourceReintroducesAFlowKeyOrAMandateStore() throws Exception {
        try (var paths = Files.walk(Path.of("src/main/resources"))) {
            var offenders = paths.filter(Files::isRegularFile)
                    .filter(p -> containsAny(p, "flow-dc", "columnName=\"flow\"", "name=\"flow\"",
                            "mandates-db", "tableName=\"mandate\"", "dcre_man"))
                    .toList();
            assertThat(offenders)
                    .as("no config may carry a flow key or a mandates datasource, and no"
                            + " changeset may create a mandate table in dcre_pay")
                    .isEmpty();
        }
    }

    /**
     * The datasource this service targets is the payments one, and only that one.
     * Comments stripped, for the reason given on {@link #offendingSources}: the yml
     * explains that it points at dcre_pay and NOT dcre_col, and that explanation is
     * worth keeping.
     */
    @Test
    void theConfiguredDatabaseIsDcrePay() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"))
                .replaceAll("(?m)^\\s*#.*$", " ");
        assertThat(yml)
                .as("PTV writes dcre_pay; the database is the family discriminator now")
                .contains("${DCRE_DB_URL:jdbc:postgresql://localhost:26257/dcre_pay")
                .doesNotContain("dcre_col");
    }

    /**
     * Scans CODE, with comments stripped. The javadoc in this service explains at
     * length what it deliberately does not do, and naming the removed thing in prose
     * is the point of that javadoc; a scan that counted those mentions would push
     * every author to delete the explanation rather than the coupling, which is the
     * opposite of the outcome wanted. The structural assertions above are what make
     * this safe: they read the annotation, the fields and the signature, none of
     * which a comment can satisfy.
     */
    private static List<Path> offendingSources(String... tokens) throws Exception {
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> containsAny(p, tokens))
                    .toList();
        }
    }

    private static boolean containsAny(Path path, String... tokens) {
        try {
            String source = stripComments(Files.readString(path));
            return Arrays.stream(tokens).anyMatch(source::contains);
        } catch (Exception e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }

    /** Block and line comments out; string literals are left alone on purpose. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
