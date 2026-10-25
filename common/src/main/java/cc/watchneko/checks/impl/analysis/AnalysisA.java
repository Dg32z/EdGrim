package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.cloud.FeatureSample;
import cc.watchneko.cloud.InferenceResult;
import cc.watchneko.cloud.Verdict;
import cc.watchneko.cloud.VerdictLabel;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.LogUtil;
import cc.watchneko.utils.anticheat.update.AttackAnchor;
import cc.watchneko.utils.anticheat.update.AttackSignalListener;
import cc.watchneko.utils.anticheat.update.RotationUpdate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Cloud-assisted rotation detection: the plugin samples, the cloud classifies, the plugin decides.
 *
 * <p>The division of authority is the point of this check. The cloud answers per sample with a verdict and
 * a score; those answers only ever become <em>votes</em>. Whether a run of votes amounts to a violation is
 * decided locally, by {@link VerdictVotes}, against thresholds that belong to the server owner. The cloud
 * never punishes and never gets to skip the local rule.
 *
 * <p>The packet thread does three cheap things here: append a rotation update, collect any sample whose
 * window just completed, and drain verdicts that came back. It never waits on the network — a batch is
 * handed to an executor and at most one is outstanding at a time.
 */
@CheckData(
        name = "AnalysisA",
        configName = "AnalysisA",
        description = "cloud-assisted rotation detection",
        decay = 0.05
)
public final class AnalysisA extends Check implements RotationCheck, AttackSignalListener {

    private final RotationWindow window = new RotationWindow();
    private final ArrayDeque<Long> pendingAnchors = new ArrayDeque<>();
    private final List<FeatureSample> batch = new ArrayList<>();
    private final ConcurrentLinkedQueue<VerdictLabel> incomingVerdicts = new ConcurrentLinkedQueue<>();

    private VerdictVotes votes = new VerdictVotes(10, 6);
    private boolean inFlight;
    private boolean debugLog;
    private int batchSize = 8;
    private long sampleCounter;

    public AnalysisA(final PlayerData player) {
        super(player);
        player.rotationSequence.addListener(this);
    }

    @Override
    public void onReload(final ConfigManager config) {
        debugLog = config.getBooleanElse("AnalysisA.debug-log", false);
        batchSize = Math.max(1, config.getIntElse("AnalysisA.batch-size", 8));

        final int windowSize = Math.max(1, config.getIntElse("AnalysisA.analysis-window-size", 10));
        final int minCheatVotes = Math.max(1, config.getIntElse("AnalysisA.min-cheat-votes", 6));
        votes = new VerdictVotes(windowSize, minCheatVotes);

        window.clear();
        pendingAnchors.clear();
        batch.clear();
        incomingVerdicts.clear();
    }

    @Override
    public void onAttack(final AttackAnchor anchor) {
        pendingAnchors.addLast(anchor.rotationSequence());
    }

    @Override
    public void process(final RotationUpdate update) {
        if (!shouldModifyPackets()) {
            return;
        }

        final CloudGateway gateway = CloudGateway.current();
        if (gateway == null) {
            return;
        }

        window.append(player.rotationSequence.position(), update.getDeltaXRot(), update.getDeltaYRot());
        collectCompletedSamples(gateway);
        drainVerdicts();
        flushIfReady(gateway);
    }

    private void collectCompletedSamples(final CloudGateway gateway) {
        while (!pendingAnchors.isEmpty()) {
            final long center = pendingAnchors.peekFirst();
            final Optional<double[]> features = window.extract(center);
            if (features.isEmpty()) {
                return;
            }

            pendingAnchors.removeFirst();
            final FeatureSample sample = new FeatureSample(
                    player.uuid + "-" + (++sampleCounter),
                    gateway.playerHash(player.uuid),
                    features.get());

            // A player being recorded is producing training data, not evidence. The sample goes to the
            // session and is not sent for inference: a recording session must never influence the
            // detection it is meant to help train.
            final Optional<RecordingSession> recording = RecordingSessions.active(player.uuid);
            if (recording.isPresent()) {
                recording.get().add(sample);
                continue;
            }

            batch.add(sample);
        }
    }

    private void flushIfReady(final CloudGateway gateway) {
        if (inFlight || batch.size() < batchSize) {
            return;
        }

        final List<FeatureSample> outgoing = List.copyOf(batch);
        batch.clear();
        inFlight = true;

        gateway.inferAsync(outgoing).whenComplete((result, error) -> {
            inFlight = false;
            if (error != null) {
                debug("cloud call failed, dropping " + outgoing.size() + " samples: " + error.getMessage());
                return;
            }
            for (final Verdict verdict : result.verdicts()) {
                incomingVerdicts.add(verdict.verdict());
            }
        });
    }

    private void drainVerdicts() {
        VerdictLabel label;
        boolean added = false;
        while ((label = incomingVerdicts.poll()) != null) {
            votes.add(label);
            added = true;
        }

        if (!added || !votes.shouldFlag()) {
            if (added) {
                reward();
            }
            return;
        }

        if (flagAndAlert("cloud votes=" + votes.cheatVotes() + "/" + votes.size())) {
            votes.clear();
        }
    }

    private void debug(final String message) {
        if (!debugLog) {
            return;
        }
        LogUtil.info("[AnalysisA] " + player.getName() + " " + message);
    }

    /** Exposed for tests: the vote window in effect. */
    VerdictVotes votes() {
        return votes;
    }

    /** Exposed for tests: the pending sample buffer. */
    List<FeatureSample> pendingBatch() {
        return batch;
    }

    /** Exposed for tests: how many attacks are waiting for their window to complete. */
    int pendingAnchorCount() {
        return pendingAnchors.size();
    }

    /** Exposed for tests: feeds a verdict as the network callback would. */
    void acceptVerdict(final VerdictLabel label) {
        incomingVerdicts.add(label);
    }

    /** Exposed for tests: the window this check keeps. */
    RotationWindow rotationWindow() {
        return window;
    }

    /** Exposed for tests: unused result guard so the compiler keeps the import honest. */
    static void ignore(final InferenceResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result");
        }
    }
}
