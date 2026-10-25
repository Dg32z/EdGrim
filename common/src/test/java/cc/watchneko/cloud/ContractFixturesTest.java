package cc.watchneko.cloud;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The corpus is the shared assertion object of both sides. This test pins which cases exist, so
 * adding a fixture forces both sides to implement it rather than silently ignoring it.
 */
class ContractFixturesTest {

    @Test
    void corpusHoldsExactlyTheCasesBothSidesImplement() {
        assertEquals(
                List.of(
                        "inference_batch",
                        "inference_single",
                        "model_metadata",
                        "model_metadata_absent",
                        "sample_upload"),
                ContractFixtures.caseNames());
    }

    @Test
    void everyCaseDeclaresItsNameAsItsFileName() {
        for (String caseName : ContractFixtures.caseNames()) {
            assertEquals(caseName, ContractFixtures.document(caseName).get("case").getAsString());
        }
    }

    @Test
    void everyCaseCarriesARequestAndAResponse() {
        for (String caseName : ContractFixtures.caseNames()) {
            JsonObject request = ContractFixtures.request(caseName);
            JsonObject response = ContractFixtures.response(caseName);

            assertFalse(request.get("method").getAsString().isBlank(), caseName);
            assertFalse(request.get("path").getAsString().isBlank(), caseName);
            assertFalse(request.get("headers").getAsJsonObject().isEmpty(), caseName);
            assertFalse(response.get("status").getAsJsonPrimitive().isString(), caseName);
            assertFalse(response.get("body").isJsonNull(), caseName);
        }
    }

    @Test
    void endpointsAreAddressedAsDocumented() {
        assertEquals("/v1/inference", ContractFixtures.request("inference_single").get("path").getAsString());
        assertEquals("/v1/samples", ContractFixtures.request("sample_upload").get("path").getAsString());
        assertEquals("/v1/model", ContractFixtures.request("model_metadata").get("path").getAsString());
    }
}
