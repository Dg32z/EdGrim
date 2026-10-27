package cc.watchneko.cloud;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The real network transport: the one place an HTTP library is named.
 *
 * <p>Everything above this line speaks {@link CloudRequest} and {@link CloudResponse}; this class is what
 * turns them into an actual exchange. It is deliberately dumb — no retries, no fail-open, no policy — so
 * that the behaviour around a failure stays the caller's decision.
 */
public final class HttpCloudTransport implements CloudTransport {

    private final HttpClient client;
    private final URI base;
    private final Duration timeout;

    public HttpCloudTransport(final String endpoint, final Duration timeout) {
        this.base = URI.create(endpoint);
        this.timeout = timeout;
        // Pin HTTP/1.1. The JDK client otherwise attempts an h2c upgrade on every plaintext request
        // (`Connection: Upgrade, HTTP2-Settings` + `Upgrade: h2c`). The service runs on uvicorn, which
        // speaks HTTP/1.1 only, so each request logs "Unsupported upgrade request" and a missing-websocket
        // warning before falling back. The requests succeed either way; the point of pinning is to stop
        // burying real errors under two lines of noise per request.
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public CloudResponse exchange(final CloudRequest request) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(base.resolve(request.path()))
                .timeout(timeout);

        for (final Map.Entry<String, String> header : request.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        if (request.body() == null) {
            builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(request.method(), HttpRequest.BodyPublishers.ofString(request.body()));
        }

        try {
            final HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new CloudResponse(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling the cloud", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("cloud request failed: " + exception.getMessage(), exception);
        }
    }
}
