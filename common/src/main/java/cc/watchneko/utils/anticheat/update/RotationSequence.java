package cc.watchneko.utils.anticheat.update;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered stream of rotation updates a player produced, plus the attack signal anchored in it.
 *
 * <p>This is the central, framework-free seam a check can consume instead of parsing
 * {@code PacketType.Play.Client.INTERACT_ENTITY} itself. It records two things only:
 *
 * <ul>
 *   <li>that a rotation update was observed, which advances the sequence position;
 *   <li>that an attack was observed, which is anchored at the current sequence position and handed to
 *       every {@link AttackSignalListener}.
 * </ul>
 *
 * <p>The position is a count of rotation updates, not wall-clock time, so a check can locate an attack
 * in the rotation-update sequence and centre a sample window on it. Nothing here touches PacketEvents,
 * which is what makes it drivable from tests.
 */
public final class RotationSequence {
    private final List<AttackSignalListener> listeners = new ArrayList<>(2);
    private long position;

    public void addListener(final AttackSignalListener listener) {
        listeners.add(listener);
    }

    /** Records that one more rotation update was observed. */
    public void onRotationUpdate(final RotationUpdate update) {
        position++;
    }

    /** Records that an attack was observed, anchored at the current rotation-update position. */
    public void onAttackObserved(final int entityId) {
        final AttackAnchor anchor = new AttackAnchor(position, entityId);
        for (final AttackSignalListener listener : listeners) {
            listener.onAttack(anchor);
        }
    }
}
