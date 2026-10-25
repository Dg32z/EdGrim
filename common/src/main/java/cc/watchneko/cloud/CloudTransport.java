package cc.watchneko.cloud;

/**
 * The only thing {@link CloudClient} needs from the network.
 *
 * <p>Keeping the HTTP library behind this seam is what lets the contract be asserted without a server:
 * a test supplies a transport that replays a fixture, and observes the request the client built. The
 * real transport is ticket #11's business.
 */
public interface CloudTransport {

    CloudResponse exchange(CloudRequest request);
}
