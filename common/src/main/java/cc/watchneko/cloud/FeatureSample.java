package cc.watchneko.cloud;

import java.util.Objects;

/**
 * One sample on the wire: a feature vector plus the identifiers needed to route it and match it back.
 *
 * <p>This is the only form in which data leaves the server. Raw angles are not part of the contract, and
 * the player id is a hash under the tenant's salt, not the player's identity.
 *
 * @param sampleId   plugin-generated id, so a verdict can be matched back to the sample that caused it
 * @param playerHash player id hashed under the tenant salt
 * @param features   normalised feature vector, exactly {@link CloudContract#FEATURE_DIMENSIONS} long
 */
public record FeatureSample(String sampleId, String playerHash, double[] features) {

    public FeatureSample {
        Objects.requireNonNull(sampleId, "sampleId");
        Objects.requireNonNull(playerHash, "playerHash");
        Objects.requireNonNull(features, "features");
        if (features.length != CloudContract.FEATURE_DIMENSIONS) {
            throw new IllegalArgumentException("a feature vector is " + CloudContract.FEATURE_DIMENSIONS
                    + " long, got " + features.length);
        }
    }
}
