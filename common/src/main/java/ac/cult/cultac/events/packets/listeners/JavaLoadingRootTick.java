package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaUnreportedTravel;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.math.Vec3;

/**
 * A client tick while ClientPacketListener#hasClientLoaded is false. LocalPlayer#tick and LocalPlayer#sendChanges wait
 * for it, but Minecraft#tick still runs ClientLevel#tickEntities, so the root the local player controls travels and no
 * MoveVehicle reports it. Its rider is a LocalPlayer that holds no xxa/zza and is not jumping, which
 * AbstractHorse#getRiddenInput reads, while Pig/Strider#getRiddenInput is always forward. That travel runs as the
 * MoveVehicle reporting the engine's own end; where the engine's candidates disagree on the end, it is not predicted.
 */
final class JavaLoadingRootTick {
    private JavaLoadingRootTick() {}

    static void travel(CultPlayer player) {
        PacketStateData state = player.packetStateData;
        PacketEntity root = player.compensatedEntities.getSelf().getRiding();
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_4)
                || state.playerLoadedIntoLevel
                || root == null
                || !(root instanceof PacketEntityHorse
                        || root.type == EntityTypeIds.PIG
                        || root.type == EntityTypeIds.STRIDER)
                || root.getRiding() != null
                || !player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(root)
                || !player.compensatedEntities.ticksInClientTick(root)
                || CheckManagerListener.setbackBlocksVehicleMovement(player)) return;
        state.markVehicleMove(true);
        state.clientTickVehicleMoveThisClientTick = true;
        // Pig#tickRidden and Strider#tickRidden tick the boost inside travelRidden, gated on isAlive().
        if (root instanceof PacketEntityRideable rideable && rideable.actionAlive && rideable.hasSaddle)
            rideable.boost.tick(state.acceptedClientTick);
        if (!state.receivedMovementThisClientTick)
            player.checkManager.getSimulationProcessor().applyEntityMovesBeforeTravel();
        state.receivedMovementThisClientTick = true;
        state.vehicleMovementStartOnGround = root.onGround;
        state.vehicleMovementOnGroundPresent = true;
        root.interpolateBeforeLocalTravel(player);
        // tickRidden turns the root to the rider's look, which Camel#getRiddenRotation keeps while it refuses to move.
        boolean keepsRotation =
                root instanceof PacketEntityCamel camel && camel.refuseToMove(player.compensatedWorld.clientGameTime());
        float yaw = keepsRotation ? root.clientPhysicalYaw : player.xRot;
        float pitch = keepsRotation ? root.clientPhysicalPitch : player.yRot * 0.5F;
        Vec3 from = CheckManagerListener.currentVehiclePhysicalPosition(root, new Vec3(player.x, player.y, player.z));
        JavaUnreportedTravel.End end =
                player.checkManager.getSimulationProcessor().unreportedVehicleTravel(root, from, yaw, pitch);
        if (end == null) return;
        Vec3 to = end.position();
        player.lastX = player.x;
        player.lastY = player.y;
        player.lastZ = player.z;
        player.x = to.x;
        player.y = to.y;
        player.z = to.z;
        SetbackTeleportUtil setbacks = player.getSetbackTeleportUtil();
        setbacks.holdSafePosition();
        try {
            player.checkManager.onVehiclePositionUpdate(
                    new VehiclePositionUpdate(from, to, yaw, pitch, end.onGround(), true));
        } finally {
            setbacks.releaseSafePosition();
        }
        player.compensatedEntities.vehicles.applyControlledVehicleMove(root, to, end.onGround(), yaw, pitch);
    }
}
