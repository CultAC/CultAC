package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

/** The pre-1.21.6 client's Ravager#aiStep countdown, advanced only when ClientLevel ticks this entity. */
public final class PacketEntityRavager extends PacketEntity {
    private int stunnedTicks;
    private int roarTicks;
    private int roaredAt = Integer.MIN_VALUE;

    public PacketEntityRavager(CultPlayer player, int id, int type, Vec3 position) {
        super(player, id, type, position.x, position.y, position.z);
    }

    public static boolean usesRoarCountdown(ClientVersion version) {
        return version.isNewerThanOrEquals(ClientVersion.V_1_21_2) && version.isOlderThan(ClientVersion.V_1_21_6);
    }

    public void stun() {
        // Event 39 overwrites only stunnedTick; an already running roarTick keeps counting down.
        stunnedTicks = 40;
    }

    public boolean roarsThisTick(CultPlayer player) {
        return usesRoarCountdown(player.getClientVersion())
                && actionAlive
                && !isDead
                && (tickedThisTick(player) ? roaredAt == player.packetStateData.acceptedClientTick : roarTicks == 11);
    }

    @Override
    public void onMovement(CultPlayer player) {
        super.onMovement(player);
        if (player.isBedrockMovement() || !usesRoarCountdown(player.getClientVersion()) || !actionAlive || isDead)
            return;
        // Ravager#aiStep runs super.aiStep (including interpolation and ordinary pushes) first, then roar,
        // then stun. The tick that ends the stun sets roarTick=20 without decrementing it again.
        if (roarTicks > 0 && --roarTicks == 10) roaredAt = player.packetStateData.acceptedClientTick;
        if (stunnedTicks > 0 && --stunnedTicks == 0) roarTicks = 20;
    }
}
