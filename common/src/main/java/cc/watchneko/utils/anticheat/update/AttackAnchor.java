package cc.watchneko.utils.anticheat.update;

/**
 * A position in the {@link RotationSequence} at which an attack was observed.
 *
 * <p>The anchor is an immutable snapshot: later rotation updates do not move it. This is what lets a
 * check centre a sample window on an attack instead of merely asking whether one happened recently.
 *
 * @param rotationSequence how many rotation updates the sequence had observed when the attack arrived
 * @param entityId         the entity the attack was aimed at
 */
public record AttackAnchor(long rotationSequence, int entityId) {
}
