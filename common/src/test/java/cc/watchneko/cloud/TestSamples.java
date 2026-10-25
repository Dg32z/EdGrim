package cc.watchneko.cloud;

/**
 * The worked example the corpus was authored from.
 *
 * <p>These values are deliberately literals: the fixture files are the independent source of truth for
 * the wire shape, and this is the input a plugin would hand the client for that example. If the corpus
 * changes its worked example, these constants must change with it — which is the point.
 */
final class TestSamples {

    static final String HASH_A = "9f2c0d4a5b6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708";
    static final String HASH_B = "1a2b3c4d5e6f70819f2c0d4a5b6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80";

    /** A window that moved: non-zero on every derived quantity. */
    static final double[] FEATURES_MOVED = {
            0.12, -0.34, 0.56, 0.02, -0.88, 0.41, 0.07, -0.15, 0.63, 0.29, -0.05,
            0.18, 0.44, -0.22, 0.91, 0.03, -0.47, 0.26, 0.11, -0.09, 0.35, 0.58
    };

    /** A window that never moved: every derived quantity is zero. */
    static final double[] FEATURES_STILL = new double[CloudContract.FEATURE_DIMENSIONS];

    private TestSamples() {
    }

    static CloudSettings settings() {
        return new CloudSettings("test-tenant-key", "survival-1");
    }

    static FeatureSample sample(String sampleId, String playerHash, double[] features) {
        return new FeatureSample(sampleId, playerHash, features.clone());
    }

    static FeatureSample movedSample(String sampleId) {
        return new FeatureSample(sampleId, HASH_A, FEATURES_MOVED.clone());
    }

    static FeatureSample stillSample(String sampleId) {
        return new FeatureSample(sampleId, HASH_B, FEATURES_STILL.clone());
    }
}
