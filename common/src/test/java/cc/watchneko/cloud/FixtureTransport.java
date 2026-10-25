package cc.watchneko.cloud;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A cloud transport that replays the response half of one fixture case and records every request the
 * client constructed.
 *
 * <p>There is no network here: the point is to observe what the plugin put on the wire, not to reach a
 * server. The recorded request is what the tests compare against the request half of the fixture.
 */
final class FixtureTransport implements CloudTransport {

    private final String caseName;
    private final List<CloudRequest> requests = new ArrayList<>();

    private FixtureTransport(String caseName) {
        this.caseName = caseName;
    }

    static FixtureTransport replaying(String caseName) {
        return new FixtureTransport(caseName);
    }

    @Override
    public CloudResponse exchange(CloudRequest request) {
        requests.add(request);
        JsonObject response = ContractFixtures.response(caseName);
        return new CloudResponse(response.get("status").getAsInt(), response.get("body").toString());
    }

    CloudRequest lastRequest() {
        if (requests.isEmpty()) {
            throw new AssertionError("the client sent no request for case " + caseName);
        }
        return requests.get(requests.size() - 1);
    }

    List<CloudRequest> requests() {
        return List.copyOf(requests);
    }
}
