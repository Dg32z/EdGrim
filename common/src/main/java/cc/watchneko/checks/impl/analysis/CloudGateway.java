package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.cloud.CloudClient;
import cc.watchneko.cloud.CloudSettings;
import cc.watchneko.cloud.FeatureSample;
import cc.watchneko.cloud.InferenceResult;
import cc.watchneko.cloud.ModelMetadata;
import cc.watchneko.utils.anticheat.LogUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Everything the plugin knows about the cloud, shared by every player's check.
 *
 * <p>Rebuilt on config reload and published as a whole through a single volatile field, so a check either
 * sees a fully configured gateway or none at all — never a half-initialised one. That is the deliberate
 * answer to the failure mode that killed the previous implementation, where a static reference library was
 * dereferenced before anything had loaded it and every packet threw.
 *
 * <p>{@link #current()} returning {@code null} is the normal "not enabled, not configured, or no model
 * published yet" state, not an error.
 */
public final class CloudGateway {

    private static final ExecutorService EXECUTOR =
            Executors.newFixedThreadPool(2, daemonThreads("watchneko-cloud"));

    private static volatile CloudGateway current;

    private final CloudClient client;
    private final ModelMetadata metadata;
    private final String salt;

    private CloudGateway(final CloudClient client, final ModelMetadata metadata, final String salt) {
        this.client = client;
        this.metadata = metadata;
        this.salt = salt;
    }

    /** The gateway in effect, or {@code null} when cloud detection is not usable right now. */
    public static CloudGateway current() {
        return current;
    }

    /** Rebuilds the gateway from config. Called from the async reload path, so blocking here is acceptable. */
    public static void reload(final ConfigManager config) {
        current = null;

        if (!config.getBooleanElse("AnalysisA.cloud.enabled", false)) {
            return;
        }

        final String endpoint = config.getStringElse("AnalysisA.cloud.endpoint", "");
        final String apiKey = config.getStringElse("AnalysisA.cloud.api-key", "");
        final String serverId = config.getStringElse("AnalysisA.cloud.server-id", "");
        final String salt = config.getStringElse("AnalysisA.cloud.salt", "");
        final int timeoutMs = config.getIntElse("AnalysisA.cloud.timeout-ms", 2000);

        if (endpoint.isBlank() || apiKey.isBlank() || serverId.isBlank()) {
            LogUtil.warn("AnalysisA cloud is enabled but endpoint, api-key or server-id is missing");
            return;
        }

        try {
            final CloudClient client = new CloudClient(
                    new HttpCloudTransport(endpoint, Duration.ofMillis(Math.max(1, timeoutMs))),
                    new CloudSettings(apiKey, serverId));

            final Optional<ModelMetadata> metadata = client.fetchModelMetadata();
            if (metadata.isEmpty()) {
                LogUtil.info("AnalysisA cloud has no published model yet; detection stays disabled");
                return;
            }

            if (metadata.get().featureDimensions() != RotationWindow.FEATURE_DIMENSIONS) {
                LogUtil.error("AnalysisA cloud model expects " + metadata.get().featureDimensions()
                        + " feature dimensions but this build sends " + RotationWindow.FEATURE_DIMENSIONS
                        + "; detection stays disabled");
                return;
            }

            current = new CloudGateway(client, metadata.get(), salt);
            LogUtil.info("AnalysisA cloud enabled with model " + metadata.get().modelId()
                    + " trained on dataset " + metadata.get().datasetId());
        } catch (Exception exception) {
            LogUtil.error("AnalysisA could not reach the cloud; detection stays disabled", exception);
        }
    }

    public ModelMetadata metadata() {
        return metadata;
    }

    /** Hashes a player id under the tenant salt, so no raw identity ever leaves the server. */
    public String playerHash(final UUID uuid) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update(uuid.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    /** Sends a batch off the packet thread. */
    public CompletableFuture<InferenceResult> inferAsync(final List<FeatureSample> samples) {
        return CompletableFuture.supplyAsync(() -> client.infer(samples), EXECUTOR);
    }

    private static ThreadFactory daemonThreads(final String prefix) {
        return runnable -> {
            final Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }
}
