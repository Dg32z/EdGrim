package cc.watchneko.cloud;

/**
 * The cloud answered, but not with something this contract allows.
 *
 * <p>Thrown instead of returning a half-parsed object, so the caller can make one decision — fail open —
 * without having to inspect a malformed response. Failing open is the caller's policy (ticket #11), not
 * this exception's.
 */
public class CloudContractException extends RuntimeException {

    public CloudContractException(String message) {
        super(message);
    }
}
