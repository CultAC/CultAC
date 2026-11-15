package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.movement.VehiclePredictionRunner;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedVehicleState;
import ac.cult.cultac.utils.math.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A loaded client's tick that sends the rider's Rot and no MoveVehicle. LocalPlayer#sendChanges follows the passenger
 * Rot with ServerboundMoveVehiclePacket#fromEntity whenever the root is local-authoritative, so such a tick is one in
 * which the controlled root travelled yet reports no movement. Its travel is predicted against its unchanged position,
 * as a MoveVehicle repeating that position would be.
 */
final class JavaUnreportedRootTick {
    private JavaUnreportedRootTick() {}

    static void travel(CultPlayer player) {
        PacketEntity root = unreportedRoot(player);
        if (root == null) return;
        PacketStateData state = player.packetStateData;
        state.markVehicleMove(true);
        state.clientTickVehicleMoveThisClientTick = true;
        // The rider's Rot left the root's pre-travel pass to its move.
        player.checkManager.getSimulationProcessor().applyEntityMovesBeforeTravel();
        state.receivedMovementThisClientTick = true;
        state.vehicleMovementStartOnGround = root.onGround;
        state.vehicleMovementOnGroundPresent = false;
        state.vehicleMovementReportedOnGround = false;
        Vec3 at = root.clientPhysicalPosition;
        float yaw = root.clientPhysicalYaw;
        float pitch = root.clientPhysicalPitch;
        if (root.isBoat()) player.boatData.tick(player);
        double lastX = player.lastX, lastY = player.lastY, lastZ = player.lastZ;
        double x = player.x, y = player.y, z = player.z;
        player.lastX = x;
        player.lastY = y;
        player.lastZ = z;
        player.x = at.x;
        player.y = at.y;
        player.z = at.z;
        SetbackTeleportUtil setbacks = player.getSetbackTeleportUtil();
        setbacks.holdSafePosition();
        try {
            player.checkManager.onVehiclePositionUpdate(
                    new VehiclePositionUpdate(at, at, yaw, pitch, root.onGround, false));
        } finally {
            setbacks.releaseSafePosition();
        }
        VehiclePredictionRunner runner = player.checkManager.getListener(VehiclePredictionRunner.class);
        Boolean correctedOnGround = runner.takeCorrectedOnGround();
        // AbstractBoat#controlBoat turned the boat in this tick too.
        float correctedYaw = runner.takeCorrectedYaw();
        if (!Float.isNaN(correctedYaw)) yaw = correctedYaw;
        if (CheckManagerListener.setbackBlocksVehicleMovement(player)) {
            player.lastX = lastX;
            player.lastY = lastY;
            player.lastZ = lastZ;
            player.x = x;
            player.y = y;
            player.z = z;
            return;
        }
        boolean onGround = correctedOnGround != null ? correctedOnGround : root.onGround;
        player.compensatedEntities.vehicles.applyControlledVehicleMove(root, at, onGround, yaw, pitch);
    }

    /** The root this tick left unreported; null when the client cannot be shown to control a ticking root in it. */
    @Nullable
    private static PacketEntity unreportedRoot(CultPlayer player) {
        PacketStateData state = player.packetStateData;
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)
                || !state.playerLoadedIntoLevel
                || !state.hasPassengerRotationThisClientTick()
                || state.clientTickVehicleMoveThisClientTick
                || CheckManagerListener.setbackBlocksVehicleMovement(player)) {
            return null;
        }
        CompensatedVehicleState vehicles = player.compensatedEntities.vehicles;
        PacketEntity root = player.compensatedEntities.getSelf().getRiding();
        // Pig and strider control follows the held item, which the vehicle-switch buffer owns.
        if (root == null
                || root.getRiding() != null
                || root.type == EntityTypeIds.PIG
                || root.type == EntityTypeIds.STRIDER
                || !root.actionAlive
                || !vehicles.mayLocalPlayerControl(root)
                || vehicles.isVehicleSwitchBufferActiveFor(root)
                || vehicles.serverVehicleEntity() != root
                || !vehicles.hasClientObservedServerVehicle()
                || vehicles.hasPendingServerDismount()
                || root.hasActiveLocalRootInterpolation()
                || root.clientPhysicalPosition == null
                || !root.clientPhysicalPositionExact
                || !root.clientPhysicalYawCurrent
                || !player.compensatedEntities.ticksInClientTick(root)) {
            return null;
        }
        return root;
    }
}
