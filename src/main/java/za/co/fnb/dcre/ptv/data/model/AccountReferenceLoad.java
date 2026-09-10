package za.co.fnb.dcre.ptv.data.model;

/**
 * The READ projection of the newest {@code account_reference_load} row: what the account
 * table currently holds, and which dataset put it there.
 *
 * <p>Deliberately separate from {@link AccountReferenceLoadEntity}, which is the WRITE
 * model the loader saves. The verdict path reads two columns AS OF a snapshot timestamp and
 * has no business carrying the manifest, the checksum or the audit trio around with it.
 *
 * <p>{@code appliedRowCount} is what THIS context materialised, and the load record is
 * written in the SAME transaction as the rows it applied, so a record saying 10 is a
 * statement about the table's current contents rather than about a run that once happened.
 */
public record AccountReferenceLoad(String datasetVersion, int appliedRowCount) {
}
