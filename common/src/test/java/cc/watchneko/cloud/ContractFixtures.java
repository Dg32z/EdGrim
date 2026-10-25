package cc.watchneko.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads the shared golden fixture corpus at {@code contract/fixtures}.
 *
 * <p>Both sides of the wire read this same directory: the Java tests here and the Python tests in
 * {@code cloud-service/tests}. That shared read is the whole anti-drift mechanism, so this class
 * deliberately does no interpretation — it hands back the parsed document and lets each test assert
 * against it.
 */
final class ContractFixtures {

    private static final String CORPUS = "contract/fixtures";

    private ContractFixtures() {
    }

    /** The corpus directory, resolved by walking up from the working directory. */
    static Path directory() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            Path corpus = candidate.resolve(CORPUS);
            if (Files.isDirectory(corpus)) {
                return corpus;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate " + CORPUS + " above " + System.getProperty("user.dir"));
    }

    /** Every case in the corpus, in a stable order. */
    static List<String> caseNames() {
        try (var entries = Files.list(directory())) {
            return entries
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The whole case document. */
    static JsonObject document(String caseName) {
        Path file = directory().resolve(caseName + ".json");
        try {
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    static JsonObject request(String caseName) {
        return document(caseName).getAsJsonObject("request");
    }

    static JsonObject response(String caseName) {
        return document(caseName).getAsJsonObject("response");
    }
}
