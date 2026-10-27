package cc.watchneko.cloud;

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
     * <p>Use this for samples sent to inference. A verdict is consumed immediately and never stored under
     * this id, so collisions across sessions do not matter there.
     *
     * @param playerHash the tenant-salted hash, as {@link CloudGateway#playerHash} produces
     * @param counter    a per-player monotonic counter, so verdicts can be matched back to samples
     */
    public static String of(final String playerHash, final long counter) {
        return prefix(playerHash) + "-" + counter;
    }

    /**
     * The wire id for the {@code counter}-th sample of one recording session.
     *
     * <p>Recording samples are stored under their id, and the cloud rejects an id it already has — so this
     * id has to be unique across every session the tenant ever uploads, not merely within one player's
     * connection. A plain per-player counter is not enough: it lives on the player's check instance, which
     * is rebuilt on every login, so a player who reconnects restarts at 1 and re-sends ids that are already
     * stored. The whole second recording is then rejected as duplicates.
     *
     * <p>Scoping to the session id fixes that, because the session id is a random UUID minted by
     * {@code record start} and is therefore unique across reconnects, restarts and servers.
     *
     * @param playerHash the tenant-salted hash, for the readable prefix
     * @param sessionId  the recording session the sample belongs to
     * @param counter    a monotonic counter within that session
     */
    public static String forRecording(final String playerHash, final String sessionId, final long counter) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("a recording sample needs its session id");
        }
        return prefix(playerHash) + "-" + sessionId + "-" + counter;
    }

    private static String prefix(final String playerHash) {
        if (playerHash == null || playerHash.length() < PREFIX_LENGTH) {
            throw new IllegalArgumentException("player hash must be at least " + PREFIX_LENGTH + " characters");
        }
        return playerHash.substring(0, PREFIX_LENGTH);
    }
}
