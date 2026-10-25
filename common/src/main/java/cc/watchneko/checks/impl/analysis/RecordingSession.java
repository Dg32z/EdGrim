package cc.watchneko.checks.impl.analysis;

import cc.watchneko.cloud.FeatureSample;
import cc.watchneko.cloud.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 */
public final class RecordingSession {

    private final String sessionId;
    private final UUID player;
    private final String playerName;
    private final Label label;
    private final List<FeatureSample> samples = new ArrayList<>();

    public RecordingSession(
            final String sessionId, final UUID player, final String playerName, final Label label) {
        this.sessionId = sessionId;
        this.player = player;
        this.playerName = playerName;
        this.label = label;
    }

    public void add(final FeatureSample sample) {
        samples.add(sample);
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
