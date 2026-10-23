package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.checks.impl.movement.VehiclePredictionRunner;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;

public class PacketPlayerSteer {
    // LOW

    @CultPacketHandler
    public void onPlayerInput(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        if (event.isCancelled()) {
            return;
        }
        player.isSneaking = packet.shift();
        ServerboundPlayerInput input = packet;
        // Vanilla sends input only when key state changes; keep the latest raw state even before mounting.
        // Living vehicles read the KeyboardInput impulses (xxa/zza), where opposing keys cancel.
        // AbstractBoat#setInput instead receives the raw key bits, kept separately in vehicleKeys.
        KnownInput vehicleKeys = new KnownInput(
                input.forward(),
                input.backward(),
                input.left(),
                input.right(),
                input.jump(),
                input.shift(),
                input.sprint());
        float vehicleHoriz = vehicleKeys.strafeImpulse();
        float vehicleForward = vehicleKeys.forwardImpulse();
        boolean vehicleJump = input.jump();
        // MCP-Reborn LocalPlayer#tick sends ServerboundPlayerInputPacket before the move packet when
        // lastSentInput changes, and Input contains the full forward/back/left/right key bitset.
        player.packetStateData.clientMovementInputKnown = true;
        player.packetStateData.clientMovementInputUpdatedThisClientTick = true;
        player.packetStateData.clientMovementInput = new Vec3(vehicleHoriz, 0, vehicleForward);
        player.boatData.nextVehicleHoriz = vehicleHoriz;
        player.boatData.nextVehicleForward = vehicleForward;
        player.boatData.nextVehicleJump = vehicleJump;
        player.boatData.nextVehicleKeys = vehicleKeys;
        final PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        Integer serverVehicleId = player.compensatedEntities.vehicles.serverPlayerVehicle;
        PacketEntity serverVehicle =
                serverVehicleId == null ? null : player.compensatedEntities.getEntity(serverVehicleId);
        boolean ridingAuthoritativeVehicle = VehiclePredictionRunner.usesStagedVehicleInput(riding);
        boolean observedAuthoritativeVehicle = VehiclePredictionRunner.usesStagedVehicleInput(serverVehicle);
        // MCP-Reborn ClientLevel#tickNonPassenger moves local-authoritative
        // rideable roots before ClientLevel#tickPassenger runs
        // LocalPlayer#rideTick. A raw input packet in the same client tick
        // is therefore the next root tick's staged input for boats, horses,
        // pigs, striders, and happy ghasts. Non-staged states can still use
        // the latest packet value immediately.
        if (!ridingAuthoritativeVehicle && !observedAuthoritativeVehicle) {
            player.boatData.vehicleHoriz = vehicleHoriz;
            player.boatData.vehicleForward = vehicleForward;
            player.boatData.vehicleJump = vehicleJump;
            player.boatData.vehicleKeys = vehicleKeys;
        }
    }
}
