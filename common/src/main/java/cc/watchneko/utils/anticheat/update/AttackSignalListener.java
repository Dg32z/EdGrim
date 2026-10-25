package cc.watchneko.utils.anticheat.update;

/**
 * Consumes the central attack signal instead of parsing {@code INTERACT_ENTITY} itself.
 *
 * <p>Implementations are notified once per observed attack, with the anchor locating that attack in
 * the rotation-update sequence.
 */
public interface AttackSignalListener {

    void onAttack(AttackAnchor anchor);
}
