package cc.watchneko.checks.impl.analysis;

/**
 * How a sample is identified on the wire.
 *
 * <p>The id is built from the tenant-salted player hash, never from the player's UUID. That is the whole
 * invariant this class exists to hold: a sample id travels to the cloud, and hashing the player id would be
 * pointless if the id carried the raw one alongside it.
 *
 * <p>It lives in its own type so the invariant has a name and a test, rather than being one concatenation
 * buried in a check where it can regress silently.
 */
public final class SampleIdentity {

    /** How much of the hash is used as a readable prefix. Long enough to distinguish, short enough to log. */
    private static final int PREFIX_LENGTH = 12;

    private SampleIdentity() {
    }

    /**
     * The wire id for the {@code counter}-th sample of a player.
     *
     * @param playerHash the tenant-salted hash, as {@link CloudGateway#playerHash} produces
     * @param counter    a per-player monotonic counter, so verdicts can be matched back to samples
     */
    public static String of(final String playerHash, final long counter) {
        if (playerHash == null || playerHash.length() < PREFIX_LENGTH) {
            throw new IllegalArgumentException("player hash must be at least " + PREFIX_LENGTH + " characters");
        }
        return playerHash.substring(0, PREFIX_LENGTH) + "-" + counter;
    }
}
