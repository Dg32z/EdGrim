package cc.watchneko.analysis;

import cc.watchneko.cloud.VerdictLabel;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Turns a run of per-sample verdicts into a single decision about whether to raise a violation.
 *
 * <p>These are the <em>voting</em> thresholds, and they deliberately stay in the plugin's own config:
 * they are an alerting policy belonging to the server owner, not a property of the model. The
 * <em>classification</em> thresholds — the ones that turn a score into a {@link VerdictLabel} — travel
 * with the model version instead, because a different model has a different optimum.
 *
 * <p>A verdict of {@link VerdictLabel#UNCERTAIN} counts as neither evidence nor exoneration; it simply
 * occupies a slot in the window. That is why a run of uncertain verdicts never flags, no matter how long.
 */
public final class VerdictVotes {

    private final Deque<VerdictLabel> votes = new ArrayDeque<>();
    private final int windowSize;
    private final int minCheatVotes;

    public VerdictVotes(final int windowSize, final int minCheatVotes) {
        this.windowSize = Math.max(1, windowSize);
        this.minCheatVotes = Math.max(1, Math.min(this.windowSize, minCheatVotes));
    }

    public void add(final VerdictLabel label) {
        votes.addLast(label);
        while (votes.size() > windowSize) {
            votes.removeFirst();
        }
    }

    public boolean isFull() {
        return votes.size() >= windowSize;
    }

    public int cheatVotes() {
        int count = 0;
        for (final VerdictLabel vote : votes) {
            if (vote == VerdictLabel.CHEAT_LIKE) {
                count++;
            }
        }
        return count;
    }

    public int size() {
        return votes.size();
    }

    /** Whether the window is full and carries enough cheat-like verdicts to raise a violation. */
    public boolean shouldFlag() {
        return isFull() && cheatVotes() >= minCheatVotes;
    }

    public void clear() {
        votes.clear();
    }
}
