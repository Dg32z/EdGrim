package cc.watchneko.cloud;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One admin's recording of one player, from the moment it starts to the moment it is uploaded or thrown away.
 *
 * <p>The label is fixed when the session starts, not afterwards. That is the whole point of recording this
 * way: the admin knows what they are about to record — a known cheat client, or a known clean player — and
 * saying so up front is what removes the "sort the JSON files into folders later" step that the previous
 * implementation depended on and nobody ever did.
 *
 * <p>A session holds samples in memory and does nothing with them until it is resolved. Nothing leaves the
 * server until an admin explicitly uploads, which is why discarding a bad recording is free.
 *
 * <p><b>Stopping is not the same as resolving.</b> {@link #close()} freezes the sample set when the admin
 * asks to stop, but the session stays in the registry until an upload actually succeeds or the admin
 * discards it. A failed upload — a network blip, a bad key, a bug in the serializer — must not destroy
 * minutes of recording; the admin runs {@code record stop <player> upload} again and the same samples are
 * retried. Samples arriving after the close are refused rather than appended, so what gets uploaded is
 * exactly what the admin stopped.
 */
public final class RecordingSession {

    private final String sessionId;
    private final UUID player;
    private final String playerName;
    private final Label label;
    private final List<FeatureSample> samples = new ArrayList<>();
    private final AtomicInteger interruptions = new AtomicInteger();
    private final AtomicBoolean uploading = new AtomicBoolean();
    private volatile boolean paused;
    private volatile boolean closed;

    public RecordingSession(
            final String sessionId, final UUID player, final String playerName, final Label label) {
        this.sessionId = sessionId;
        this.player = player;
        this.playerName = playerName;
        this.label = label;
    }

    /**
     * Adds a sample, unless the session is paused or closed.
     *
     * <p>A paused session is one whose player has left the server. Nothing would arrive while they are gone
     * anyway, so the guard is defensive rather than load-bearing — but it makes the invariant explicit: a
     * paused recording never grows. A closed session has already been stopped; growing it after that would
     * upload samples the admin never saw the count of.
     *
     * @return whether the sample was accepted into this session
     */
    public boolean add(final FeatureSample sample) {
        if (paused || closed) {
            return false;
        }
        samples.add(sample);
        return true;
    }

    /**
     * Freezes the session because the admin stopped recording.
     *
     * <p>Idempotent: a retry after a failed upload closes an already-closed session. The samples are
     * untouched, which is the whole point — stopping decides what the recording <em>is</em>, not what
     * happens to it.
     */
    public void close() {
        closed = true;
    }

    /** Whether the recording has been stopped. A closed session still holds its samples until resolved. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Claims the right to upload this session, so two {@code stop ... upload} commands in a row cannot send
     * the same batch twice.
     *
     * @return false when an upload is already in flight
     */
    public boolean beginUpload() {
        return uploading.compareAndSet(false, true);
    }

    /** Releases the upload claim, whether the attempt succeeded or failed. */
    public void endUpload() {
        uploading.set(false);
    }

    /**
     * Suspends the recording because the player left. The session survives, and {@link #resume()} picks it
     * back up — an admin should not have to re-run {@code record start} because someone disconnected.
     *
     * <p>Counts interruptions, so {@code record stop} can tell the admin their recording has a hole in it.
     */
    public void pause() {
        if (paused) {
            return;
        }
        paused = true;
        interruptions.incrementAndGet();
    }

    /** Resumes a paused recording, e.g. when the player reconnects. */
    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    /** How many times the player left mid-recording. */
    public int interruptions() {
        return interruptions.get();
    }

    public String sessionId() {
        return sessionId;
    }

    public UUID player() {
        return player;
    }

    public String playerName() {
        return playerName;
    }

    public Label label() {
        return label;
    }

    public List<FeatureSample> samples() {
        return List.copyOf(samples);
    }

    public int size() {
        return samples.size();
    }
}
