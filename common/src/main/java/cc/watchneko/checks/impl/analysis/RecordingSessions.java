package cc.watchneko.checks.impl.analysis;

import cc.watchneko.cloud.Label;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The recording sessions currently in progress, one per player.
 *
 * <p>Shared between the command that starts and stops a session and the check that feeds it, so it is a
 * single process-wide registry rather than something owned by either side.
 *
 * <p>Starting a session for a player who is already being recorded is refused rather than silently
 * replacing the earlier one: losing a half-finished recording because someone typed the command twice is
 * exactly the kind of quiet data loss that makes a dataset untrustworthy.
 */
public final class RecordingSessions {

    private static final Map<UUID, RecordingSession> ACTIVE = new ConcurrentHashMap<>();

    private RecordingSessions() {
    }

    /**
     * Starts recording a player, or returns empty when one is already running for them.
     */
    public static Optional<RecordingSession> start(
            final UUID player, final String playerName, final Label label, final String sessionId) {
        final RecordingSession session = new RecordingSession(sessionId, player, playerName, label);
        return ACTIVE.putIfAbsent(player, session) == null
                ? Optional.of(session)
                : Optional.empty();
    }

    /** Ends a player's session and hands it back, or empty when they were not being recorded. */
    public static Optional<RecordingSession> stop(final UUID player) {
        return Optional.ofNullable(ACTIVE.remove(player));
    }

    public static Optional<RecordingSession> active(final UUID player) {
        return Optional.ofNullable(ACTIVE.get(player));
    }

    public static boolean isRecording(final UUID player) {
        return ACTIVE.containsKey(player);
    }

    /** Forgets every session, e.g. when the plugin disables. Samples are not uploaded. */
    public static void clear() {
        ACTIVE.clear();
    }
}
