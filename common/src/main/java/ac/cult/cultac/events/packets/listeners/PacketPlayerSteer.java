package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.checks.impl.movement.VehiclePredictionRunner;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;

public class PacketPlayerSteer {
    // LOW

    /**
     * LocalPlayer#tick (26.3: LocalPlayer#sendChanges) sends at most one player_input per client tick, before
     * Minecraft#tick's client_tick_end. A further one before that tick end is not forwarded and sets no keys, so the
     * server's keys only change with the input the tick's movement is judged by.
     */
    public void onPlayerInputArrival(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        // Clients before 1.21.2 send no tick end, and their translated inputs keep no per-tick count.
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) return;
        PacketStateData state = player.packetStateData;
        // In observe-only the codec forwards a cancelled packet, so the server applies every input.
        if (state.playerInputThisClientTick && !player.isObserveOnly()) event.cancelFinally();
        state.playerInputThisClientTick = true;
    }

    /** Every client_tick_end the client sent closes its input tick, also one a later handler rejects. */
    public void onClientTickEndArrival(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        player.packetStateData.playerInputThisClientTick = false;
    }

    @CultPacketHandler
    public void onPlayerInput(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        if (event.isCancelled()) {
            return;
        }
        player.isSneaking = packet.shift();
        player.packetStateData.respawnShiftPending = false;
        ServerboundPlayerInput input = packet;
        // Vanilla sends input only when key state changes; keep the latest raw state even before mounting.
        // Living vehicles read the KeyboardInput impulses (xxa/zza), where opposing keys cancel.
        // AbstractBoat#setInput instead receives the raw key bits, kept in nextVehicleKeys until a ridden tick end
        // gives them to the steered boat (BoatData#onClientTickEnd).
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
        player.packetStateData.localPlayerTickInputOrCommandThisClientTick = true;
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
        }
    }
}
