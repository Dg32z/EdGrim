package cc.watchneko.cloud;

import java.util.List;

/**
 * The cloud's answer to one inference request.
 *
 * <p>{@code modelId} travels with every answer so a verdict seen in production can be traced back to the
 * model version that produced it, and from there to the dataset that trained it.
 *
 * @param contractVersion the contract version the service answered with
 * @param modelId         the model version that produced these verdicts
 * @param verdicts        one verdict per requested sample, in request order
 */
public record InferenceResult(int contractVersion, String modelId, List<Verdict> verdicts) {
}
