package cc.watchneko.checks;

import cc.watchneko.utils.anticheat.update.AttackAnchor;
import cc.watchneko.utils.anticheat.update.AttackSignalListener;
import cc.watchneko.utils.anticheat.update.RotationSequence;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.data.HeadRotation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A check can be driven with synthetic rotation updates plus an attack signal.
 *
 * <p>Nothing in this test constructs a PacketEvents event, a User or a channel: the check observes
 * rotations and the attack signal through the plain attack seam only. The test itself plays the
 * role the framework plays in production, dispatching each rotation update to the check.
 */
class AttackSignalDrivesCheckTest {

    @Test
    void checkReceivesAttackAnchoredAtItsPositionInTheRotationSequence() {
        RotationSequence sequence = new RotationSequence();
        AttackWatchingCheck check = new AttackWatchingCheck();
        sequence.addListener(check);

        for (int i = 1; i <= 5; i++) {
            RotationUpdate update = rotate(i);
            sequence.onRotationUpdate(update);
            check.onRotationUpdate(update);
        }

        sequence.onAttackObserved(42);

        assertEquals(5, check.rotationsSeen);
        assertEquals(1, check.attacks.size());
        assertEquals(42, check.attacks.get(0).entityId());
        assertEquals(5L, check.attacks.get(0).rotationSequence());
    }

    @Test
    void successiveAttacksKeepTheirOwnPositionsInTheRotationSequence() {
        RotationSequence sequence = new RotationSequence();
        AttackWatchingCheck check = new AttackWatchingCheck();
        sequence.addListener(check);

        for (int i = 1; i <= 2; i++) {
            RotationUpdate update = rotate(i);
            sequence.onRotationUpdate(update);
            check.onRotationUpdate(update);
        }
        sequence.onAttackObserved(7);

        for (int i = 3; i <= 6; i++) {
            RotationUpdate update = rotate(i);
            sequence.onRotationUpdate(update);
            check.onRotationUpdate(update);
        }
        sequence.onAttackObserved(9);

        assertEquals(2, check.attacks.size());
        assertEquals(7, check.attacks.get(0).entityId());
        assertEquals(2L, check.attacks.get(0).rotationSequence());
        assertEquals(9, check.attacks.get(1).entityId());
        assertEquals(6L, check.attacks.get(1).rotationSequence());
    }

    private static RotationUpdate rotate(float degrees) {
        return new RotationUpdate(
                new HeadRotation(0.0f, 0.0f),
                new HeadRotation(degrees, degrees),
                degrees,
                degrees);
    }

    /** A check that only consumes the attack seam. */
    private static final class AttackWatchingCheck implements AttackSignalListener {
        private int rotationsSeen;
        private final List<AttackAnchor> attacks = new ArrayList<>();

        void onRotationUpdate(RotationUpdate update) {
            rotationsSeen++;
        }

        @Override
        public void onAttack(AttackAnchor anchor) {
            attacks.add(anchor);
        }
    }
}
