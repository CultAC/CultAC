package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;

public class PacketPlayerSteer {
    //LOW

    @CultPacketHandler
    public void onPlayerInput(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerInputPacket packet) {
        if (event.isCancelled()) {
            return;
        }
        // For 1.8-1.21.1, LegacyViaInputBridge applies the original sneak
        // command or mounted steer packet in client order. Via's periodically
        // synthesized input must not undo it. 1.7 uses older packet layouts.
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_1_8)
                || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
            player.isSneaking = packet.input().shift();
        }
        net.minecraft.world.entity.player.Input input = packet.input();
        // Vanilla sends input only when key state changes; keep the latest raw state even before mounting.
        float vehicleHoriz = input.left() ? 1.0F : (input.right() ? -1.0F : 0.0F);
        float vehicleForward = input.forward() ? 1.0F : (input.backward() ? -1.0F : 0.0F);
        boolean vehicleJump = input.jump();
        // MCP-Reborn LocalPlayer#tick sends ServerboundPlayerInputPacket before the move packet when
        // lastSentInput changes, and Input contains the full forward/back/left/right key bitset.
        player.packetStateData.clientMovementInputKnown = true;
        player.packetStateData.clientMovementInputUpdatedThisClientTick = true;
        player.packetStateData.clientMovementInput = new Vec3(vehicleHoriz, 0, vehicleForward);
        player.boatData.nextVehicleHoriz = vehicleHoriz;
        player.boatData.nextVehicleForward = vehicleForward;
        player.boatData.nextVehicleJump = vehicleJump;
        final PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        Integer serverVehicleId = player.compensatedEntities.vehicles.serverPlayerVehicle;
        PacketEntity serverVehicle = serverVehicleId == null ? null : player.compensatedEntities.getEntity(serverVehicleId);
        boolean ridingAuthoritativeVehicle = usesStagedMoveVehicleInput(riding);
        boolean observedAuthoritativeVehicle = usesStagedMoveVehicleInput(serverVehicle);
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

    private boolean usesStagedMoveVehicleInput(PacketEntity vehicle) {
        if (vehicle == null) {
            return false;
        }

        return EntityTypeUtil.isBoat(vehicle.type)
                || EntityTypeUtil.isHorseFamily(vehicle.type)
                || vehicle.type == EntityTypesCompat.PIG
                || vehicle.type == EntityTypesCompat.STRIDER
                || vehicle instanceof PacketEntityHappyGhast
                || vehicle instanceof PacketEntityNautilus;
    }

}
