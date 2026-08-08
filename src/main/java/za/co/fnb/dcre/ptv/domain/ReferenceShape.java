package za.co.fnb.dcre.ptv.domain;

/**
 * The artifact's projection discriminator, carried in the CSV's first column.
 *
 * <p>The two shapes are DISJOINT sets of accounts and DIFFERENT projections, not two
 * halves of one table: a MANDATES row has no branch code and a COLLECTIONS row has no
 * account type. Payments materialises {@link #COLLECTIONS} only, keeping that shape's
 * own NOT NULL set. Unioning them is exactly what the retired shared reference store
 * did, and the price was a table whose every interesting column was nullable.
 */
public enum ReferenceShape {

    /** 100 rows, consumed by mandates/mrv into dcre_man. */
    MANDATES,

    /** 10 rows, consumed by collections/ctv into dcre_col and by payments/ptv into dcre_pay. */
    COLLECTIONS
}
