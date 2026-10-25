package cc.watchneko.cloud;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The inference endpoint, asserted from both directions: the request the plugin constructed and the
 * verdict it read back. Both are compared against the same shared fixture the service side reads.
 */
class InferenceContractTest {

    @Test
    void singleSampleInferenceSendsThePinnedRequestAndReadsBackThePinnedVerdict() {
        FixtureTransport transport = FixtureTransport.replaying("inference_single");
        CloudClient client = new CloudClient(transport, TestSamples.settings());

        InferenceResult result = client.infer(List.of(TestSamples.movedSample("s-0001")));

        CloudRequest sent = transport.lastRequest();
        assertEquals("POST", sent.method());
        assertEquals("/v1/inference", sent.path());
        ContractAssertions.assertSameHeaders(
                ContractFixtures.request("inference_single").getAsJsonObject("headers"),
                sent.headers());
        ContractAssertions.assertSameJson(
                ContractFixtures.request("inference_single").getAsJsonObject("body"),
                CloudJson.parse(sent.body()));

        ContractAssertions.assertSameJson(
                ContractFixtures.response("inference_single").getAsJsonObject("body"),
                CloudJson.parse(CloudJson.write(result)));

        assertEquals("m-0001", result.modelId());
        assertEquals("s-0001", result.verdicts().get(0).sampleId());
        assertEquals(VerdictLabel.LEGIT_LIKE, result.verdicts().get(0).verdict());
    }

    @Test
    void batchInferenceKeepsEveryVerdictAddressableByItsSampleId() {
        FixtureTransport transport = FixtureTransport.replaying("inference_batch");
        CloudClient client = new CloudClient(transport, TestSamples.settings());

        InferenceResult result = client.infer(List.of(
                TestSamples.movedSample("s-0002"),
                TestSamples.stillSample("s-0003")));

        ContractAssertions.assertSameJson(
                ContractFixtures.request("inference_batch").getAsJsonObject("body"),
                CloudJson.parse(transport.lastRequest().body()));
        ContractAssertions.assertSameJson(
                ContractFixtures.response("inference_batch").getAsJsonObject("body"),
                CloudJson.parse(CloudJson.write(result)));

        assertEquals(2, result.verdicts().size());
        assertEquals("s-0002", result.verdicts().get(0).sampleId());
        assertEquals("s-0003", result.verdicts().get(1).sampleId());
    }
}
