package ac.cult.cultac.checks;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;

/**
 * The yaw Minecraft#pick uses before a client tick's Rot arrives. Before 26.3 LocalPlayer#tick sends a rider's Rot
 * before Entity#rideTick's positionRider, so the next pick starts from the yaw that positionRider left.
 */
public final class RiderPickYaw {
    private PacketEntity vehicle;
    private float fromYaw;
    private float yaw;

    /** Records the yaw the client tick's positionRider left; call at the client tick end. */
    public void onTickEnd(CultPlayer player) {
        vehicle = null;
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)
                || !player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) {
            return;
        }
        PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        if (riding == null || !player.vehicleTurnsRider()) return;
        vehicle = riding;
        fromYaw = player.xRot;
        yaw = riding.isBoat() ? player.yawAfterRideTick() : Float.NaN;
    }

    /** The yaw of the pick behind a place or break that arrives before this tick's Rot; NaN when that is not one yaw. */
    public float beforeRot(CultPlayer player) {
        if (vehicle != null
                && player.compensatedEntities.getSelf().getRiding() == vehicle
                && player.xRot == fromYaw) {
            return yaw;
        }
        return player.xRot;
    }
}
