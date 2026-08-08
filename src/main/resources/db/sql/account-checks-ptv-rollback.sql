-- LIQUIBASE-SQL-EXCEPTION: the rollback half of account-checks-ptv.sql. Liquibase
-- ships no typed tag to drop a check constraint either, so the reverse of a raw-SQL
-- forward change is raw SQL. Dropped in reverse order of creation, purely so a reader
-- can pair the two files line for line.

ALTER TABLE account DROP CONSTRAINT chk_account_amounts_nonneg;
ALTER TABLE account DROP CONSTRAINT chk_account_product_amount;
ALTER TABLE account DROP CONSTRAINT chk_account_product;
