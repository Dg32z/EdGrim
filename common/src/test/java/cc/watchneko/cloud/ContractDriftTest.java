package cc.watchneko.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the anti-drift guard is actually load-bearing.
 *
 * <p>A comparison that silently tolerates a renamed field is worse than no comparison: it looks like a
 * contract test while catching nothing. So this test does the thing the corpus exists to prevent — renames
 * one field on one side — and asserts that the comparison fails. If someone later loosens
 * {@link ContractAssertions} to compare a subset of fields, this test goes red.
 *
 * <p>The service side carries the mirror image of this test against the same corpus, which is what makes a
 * field rename fail on both sides instead of one.
 */
class ContractDriftTest {

    @Test
    void aRenamedRequestFieldIsRejected() {
        FixtureTransport transport = FixtureTransport.replaying("inference_single");
        CloudClient client = new CloudClient(transport, TestSamples.settings());
        client.infer(List.of(TestSamples.movedSample("s-0001")));

        JsonObject pinned = ContractFixtures.request("inference_single").getAsJsonObject("body");
        JsonObject actual = CloudJson.parse(transport.lastRequest().body());

        assertDoesNotThrow(() -> ContractAssertions.assertSameJson(pinned, actual));

        JsonObject drifted = renameSampleField(pinned, "features", "feature_vector");

        assertThrows(AssertionError.class,
                () -> ContractAssertions.assertSameJson(drifted, actual),
                "renaming features to feature_vector on one side must not compare equal");
    }

    @Test
    void aRenamedResponseFieldIsRejected() {
        FixtureTransport transport = FixtureTransport.replaying("inference_single");
        CloudClient client = new CloudClient(transport, TestSamples.settings());
        InferenceResult result = client.infer(List.of(TestSamples.movedSample("s-0001")));

        JsonObject pinned = ContractFixtures.response("inference_single").getAsJsonObject("body");
        JsonObject actual = CloudJson.parse(CloudJson.write(result));

        assertDoesNotThrow(() -> ContractAssertions.assertSameJson(pinned, actual));

        JsonObject drifted = renameVerdictField(pinned, "verdict", "label");

        assertThrows(AssertionError.class,
                () -> ContractAssertions.assertSameJson(drifted, actual),
                "renaming verdict to label on one side must not compare equal");
    }

    @Test
    void aMissingFieldIsRejectedRatherThanTreatedAsAbsentOnBothSides() {
        FixtureTransport transport = FixtureTransport.replaying("sample_upload");
        CloudClient client = new CloudClient(transport, TestSamples.settings());
        client.uploadSamples("rs-0001", Label.CHEAT, List.of(TestSamples.movedSample("s-1001")));

        JsonObject pinned = ContractFixtures.request("sample_upload").getAsJsonObject("body");
        JsonObject droppedLabel = pinned.deepCopy();
        droppedLabel.remove("label");

        assertThrows(AssertionError.class,
                () -> ContractAssertions.assertSameJson(droppedLabel, CloudJson.parse(transport.lastRequest().body())),
                "a field the fixture has and the client omits must not compare equal");
    }

    @Test
    void theVerdictVocabularyIsPinnedExactly() {
        assertEquals(3, VerdictLabel.values().length, "the cloud may only reach three conclusions");
        assertEquals("cheat_like", wire(VerdictLabel.CHEAT_LIKE));
        assertEquals("legit_like", wire(VerdictLabel.LEGIT_LIKE));
        assertEquals("uncertain", wire(VerdictLabel.UNCERTAIN));

        assertEquals(2, Label.values().length, "a sample is either cheat or legit");
        assertEquals("cheat", wire(Label.CHEAT));
        assertEquals("legit", wire(Label.LEGIT));
    }

    private static String wire(Enum<?> constant) {
        return CloudJson.toTree(constant).getAsString();
    }

    private static JsonObject renameSampleField(JsonObject requestBody, String from, String to) {
        JsonObject drifted = requestBody.deepCopy();
        JsonArray samples = drifted.getAsJsonArray("samples");
        for (int i = 0; i < samples.size(); i++) {
            JsonObject sample = samples.get(i).getAsJsonObject();
            if (sample.has(from)) {
                sample.add(to, sample.remove(from));
            }
        }
        return drifted;
    }

    private static JsonObject renameVerdictField(JsonObject responseBody, String from, String to) {
        JsonObject drifted = responseBody.deepCopy();
        JsonArray verdicts = drifted.getAsJsonArray("verdicts");
        for (int i = 0; i < verdicts.size(); i++) {
            JsonObject verdict = verdicts.get(i).getAsJsonObject();
            if (verdict.has(from)) {
                verdict.add(to, verdict.remove(from));
            }
        }
        return drifted;
    }
}
