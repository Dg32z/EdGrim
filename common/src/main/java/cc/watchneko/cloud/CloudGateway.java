package cc.watchneko.cloud;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.RotationWindow;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Everything the plugin knows about the cloud, shared by every player's check.
 *
 * <p>Rebuilt on config reload and published as a whole through a single volatile field, so a check either
 * sees a fully usable gateway or none at all — never a half-initialised one. That is the deliberate answer
 * to the failure mode that killed the previous implementation, where a static reference library was
 * dereferenced before anything had loaded it and every packet threw.
 *
 * <p><b>{@link #current()} returning {@code null} is a normal state</b>, not an error. It covers every way
 * the cloud can be unusable: not enabled, not configured, unreachable, answering with an error, having no
 * published model yet, or publishing a model whose feature width this build cannot speak. A check that
 * sees {@code null} simply does nothing — no samples leave, no verdicts arrive, no violation is raised.
 *
 * <p>When the cloud comes back, detection comes back on its own: {@link #recoverIfNeeded()} re-attempts on
 * a timer, so a server that started before the cloud did does not need a restart to begin detecting.
 */
public final class CloudGateway {

    private static final ExecutorService EXECUTOR =
            Executors.newFixedThreadPool(2, daemonThreads("watchneko-cloud"));

    private static final ScheduledExecutorService RECOVERY =
            Executors.newSingleThreadScheduledExecutor(daemonThreads("watchneko-cloud-recovery"));

    /** How long to wait before re-attempting a cloud that was not usable last time. */
    static final long RECOVERY_INTERVAL_SECONDS = 30L;

    private static volatile CloudGateway current;
    private static volatile ConfigManager lastConfig;
    private static volatile boolean recoveryScheduled;

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
        lastConfig = config;
        current = attemptFrom(config);
        scheduleRecovery();
    }

    /**
     * Builds a gateway against an already-constructed transport, or returns empty for every reason the
     * cloud cannot be used. Never throws: a cloud that misbehaves must not take detection — or the plugin —
     * down with it.
     *
     * <p>This is the seam the fail-open behaviour is tested through.
     */
    public static Optional<CloudGateway> attempt(
            final CloudTransport transport, final CloudSettings settings, final String salt) {
        try {
            final CloudClient client = new CloudClient(transport, settings);

            final Optional<ModelMetadata> metadata = client.fetchModelMetadata();
            if (metadata.isEmpty()) {
                log(() -> LogUtil.info("AnalysisA cloud has no published model yet; detection stays disabled"));
                return Optional.empty();
            }

            final int expected = RotationWindow.FEATURE_DIMENSIONS;
            if (metadata.get().featureDimensions() != expected) {
                log(() -> LogUtil.error("AnalysisA cloud model expects " + metadata.get().featureDimensions()
                        + " feature dimensions but this build sends " + expected
                        + "; detection stays disabled"));
                return Optional.empty();
            }

            return Optional.of(new CloudGateway(client, metadata.get(), salt));
        } catch (Exception exception) {
            log(() -> LogUtil.error("AnalysisA could not reach the cloud; detection stays disabled", exception));
            return Optional.empty();
        }
    }

    /**
     * Re-attempts a cloud that was unusable last time. Does nothing when there is no config yet, when
     * detection is switched off, or when a gateway is already in place.
     */
    static void recoverIfNeeded() {
        final ConfigManager config = lastConfig;
        if (config == null || current != null) {
            return;
        }
        if (!config.getBooleanElse("AnalysisA.cloud.enabled", false)) {
            return;
        }

        final CloudGateway recovered = attemptFrom(config);
        if (recovered != null) {
            current = recovered;
            log(() -> LogUtil.info("AnalysisA cloud became available again; detection re-enabled"));
        }
    }

    private static CloudGateway attemptFrom(final ConfigManager config) {
        if (!config.getBooleanElse("AnalysisA.cloud.enabled", false)) {
            return null;
        }

        final String endpoint = config.getStringElse("AnalysisA.cloud.endpoint", "");
        final String apiKey = config.getStringElse("AnalysisA.cloud.api-key", "");
        final String serverId = config.getStringElse("AnalysisA.cloud.server-id", "");
        final String salt = config.getStringElse("AnalysisA.cloud.salt", "");
        final int timeoutMs = config.getIntElse("AnalysisA.cloud.timeout-ms", 2000);

        if (endpoint.isBlank() || apiKey.isBlank() || serverId.isBlank()) {
            log(() -> LogUtil.warn("AnalysisA cloud is enabled but endpoint, api-key or server-id is missing"));
            return null;
        }

        final HttpCloudTransport transport =
                new HttpCloudTransport(endpoint, Duration.ofMillis(Math.max(1, timeoutMs)));
        return attempt(transport, new CloudSettings(apiKey, serverId), salt).orElse(null);
    }

    private static synchronized void scheduleRecovery() {
        if (recoveryScheduled) {
            return;
        }
        recoveryScheduled = true;
        RECOVERY.scheduleWithFixedDelay(
                CloudGateway::recoverIfNeeded,
                RECOVERY_INTERVAL_SECONDS,
                RECOVERY_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
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

    /** Uploads one recording session's labelled batch off the calling thread. */
    public CompletableFuture<SampleUploadResult> uploadAsync(
            final String sessionId, final Label label, final List<FeatureSample> samples) {
        return CompletableFuture.supplyAsync(
                () -> client.uploadSamples(sessionId, label, samples), EXECUTOR);
    }

    /**
     * Runs a logging call, tolerating a logger that is unavailable.
     *
     * <p>The plugin's logger resolves through the running plugin instance, which does not exist in a
     * headless context and is not yet ready during the earliest startup — and in that state it fails with
     * an {@link Error}, not an {@link Exception}. Letting that propagate would make logging the thing that
     * breaks detection, which is exactly backwards.
     */
    private static void log(final Runnable action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            // see the method comment: a missing logger is not a detection failure
        }
    }

    private static ThreadFactory daemonThreads(final String prefix) {
        return runnable -> {
            final Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }
}
