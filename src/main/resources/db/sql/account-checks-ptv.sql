-- LIQUIBASE-SQL-EXCEPTION (see 003-account-reference.xml for the full statement of
-- why). Liquibase's open-source distribution ships no check-constraint change type,
-- and the column-level checkConstraint attribute is a verified NO-OP in
-- liquibase-core 5.0.3: it parses and CreateTableChange never reads it. These three
-- invariants are the collections shape's own, lifted from the pre-ACS
-- dcre_col.account DDL, and they are what stops a malformed artifact row from being
-- materialised as if it were reference data.
--
-- It lives under db/sql/ rather than beside the changelog because everything under
-- db/changelog/ must be XML (rules/be/java/persistence.md point 22, hook-enforced).
-- The sqlFile changeset in 003-account-reference.xml names this path.
--
-- AccountReferenceConstraintIT proves each one REJECTS a bad row; presence in the
-- catalogue is not evidence that a constraint bites.

ALTER TABLE account ADD CONSTRAINT chk_account_product CHECK (product_code IN ('FNBRF', 'FNBCC'));

-- A balance-carrying product carries a balance and no limit; a credit product carries
-- a limit and no balance. Not "either column may be set": exactly one, and which one
-- is decided by the product.
ALTER TABLE account ADD CONSTRAINT chk_account_product_amount CHECK (
    (product_code = 'FNBRF' AND balance IS NOT NULL AND max_credit_limit IS NULL)
    OR
    (product_code = 'FNBCC' AND max_credit_limit IS NOT NULL AND balance IS NULL)
);

ALTER TABLE account ADD CONSTRAINT chk_account_amounts_nonneg CHECK (
    (balance IS NULL OR balance >= 0)
    AND (max_credit_limit IS NULL OR max_credit_limit >= 0)
);
