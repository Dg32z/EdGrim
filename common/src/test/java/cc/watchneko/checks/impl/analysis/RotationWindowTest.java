package cc.watchneko.checks.impl.analysis;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationWindowTest {

    /** The first anchor that can have {@link RotationWindow#RADIUS} updates on both sides of it. */
    private static final long FIRST_CENTRE = RotationWindow.RADIUS + 1L;

    private static RotationWindow filledTo(final int updates) {
        final RotationWindow window = new RotationWindow();
        for (int i = 1; i <= updates; i++) {
            window.append(i, i, -i);
        }
        return window;
    }

    @Test
    void aWindowIsNotCompleteUntilTheUpdatesAfterTheAnchorArrive() {
        final RotationWindow window = filledTo(RotationWindow.WINDOW_SIZE - 1);
        assertTrue(window.extract(FIRST_CENTRE).isEmpty(), "one update short of a full window");

        window.append(RotationWindow.WINDOW_SIZE, RotationWindow.WINDOW_SIZE, -RotationWindow.WINDOW_SIZE);
        assertTrue(window.extract(FIRST_CENTRE).isPresent(), "the eleventh update completes it");
    }

    @Test
    void anAnchorWithoutEnoughHistoryBehindItYieldsNothing() {
        final RotationWindow window = filledTo(RotationWindow.WINDOW_SIZE);
        assertTrue(window.extract(1).isEmpty(), "nothing precedes the first update");
        assertTrue(window.extract(2).isEmpty(), "only one update precedes the second");
    }

    @Test
    void aWindowCompletesOnceFiveUpdatesFollowTheAnchor() {
        final RotationWindow window = filledTo(RotationWindow.WINDOW_SIZE);

        final Optional<double[]> features = window.extract(FIRST_CENTRE);
        assertTrue(features.isPresent(), "eleven updates with the anchor centred");
        assertEquals(RotationWindow.FEATURE_DIMENSIONS, features.get().length);
    }

    @Test
    void featuresAreNormalisedIntoTheUnitRange() {
        final RotationWindow window = filledTo(RotationWindow.WINDOW_SIZE);
        final double[] features = window.extract(FIRST_CENTRE).orElseThrow();

        for (final double value : features) {
            assertTrue(value >= -1.0 && value <= 1.0, "feature out of range: " + value);
        }
    }

    @Test
    void theLargestDeltaOfEachAxisBecomesUnitMagnitude() {
        final RotationWindow window = filledTo(RotationWindow.WINDOW_SIZE);
        final double[] features = window.extract(FIRST_CENTRE).orElseThrow();

        boolean sawYawUnit = false;
        boolean sawPitchUnit = false;
        for (int i = 0; i < features.length; i += 2) {
            if (Math.abs(features[i]) == 1.0) {
                sawYawUnit = true;
            }
            if (Math.abs(features[i + 1]) == 1.0) {
                sawPitchUnit = true;
            }
        }
        assertTrue(sawYawUnit, "yaw axis should normalise its peak to 1");
        assertTrue(sawPitchUnit, "pitch axis should normalise its peak to 1");
    }

    @Test
    void aStillPlayerProducesNoRotationUpdatesAndThereforeNoWindow() {
        final RotationWindow window = new RotationWindow();
        assertEquals(0, window.size());
        assertTrue(window.extract(FIRST_CENTRE).isEmpty());
    }

    @Test
    void anAnchorThatHasSlidOutOfTheBufferYieldsNothing() {
        final RotationWindow window = filledTo(RotationWindow.WINDOW_SIZE * 4 + 10);
        assertTrue(window.extract(FIRST_CENTRE).isEmpty(), "the buffer no longer holds that anchor");
    }

    @Test
    void clearingTheWindowForgetsEverything() {
        final RotationWindow window = filledTo(RotationWindow.WINDOW_SIZE);
        window.clear();
        assertEquals(0, window.size());
        assertTrue(window.extract(FIRST_CENTRE).isEmpty());
    }

    @Test
    void aFlatWindowDoesNotDivideByZero() {
        final RotationWindow window = new RotationWindow();
        for (int i = 1; i <= RotationWindow.WINDOW_SIZE; i++) {
            window.append(i, 0.0F, 0.0F);
        }
        final double[] features = window.extract(FIRST_CENTRE).orElseThrow();
        for (final double value : features) {
            assertEquals(0.0, value, "no movement normalises to zero, not NaN");
            assertFalse(Double.isNaN(value));
        }
    }
}
