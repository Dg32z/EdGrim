package cc.watchneko.cloud;

import java.util.Objects;

/**
 * What the plugin knows about its own identity towards the cloud: the tenant's API key and this
 * server's id. Both become request headers.
 *
 * <p>The endpoint itself is not here: it belongs to the transport that owns the base URL, so that the
 * contract's paths stay host-independent.
 */
public record CloudSettings(String apiKey, String serverId) {

    public CloudSettings {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(serverId, "serverId");
    }
}
