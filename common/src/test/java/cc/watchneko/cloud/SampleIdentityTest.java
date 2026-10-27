package cc.watchneko.cloud;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample id is the one place a player identifier could leak onto the wire. These tests are the reason it
 * cannot do so quietly.
 */
class SampleIdentityTest {

    private static final String HASH = "9f2c1a77b4e0d3c5a1b2c3d4e5f60718";

    @Test
    void anIdIsBuiltFromTheHashAndACounter() {
        assertTrue(SampleIdentity.of(HASH, 7).startsWith(HASH.substring(0, 12)));
        assertTrue(SampleIdentity.of(HASH, 7).endsWith("-7"));
    }

    @Test
    void theSamePlayerAtDifferentCountsGetsDifferentIds() {
        assertFalse(SampleIdentity.of(HASH, 1).equals(SampleIdentity.of(HASH, 2)));
    }

    @Test
    void anIdNeverContainsAUuid() {
        final UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        final String hash = "9f2c1a77b4e0d3c5a1b2c3d4e5f60718";

        final String id = SampleIdentity.of(hash, 1);

        assertFalse(id.contains(uuid.toString()), "the raw uuid must never appear in a wire id");
        assertFalse(id.contains(uuid.toString().replace("-", "")), "nor with its dashes stripped");
        assertFalse(id.toLowerCase().contains("069a79f4"), "nor any fragment of it");
    }

    @Test
    void aHashTooShortToPrefixIsRefusedRatherThanTruncatedSilently() {
        assertThrows(IllegalArgumentException.class, () -> SampleIdentity.of("abc", 1));
        assertThrows(IllegalArgumentException.class, () -> SampleIdentity.of(null, 1));
    }

    @Test
    void recordingIdsFromDifferentSessionsNeverCollideEvenAtTheSameCounter() {
        // A reconnect rebuilds the per-player counter, so both sessions start at 1. The cloud stores
        // samples by id and rejects ones it already has, so an id shared across sessions silently loses
        // the whole second recording.
        assertFalse(
                SampleIdentity.forRecording(HASH, "rs-a", 1)
                        .equals(SampleIdentity.forRecording(HASH, "rs-b", 1)),
                "two recordings of the same player must not produce the same sample id");
    }

    @Test
    void aRecordingIdIsStableForTheSameSessionAndCounter() {
        assertEquals(
                SampleIdentity.forRecording(HASH, "rs-a", 7),
                SampleIdentity.forRecording(HASH, "rs-a", 7),
                "the id must be reproducible, or a retried upload could not be recognised as a replay");
    }

    @Test
    void aRecordingIdCarriesTheSessionAndCounter() {
        final String id = SampleIdentity.forRecording(HASH, "rs-a", 7);

        assertTrue(id.startsWith(HASH.substring(0, 12)));
        assertTrue(id.contains("rs-a"));
        assertTrue(id.endsWith("-7"));
    }

    @Test
    void aRecordingIdNeverContainsAUuid() {
        final UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        final String id = SampleIdentity.forRecording(HASH, "rs-a", 1);

        assertFalse(id.contains(uuid.toString()));
        assertFalse(id.toLowerCase().contains("069a79f4"));
    }

    @Test
    void aRecordingIdWithoutASessionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SampleIdentity.forRecording(HASH, null, 1));
        assertThrows(IllegalArgumentException.class, () -> SampleIdentity.forRecording(HASH, "  ", 1));
    }
}
