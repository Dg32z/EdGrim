package cc.watchneko.checks.impl.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one thing that makes the cloud check punishable at all: it is enabled and escalated by the
 * existing {@code Analysis} punishments group, purely by name matching.
 *
 * <p>The repo matches a punishment group to a check with {@code checkName.toLowerCase().contains(command)},
 * so {@code AnalysisA} is picked up by a group listing {@code "Analysis"} — no new punishments entry is
 * needed. That is convenient, and also invisible: rename the check and the group silently stops applying,
 * leaving a detection that never escalates. This test makes that failure loud.
 */
class AnalysisPunishmentGroupTest {

    private static final String CHECK_NAME = "AnalysisA";
    private static final String GROUP_CHECK_COMMAND = "Analysis";

    @Test
    void theExistingAnalysisGroupMatchesThisChecksName() {
        assertTrue(
                CHECK_NAME.toLowerCase(Locale.ROOT).contains(GROUP_CHECK_COMMAND.toLowerCase(Locale.ROOT)),
                "the Analysis punishments group must keep matching " + CHECK_NAME);
    }

    @Test
    void theShippedPunishmentsFilesStillDeclareThatGroup() throws IOException {
        for (final String file : new String[] {"punishments/en.yml", "punishments/zh.yml"}) {
            final String contents = readResource(file);

            assertTrue(contents.contains("Analysis:"), file + " no longer declares an Analysis group");
            assertTrue(contents.contains("\"" + GROUP_CHECK_COMMAND + "\""),
                    file + " no longer lists " + GROUP_CHECK_COMMAND + " as a check of that group");
        }
    }

    private static String readResource(final String path) throws IOException {
        try (InputStream stream = AnalysisPunishmentGroupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "missing classpath resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
