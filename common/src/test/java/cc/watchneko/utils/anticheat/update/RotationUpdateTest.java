package cc.watchneko.utils.anticheat.update;

import cc.watchneko.utils.data.HeadRotation;
import cc.watchneko.utils.math.Vec2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the test harness runs and that a rotation update can be synthesized from plain values,
 * without a PacketEvents event, a User or a channel.
 */
class RotationUpdateTest {

    @Test
    void rotationUpdateExposesSynthesizedAnglesAndDeltas() {
        HeadRotation from = new HeadRotation(10.0f, 20.0f);
        HeadRotation to = new HeadRotation(40.0f, 15.0f);

        RotationUpdate update = new RotationUpdate(from, to, 30.0f, -5.0f);

        assertEquals(10.0f, update.getFrom().getYaw());
        assertEquals(15.0f, update.getTo().getPitch());
        assertEquals(30.0f, update.getDeltaXRotABS());
        assertEquals(5.0f, update.getDeltaYRotABS());
        assertEquals(new Vec2f(30.0f, -5.0f), update.getDelta());
    }
}
