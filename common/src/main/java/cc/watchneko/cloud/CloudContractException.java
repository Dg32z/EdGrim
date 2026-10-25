package cc.watchneko.cloud;

/**
 * The cloud answered, but not with something this contract allows.
 *
 * <p>Thrown instead of returning a half-parsed object, so the caller can make one decision — fail open —
 * without having to inspect a malformed response. Failing open is the caller's policy (ticket #11), not
 * this exception's.
 */
public class CloudContractException extends RuntimeException {

    private final int status;

    public CloudContractException(int status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * The HTTP status the cloud answered with.
     *
     * <p>Carried because "over quota" (429) is not a failure the caller should treat like an outage: it
     * means back off and send less, not that something is broken.
     */
    public int status() {
        return status;
    }
}
