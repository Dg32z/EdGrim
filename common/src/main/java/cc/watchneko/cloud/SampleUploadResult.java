package cc.watchneko.cloud;

/**
 * The cloud's receipt for one uploaded batch.
 *
 * <p>{@code accepted + rejected} equals the number of samples sent. The plugin does not need to know why
 * a sample was rejected — only that the batch has been dealt with.
 *
 * @param contractVersion    the contract version the service answered with
 * @param recordingSessionId the session the batch belonged to
 * @param accepted           samples stored
 * @param rejected           samples the cloud would not store
 */
public record SampleUploadResult(
        int contractVersion, String recordingSessionId, int accepted, int rejected) {
}
