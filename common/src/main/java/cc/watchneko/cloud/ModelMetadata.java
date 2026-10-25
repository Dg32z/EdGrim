package cc.watchneko.cloud;

/**
 * What the cloud tells the plugin about the model currently in effect.
 *
 * <p>{@code modelId} and {@code datasetId} together are the traceability chain: a verdict names a model,
 * a model names the dataset it was trained on. {@code featureDimensions} is the one thing the plugin must
 * cross-check against its own feature vector before sending anything — a model expecting a different
 * vector length would silently misjudge every sample.
 *
 * @param contractVersion   the contract version the service answered with
 * @param modelId           the model version currently in effect
 * @param datasetId         the dataset version that trained it
 * @param featureDimensions the feature vector length the model accepts
 * @param thresholds        the classification thresholds that model was tuned with
 */
public record ModelMetadata(
        int contractVersion,
        String modelId,
        String datasetId,
        int featureDimensions,
        Thresholds thresholds) {
}
