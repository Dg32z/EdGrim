package cc.watchneko.cloud;

/**
 * One response as the plugin received it.
 *
 * @param status HTTP status code
 * @param body   raw JSON body, as the transport read it
 */
public record CloudResponse(int status, String body) {
}
