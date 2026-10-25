package cc.watchneko.manager;

import cc.watchneko.checks.Check;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import lombok.Getter;

@Getter
public class ActionManager extends Check implements PacketCheck {
    private boolean attacking = false;
    private long lastAttack = 0;

    public ActionManager(PlayerData player) {
        super(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity action = new WrapperPlayClientInteractEntity(event);
            if (action.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                onAttackObserved(action.getEntityId());
            }
        } else if (isTickPacketIncludingNonMovement(event.getPacketType())) {
            player.totalFlyingPacketsSent++;
            player.incrementRespawnTick();
            attacking = false;
        }
    }

    /**
     * Records one observed attack. The packet entry point above is only an adapter onto this method, so
     * the attack signal can be produced without a PacketEvents event.
     *
     * <p>Beyond the existing time-based bookkeeping this publishes the attack into the player's
     * {@link cc.watchneko.utils.anticheat.update.RotationSequence}, anchoring it at its position in the
     * rotation-update sequence.
     */
    public void onAttackObserved(final int entityId) {
        player.totalFlyingPacketsSent = 0;
        attacking = true;
        lastAttack = System.currentTimeMillis();
        player.rotationSequence.onAttackObserved(entityId);
    }

    public boolean hasAttackedSince(long time) {
        return System.currentTimeMillis() - lastAttack < time;
    }
}
