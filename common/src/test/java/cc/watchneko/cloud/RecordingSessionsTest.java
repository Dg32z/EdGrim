package cc.watchneko.cloud;

import cc.watchneko.analysis.RotationWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingSessionsTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static FeatureSample sample(final String id) {
        return new FeatureSample(id, "hash-a", new double[RotationWindow.FEATURE_DIMENSIONS]);
    }

    @AfterEach
    void forgetEverything() {
        RecordingSessions.clear();
    }

    @Test
    void aStartedSessionCarriesTheLabelItWasGivenAtTheStart() {
        final RecordingSession session =
                RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();

        assertEquals(Label.CHEAT, session.label());
        assertEquals("rs-1", session.sessionId());
        assertTrue(RecordingSessions.isRecording(PLAYER));
    }

    @Test
    void samplesAccumulateUntilTheSessionEnds() {
        final RecordingSession session =
                RecordingSessions.start(PLAYER, "Notch", Label.LEGIT, "rs-1").orElseThrow();

        session.add(sample("a"));
        session.add(sample("b"));

        assertEquals(2, session.size());
        assertEquals(2, session.samples().size());
    }

    @Test
    void stoppingHandsTheSessionBackAndEndsIt() {
        RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();

        final RecordingSession ended = RecordingSessions.stop(PLAYER).orElseThrow();

        assertEquals("rs-1", ended.sessionId());
        assertFalse(RecordingSessions.isRecording(PLAYER));
    }

    @Test
    void aSecondStartForTheSamePlayerIsRefusedRatherThanReplacingTheFirst() {
        RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();

        assertTrue(RecordingSessions.start(PLAYER, "Notch", Label.LEGIT, "rs-2").isEmpty(),
                "silently discarding a half-finished recording would quietly lose data");
        assertEquals("rs-1", RecordingSessions.active(PLAYER).orElseThrow().sessionId());
    }

    @Test
    void stoppingAPlayerWhoIsNotBeingRecordedYieldsNothing() {
        assertTrue(RecordingSessions.stop(PLAYER).isEmpty());
    }

    @Test
    void sessionsForDifferentPlayersAreIndependent() {
        final UUID other = UUID.randomUUID();
        RecordingSessions.start(PLAYER, "A", Label.CHEAT, "rs-1").orElseThrow();
        RecordingSessions.start(other, "B", Label.LEGIT, "rs-2").orElseThrow();

        assertEquals(Label.CHEAT, RecordingSessions.active(PLAYER).orElseThrow().label());
        assertEquals(Label.LEGIT, RecordingSessions.active(other).orElseThrow().label());

        RecordingSessions.stop(PLAYER);
        assertTrue(RecordingSessions.isRecording(other));
    }

    @Test
    void clearingForgetsEverySessionWithoutUploadingAnything() {
        RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();
        RecordingSessions.clear();
        assertFalse(RecordingSessions.isRecording(PLAYER));
    }
}
