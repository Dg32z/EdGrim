package cc.watchneko.cloud;

/**
 * The wire contract shared with the cloud service, as constants.
 *
 * <p>Everything here is pinned by the golden fixture corpus in {@code contract/fixtures}; see
 * {@code contract/README.md} for the shapes these values belong to. Nothing in this class is a policy
 * decision — it is a transcription of the contract, and the fixtures are what enforce it.
 */
public final class CloudContract {

    /** Version stamped into every request and response body. */
    public static final int CONTRACT_VERSION = 1;

    /** Length of a feature vector. A sample's window derives exactly this many normalised values. */
    public static final int FEATURE_DIMENSIONS = 22;

    public static final String INFERENCE_PATH = "/v1/inference";
    public static final String SAMPLES_PATH = "/v1/samples";
    public static final String MODEL_PATH = "/v1/model";

    /** Carries the tenant API key. Header names are lowercase because HTTP header names are case-insensitive. */
    public static final String HEADER_AUTHORIZATION = "authorization";
    public static final String HEADER_SERVER_ID = "x-server-id";
    public static final String HEADER_CONTENT_TYPE = "content-type";

    public static final String CONTENT_TYPE_JSON = "application/json";

    /** The scheme the authorization header must carry. */
    public static final String AUTHORIZATION_SCHEME = "Bearer";

    /** Error code returned by {@code GET /v1/model} when the cloud has no published model yet. */
    public static final String ERROR_NO_MODEL = "no_model";

    private CloudContract() {
    }
}
