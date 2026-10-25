package cc.watchneko.cloud;

/**
 * The cloud's verdict on one sample.
 *
 * @param sampleId the sample this verdict is about
 * @param score    cheat-direction score in {@code [0, 1]}
 * @param verdict  the classification the cloud derived from the score and its thresholds
 */
public record Verdict(String sampleId, double score, VerdictLabel verdict) {
}
