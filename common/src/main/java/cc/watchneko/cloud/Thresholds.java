package cc.watchneko.cloud;

/**
 * The classification thresholds the model was tuned with.
 *
 * <p>These map a score to a conclusion, and they belong to the model version that produced the scores —
 * so they are published alongside it, not configured locally. The other kind of threshold, the one that
 * turns a run of verdicts into a single alert, stays in the plugin's own config and is not part of this
 * contract.
 *
 * @param cheatLike {@code score >= cheatLike} classifies as {@link VerdictLabel#CHEAT_LIKE}
 * @param legitLike {@code score <= legitLike} classifies as {@link VerdictLabel#LEGIT_LIKE}; between the
 *                  two bounds the verdict is {@link VerdictLabel#UNCERTAIN}
 */
public record Thresholds(double cheatLike, double legitLike) {
}
