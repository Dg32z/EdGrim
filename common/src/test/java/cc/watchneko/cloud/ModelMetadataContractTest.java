package cc.watchneko.cloud;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model metadata endpoint: what the plugin learns about the model before it dares to send anything.
 *
 * <p>It carries the model version and the classification thresholds together, because a threshold is only
 * meaningful for the model it was tuned for. It also carries the dataset version, which is what makes a
 * production verdict traceable back to training data.
 */
class ModelMetadataContractTest {

    @Test
    void metadataIsAFetchWithNoBodyThatCarriesTheModelDatasetAndThresholds() {
        FixtureTransport transport = FixtureTransport.replaying("model_metadata");
        CloudClient client = new CloudClient(transport, TestSamples.settings());

        Optional<ModelMetadata> metadata = client.fetchModelMetadata();

        CloudRequest sent = transport.lastRequest();
        assertEquals("GET", sent.method());
        assertEquals("/v1/model", sent.path());
        assertNull(sent.body(), "a metadata fetch carries no body");
        ContractAssertions.assertSameHeaders(
                ContractFixtures.request("model_metadata").getAsJsonObject("headers"),
                sent.headers());

        ContractAssertions.assertSameJson(
                ContractFixtures.response("model_metadata").getAsJsonObject("body"),
                CloudJson.parse(CloudJson.write(metadata.orElseThrow())));

        assertEquals("m-0001", metadata.orElseThrow().modelId());
        assertEquals("ds-0001", metadata.orElseThrow().datasetId());
        assertEquals(CloudContract.FEATURE_DIMENSIONS, metadata.orElseThrow().featureDimensions());
        assertEquals(0.7, metadata.orElseThrow().thresholds().cheatLike());
        assertEquals(0.3, metadata.orElseThrow().thresholds().legitLike());
    }

    @Test
    void aCloudWithNoPublishedModelAnswersNoModelAndThePluginHasNothingToEnable() {
        FixtureTransport transport = FixtureTransport.replaying("model_metadata_absent");
        CloudClient client = new CloudClient(transport, TestSamples.settings());

        Optional<ModelMetadata> metadata = client.fetchModelMetadata();

        assertTrue(metadata.isEmpty(), "a 404 is the cold start, not an error to report");

        CloudRequest sent = transport.lastRequest();
        assertEquals("GET", sent.method());
        assertEquals("/v1/model", sent.path());
        ContractAssertions.assertSameHeaders(
                ContractFixtures.request("model_metadata_absent").getAsJsonObject("headers"),
                sent.headers());
    }
}
