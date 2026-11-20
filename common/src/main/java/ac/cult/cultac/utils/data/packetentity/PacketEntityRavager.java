package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;

/** Ravager#aiStep's stun and roar countdowns, which a client from 1.21.2 to 1.21.5 runs to roar itself. */
public class PacketEntityRavager extends PacketEntity {
    private int stunnedTick;
    private int roarTick;
    // Whether this client tick's roar already pushed before the local player's travel.
    private boolean roaredBeforeLocalTravel;

    public PacketEntityRavager(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
    }

    /** Ravager#handleEntityEvent(39). */
    public void stun() {
        stunnedTick = 40;
    }

    public boolean isCountingDown() {
        return stunnedTick > 0 || roarTick > 0;
    }

    /** Whether this client tick's Ravager#aiStep runs roar, counting roarTick down to 10, and it has not pushed yet. */
    public boolean roarPendingThisTick(CultPlayer player) {
        return roarTick == 11 && !roaredBeforeLocalTravel && aiSteps(player);
    }

    public void markRoaredBeforeLocalTravel() {
        roaredBeforeLocalTravel = true;
    }

    @Override
    public void onMovement(CultPlayer player, boolean tickingReliably) {
        super.onMovement(player, tickingReliably);
        boolean roaredBefore = roaredBeforeLocalTravel;
        roaredBeforeLocalTravel = false;
        if (!isCountingDown() || !aiSteps(player)) return;
        boolean roars = false;
        if (roarTick > 0) {
            roarTick--;
            roars = roarTick == 10;
        }
        if (stunnedTick > 0 && --stunnedTick == 0) roarTick = 20;
        if (roars && !roaredBefore) player.checkManager.getExplosionHandler().onCountdownRoar(this);
        if (!isCountingDown()) player.checkManager.getExplosionHandler().onCountdownEnd(this);
    }

    // ClientLevel#tickEntities ticks the ravager, and Ravager#aiStep counts down only while it is alive.
    private boolean aiSteps(CultPlayer player) {
        return actionAlive
                && !isDead
                && !(player.packetStateData.serverTicksFrozen
                        && player.packetStateData.serverFrozenTickStepsRemaining == 0)
                && player.compensatedEntities.isTicking(this);
    }
}
