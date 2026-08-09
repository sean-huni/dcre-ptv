package za.co.fnb.dcre.ptv.service;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.ptv.data.model.AccountEntity;
import za.co.fnb.dcre.ptv.domain.AccountReferenceManifest;
import za.co.fnb.dcre.ptv.domain.AccountReferenceRow;
import za.co.fnb.dcre.ptv.domain.ReferenceShape;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses the REAL committed artifact rather than one this suite wrote. Hand-built fixtures
 * on both sides of a seam are a drift class green tests cannot see: every other test in this
 * package writes its own artifact and would stay green if the published one changed shape,
 * lost rows, or gained a column.
 *
 * <p>It reads through the same reader the loader uses.
 *
 * <p><b>Two different paths, and they are not interchangeable.</b>
 * {@code infra/dcre-infra/fixtures/reference/account} is the committed SOURCE of the
 * artifact in git, which is what this class parses. The yml default is the STAGED runtime
 * location, derived from the exchange root, and the infra deploy step copies the source
 * there after validating it. Before SCRUM-107 the yml named the fixtures path directly:
 * six {@code ../} hops that resolve from a module directory on a laptop and resolve to
 * nothing in a pod, so a deployed loader had no artifact to read and the account table
 * stayed empty. {@link #theYmlDefaultStagesTheArtifactUnderTheExchangeRoot} pins the new
 * default literally, and the rest of this class keeps parsing the published file, which is
 * the part whose value is that nobody wrote the fixture it reads.
 */
class CommittedArtifactTest {

    private static final Path DIRECTORY = ArtifactFixture.REAL_ROOT.resolve(ArtifactFixture.VERSION);

    private static final Path YML = Path.of("src/main/resources/application.yml");

    private final ManifestReader manifests = new ManifestReader();
    private final AccountCsvReader csv = new AccountCsvReader();
    private final AccountReferenceMapper mapper = new AccountReferenceMapper();

    @Test
    void theCommittedSourceArtifactIsWhereTheInfraStepExpectsToFindIt() {
        assertTrue(Files.isDirectory(DIRECTORY),
                "the committed SOURCE artifact must resolve from the module directory, because"
                        + " the infra staging step reads it from there, looked at: "
                        + DIRECTORY.toAbsolutePath());
    }

    /**
     * The parity pin. A default that cannot resolve in a pod is invisible to every test that
     * hands the loader an explicit root, which is how the previous one survived: the loader
     * was correct, the path it was pointed at did not exist, and the account table stayed
     * empty while the verdict chain reported business rejections for the whole arrival.
     */
    @Test
    void theYmlDefaultStagesTheArtifactUnderTheExchangeRoot() throws Exception {
        String yml = Files.readString(YML).replaceAll("(?m)^\\s*#.*$", " ");

        assertTrue(yml.contains("root: ${DCRE_PTV_ACCOUNT_REFERENCE_ROOT:"
                        + "${DCRE_EXCHANGE_ROOT:../../../../../../infra/dcre-infra/exchange}"
                        + "/reference/account}"),
                "the runtime root must DERIVE from the exchange root, which AGT already injects"
                        + " as DCRE_EXCHANGE_ROOT=/exchange and which is the only volume a stage"
                        + " pod has, so /exchange/reference/account needs no new injection");
        assertTrue(!yml.contains("DCRE_PTV_ACCOUNT_REFERENCE_ROOT:../../../../../../infra/dcre-infra/fixtures"),
                "and it must not name the git fixtures path, which exists only in a checkout");
        // Control: this read CAN see the key at all, so the assertions above are about the
        // value and not about a file that moved or a comment-stripping regex that ate it.
        assertTrue(yml.contains("dataset-version: ${DCRE_PTV_ACCOUNT_DATASET_VERSION:"),
                "control: the reference block is present in the yml this test read");
    }

    @Test
    void itsManifestCarriesAllSevenKeysAndItsChecksumMatchesTheCsvBytes() {
        AccountReferenceManifest manifest = manifests.read(DIRECTORY);

        assertEquals(ArtifactFixture.VERSION, manifest.datasetVersion());
        assertEquals(AccountReferenceManifest.SUPPORTED_SCHEMA_VERSION, manifest.schemaVersion());
        assertEquals("fixture:fnb_dcre_ctv_toolkit/dcre_accounts.csv", manifest.sourceId());
        assertEquals("2026-08-09T00:00:00Z", manifest.effectiveTs().toString());
        assertEquals("2026-08-09T00:00:00Z", manifest.publicationTs().toString());
        assertEquals(110, manifest.rowCount());

        byte[] bytes = csv.readBytes(DIRECTORY);
        assertEquals(manifest.checksum(), csv.sha256(bytes),
                "the digest is over the BYTES of account.csv exactly as committed");
    }

    @Test
    void itCarries110RowsOfWhichExactly10AreThisContextsProjection() {
        AccountReferenceManifest manifest = manifests.read(DIRECTORY);
        List<AccountReferenceRow> rows = csv.parse(csv.readBytes(DIRECTORY));

        assertEquals(manifest.rowCount(), rows.size(), "manifest row.count against parsed data rows");
        assertEquals(110, rows.size());
        assertEquals(10, rows.stream().filter(r -> r.isShape(ReferenceShape.COLLECTIONS)).count(),
                "payments materialises the collections projection, and it is 10 rows");
        assertEquals(100, rows.stream().filter(r -> r.isShape(ReferenceShape.MANDATES)).count(),
                "control: the other 100 are the mandates projection, which this context drops");
        assertEquals(110, rows.stream().map(AccountReferenceRow::accountNumber).distinct().count(),
                "the two sets are disjoint: 110 distinct account numbers over 110 rows");
    }

    @Test
    void everyProjectedRowSatisfiesTheCollectionsShapesOwnConstraints() {
        List<AccountEntity> projected = csv.parse(csv.readBytes(DIRECTORY)).stream()
                .filter(r -> r.isShape(ReferenceShape.COLLECTIONS))
                .map(mapper::toEntity)
                .toList();

        assertEquals(10, projected.size());
        for (AccountEntity row : projected) {
            assertTrue(List.of("FNBRF", "FNBCC").contains(row.productCode()),
                    "chk_account_product: " + row);
            boolean balanceCarrying = "FNBRF".equals(row.productCode());
            assertTrue(balanceCarrying
                            ? row.balance() != null && row.maxCreditLimit() == null
                            : row.maxCreditLimit() != null && row.balance() == null,
                    "chk_account_product_amount: " + row);
            assertTrue(nonNegative(row.balance()) && nonNegative(row.maxCreditLimit()),
                    "chk_account_amounts_nonneg: " + row);
            assertTrue(row.appNo() != null && row.accType() != null && row.branchCode() != null
                            && row.countryId() != null && row.processStatus() != null
                            && row.status() != null && row.ucn() != null && row.clientId() != null,
                    "the collections shape's NOT NULL set: " + row);
        }
    }

    /**
     * The synthetic PAI sentinel must never reach this artifact. PAI used to mint accounts
     * at {@code 999999999.99} "generous so caps pass post-init"; those writes now live in
     * PAI's own unknown_creditor relation and are a LOCAL sentinel, not reference data. A
     * row like that materialised here would make every affordability check pass.
     */
    @Test
    void noSyntheticUnknownCreditorSentinelIsPresent() {
        List<AccountEntity> projected = csv.parse(csv.readBytes(DIRECTORY)).stream()
                .filter(r -> r.isShape(ReferenceShape.COLLECTIONS))
                .map(mapper::toEntity)
                .toList();
        BigDecimal sentinel = new BigDecimal("999999999.99");

        assertTrue(projected.stream().noneMatch(row -> sentinel.compareTo(
                        row.balance() == null ? BigDecimal.ZERO : row.balance()) == 0),
                "PAI's unknown-creditor sentinel balance is not reference data");
        // Control: the assertion above can see a balance at all.
        assertTrue(projected.stream().anyMatch(row -> row.balance() != null),
                "control: projected rows do carry balances, so the check above read real values");
    }

    private static boolean nonNegative(final BigDecimal value) {
        return value == null || value.signum() >= 0;
    }
}
