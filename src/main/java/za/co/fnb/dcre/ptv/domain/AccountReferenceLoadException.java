package za.co.fnb.dcre.ptv.domain;

import java.io.Serial;

/**
 * A load that must not proceed. Every arm of the loader contract that says FAIL raises
 * this, carrying a message that names the specific discrepancy and both of whatever two
 * values disagreed, because "the load failed" is not something an operator can act on.
 *
 * <p>It is deliberately NOT a {@code TransientDataAccessException} and carries no retry
 * semantics: an artifact whose checksum does not match its manifest is not made to match
 * by trying again.
 */
public class AccountReferenceLoadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AccountReferenceLoadException(final String message) {
        super(message);
    }

    public AccountReferenceLoadException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
