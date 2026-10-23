package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.RotationListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import org.jetbrains.annotations.NotNull;

// cinematic camera doesn't apply in inventories.
@CheckData(
        name = "MultiActionsO",
        stableKey = "cult.multiactions.inventory_rotation",
        description = "Rotating while in an inventory")
public class MultiActionsO extends Check implements RotationListener, ClientTickEndListener {
    // From 26.3, a boat rider's Rot awaiting the tick end: the client sends it before the vehicle move that carries the
    // turn AbstractBoat#positionRider gave it this tick.
    private boolean boatLookPending;
    private float yawBefore;
    private float pitchBefore;
    private float sentYaw;
    private float sentPitch;
    // Before 26.3 LocalPlayer#tick sends the Rot from Entity#rideTick, before AbstractBoat#positionRider turns the
    // rider, so a tick's Rot carries the turn of the tick before: the boat ridden at the last tick end, the yaw held
    // then and the yaw positionRider left.
    private boolean boatLookSeen;
    private PacketEntity rideTickBoat;
    private float rideTickFromYaw;
    private float rideTickYaw;

    public MultiActionsO(CultPlayer player) {
        super(player);
    }

    @Override
    public void process(@NotNull RotationUpdate rotationUpdate) {
        if (player.packetStateData.lastPacketWasTeleport) return;
        // LocalPlayer#sendChanges sends a passenger's Rot every tick, changed or not, where sendPosition only sends a
        // Rot that changed. A look that did not change is not a rotation.
        if (rotationUpdate.getFrom().equals(rotationUpdate.getTo())) return;
        if (!player.openWindow.mustBeOpen() || player.openWindow.getTicksOpen() == 0) return;
        // positionRider runs once per client tick, so only a tick's first Rot can carry a boat's turn.
        if (!boatLookSeen && ridesBoat()) {
            boatLookSeen = true;
            if (player.getClientVersion().isOlderThan(ClientVersion.V_26_3)) {
                float pitchFrom = rotationUpdate.getFrom().pitch();
                if (rotationUpdate.getTo().yaw() != rideTurnedYaw()
                        || rotationUpdate.getTo().pitch() != pitchFrom) {
                    flagRotation();
                }
                return;
            }
            boatLookPending = true;
            yawBefore = player.yawBeforeLook;
            pitchBefore = rotationUpdate.getFrom().pitch();
            sentYaw = rotationUpdate.getTo().yaw();
            sentPitch = rotationUpdate.getTo().pitch();
            return;
        }
        flagRotation();
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        boatLookSeen = false;
        if (boatLookPending) {
            boatLookPending = false;
            if (!player.boatYawFollowsTurn()
                    || sentYaw != player.yawWithoutLook(yawBefore)
                    || sentPitch != pitchBefore) {
                flagRotation();
            }
        }
        rideTickBoat = ridesBoat() ? player.compensatedEntities.getSelf().getRiding() : null;
        rideTickFromYaw = player.xRot;
        rideTickYaw = rideTickBoat == null ? Float.NaN : player.yawAfterRideTick();
    }

    // The yaw a Rot before 26.3 sends without a mouse turn. ClientPacketListener#handleSetEntityPassengersPacket
    // turns a player newly aboard a boat to the boat's yaw, which no positionRider turns before the Rot.
    private float rideTurnedYaw() {
        if (player.compensatedEntities.getSelf().getRiding() == rideTickBoat
                && player.yawBeforeLook == rideTickFromYaw) {
            return rideTickYaw;
        }
        return player.yawBeforeLook;
    }

    private boolean ridesBoat() {
        PacketEntity vehicle = player.compensatedEntities.getSelf().getRiding();
        return vehicle != null && vehicle.isBoat();
    }

    private void flagRotation() {
        if (flag() && shouldModifyPackets()) {
            player.closeInventorySafely();
        }
    }
}
