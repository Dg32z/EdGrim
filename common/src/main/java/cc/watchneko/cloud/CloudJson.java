package cc.watchneko.cloud;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The JSON codec for the contract.
 *
 * <p>Field names on the wire are {@code snake_case}, which is the single wire form both sides speak; the
 * naming policy maps the Java records' camelCase onto it. Enum constants carry their wire spelling
 * explicitly, because a naming policy does not rename enum constants and {@code CHEAT_LIKE} is not the
 * contract.
 */
final class CloudJson {

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private CloudJson() {
    }

    static String write(Object value) {
        return GSON.toJson(value);
    }

    static <T> T read(String body, Class<T> type) {
        return GSON.fromJson(body, type);
    }

    static JsonObject parse(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /**
     * Serialises a value to a JSON tree, so a constant's wire spelling is written in exactly one place —
     * the annotation on the enum constant — instead of being restated at every call site.
     */
    static JsonElement toTree(Object value) {
        return GSON.toJsonTree(value);
    }
}
