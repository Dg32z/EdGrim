package cc.watchneko.cloud;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One request as the plugin will put it on the wire.
 *
 * <p>Header names are normalised to lowercase on construction so both sides of the contract compare
 * the same thing; HTTP header names are case-insensitive, so this loses nothing.
 *
 * @param method  HTTP method
 * @param path    path below the configured endpoint, as pinned by the contract
 * @param headers request headers, keys lowercase
 * @param body    JSON body, or {@code null} for a request without one
 */
public record CloudRequest(String method, String path, Map<String, String> headers, String body) {

    public CloudRequest {
        headers = normalise(headers);
    }

    private static Map<String, String> normalise(Map<String, String> headers) {
        Map<String, String> lowered = new LinkedHashMap<>();
        headers.forEach((name, value) -> lowered.put(name.toLowerCase(Locale.ROOT), value));
        return Map.copyOf(lowered);
    }
}
