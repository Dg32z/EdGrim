package cc.watchneko.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict structural comparison against a fixture.
 *
 * <p>Strict on purpose: object field-name sets must match exactly (no extra field, no missing field)
 * and numbers compare numerically so {@code 0} and {@code 0.0} are the same wire value. This is what
 * makes a renamed field a test failure instead of a silent pass.
 */
final class ContractAssertions {

    private ContractAssertions() {
    }

    static void assertSameJson(JsonElement expected, JsonElement actual) {
        compare("$", expected, actual);
    }

    static void assertSameHeaders(JsonObject expected, Map<String, String> actual) {
        Map<String, String> expectedLower = new LinkedHashMap<>();
        expected.entrySet().forEach(entry ->
                expectedLower.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getAsString()));

        Map<String, String> actualLower = new LinkedHashMap<>();
        actual.forEach((name, value) -> actualLower.put(name.toLowerCase(Locale.ROOT), value));

        assertEquals(expectedLower, actualLower, "request header names/values differ from the fixture");
    }

    private static void compare(String path, JsonElement expected, JsonElement actual) {
        if (expected == null || expected.isJsonNull()) {
            assertTrue(actual == null || actual.isJsonNull(),
                    path + ": expected no value but was " + actual);
            return;
        }
        assertNotNull(actual, path + ": expected " + expected + " but the field was absent");

        if (expected.isJsonObject()) {
            assertTrue(actual.isJsonObject(), path + ": expected an object but was " + actual);
            JsonObject expectedObject = expected.getAsJsonObject();
            JsonObject actualObject = actual.getAsJsonObject();
            assertEquals(expectedObject.keySet(), actualObject.keySet(), path + ": field names differ");
            for (String field : expectedObject.keySet()) {
                compare(path + "." + field, expectedObject.get(field), actualObject.get(field));
            }
            return;
        }

        if (expected.isJsonArray()) {
            assertTrue(actual.isJsonArray(), path + ": expected an array but was " + actual);
            JsonArray expectedArray = expected.getAsJsonArray();
            JsonArray actualArray = actual.getAsJsonArray();
            assertEquals(expectedArray.size(), actualArray.size(), path + ": array length differs");
            for (int i = 0; i < expectedArray.size(); i++) {
                compare(path + "[" + i + "]", expectedArray.get(i), actualArray.get(i));
            }
            return;
        }

        JsonPrimitive expectedPrimitive = expected.getAsJsonPrimitive();
        assertTrue(actual.isJsonPrimitive(), path + ": expected a primitive but was " + actual);
        JsonPrimitive actualPrimitive = actual.getAsJsonPrimitive();
        if (expectedPrimitive.isNumber() && actualPrimitive.isNumber()) {
            assertEquals(expectedPrimitive.getAsDouble(), actualPrimitive.getAsDouble(), 0.0,
                    path + ": number differs");
            return;
        }
        assertEquals(expectedPrimitive, actualPrimitive, path + ": value differs");
    }
}
