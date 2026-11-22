package ac.cult.cultac.bedrock.replay.offline;

import static org.mockito.Mockito.*;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientInformation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import java.util.Optional;

/** Record events for state-level tests; byte/native parity is exercised separately. */
final class RecordReceiveTestEvents {
    private RecordReceiveTestEvents() {}

    static PacketReceiveEvent<ServerboundKeepAlive> keepAlive(CultPlayer player, ServerboundKeepAlive packet) {
        return record(player, ServerboundPackets.KEEP_ALIVE, "minecraft:keep_alive", packet);
    }

    static PacketReceiveEvent<Opaque> signUpdate(CultPlayer player) {
        return record(
                player,
                ServerboundPackets.SIGN_UPDATE,
                "minecraft:sign_update",
                ServerboundPackets.SIGN_UPDATE.opaqueValue());
    }

    static PacketReceiveEvent<ServerboundPlayerAction> playerAction(CultPlayer player, ServerboundPlayerAction packet) {
        return record(player, ServerboundPackets.PLAYER_ACTION, "minecraft:player_action", packet);
    }

    static PacketReceiveEvent<ServerboundPlayerCommand> playerCommand(
            CultPlayer player, ServerboundPlayerCommand packet) {
        return record(player, ServerboundPackets.PLAYER_COMMAND, "minecraft:player_command", packet);
    }

    static PacketReceiveEvent<ServerboundInteract> attack(CultPlayer player, int entityId) {
        return record(
                player,
                ServerboundPackets.INTERACT,
                "minecraft:attack",
                new ServerboundInteract(entityId, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false));
    }

    static PacketReceiveEvent<ServerboundMoveVehicle> vehicle(CultPlayer player, ServerboundMoveVehicle packet) {
        return record(player, ServerboundPackets.MOVE_VEHICLE, "minecraft:move_vehicle", packet);
    }

    static PacketReceiveEvent<ServerboundAcceptTeleportation> teleport(
            CultPlayer player, ServerboundAcceptTeleportation packet) {
        return record(player, ServerboundPackets.ACCEPT_TELEPORTATION, "minecraft:accept_teleportation", packet);
    }

    static PacketReceiveEvent<ServerboundSwing> swing(CultPlayer player, ServerboundSwing packet) {
        return record(player, ServerboundPackets.SWING, "minecraft:swing", packet);
    }

    static PacketReceiveEvent<ServerboundPong> pong(CultPlayer player, int id) {
        return record(player, ServerboundPackets.PONG, "minecraft:pong", new ServerboundPong(id));
    }

    static PacketReceiveEvent<ServerboundPlayerInput> input(CultPlayer player, ServerboundPlayerInput input) {
        return record(player, ServerboundPackets.PLAYER_INPUT, "minecraft:player_input", input);
    }

    static PacketReceiveEvent<Opaque> tickEnd(CultPlayer player) {
        return record(
                player,
                ServerboundPackets.CLIENT_TICK_END,
                "minecraft:client_tick_end",
                ServerboundPackets.CLIENT_TICK_END.opaqueValue());
    }

    static PacketReceiveEvent<ServerboundCustomPayload> customPayload(
            CultPlayer player, ServerboundCustomPayload packet) {
        return record(player, ServerboundPackets.CUSTOM_PAYLOAD, "minecraft:custom_payload", packet);
    }

    static PacketReceiveEvent<ServerboundMovePlayer> movement(CultPlayer player, ServerboundMovePlayer packet) {
        String variant = packet.hasPosition()
                ? (packet.hasRotation() ? "pos_rot" : "pos")
                : (packet.hasRotation() ? "rot" : "status_only");
        return record(player, ServerboundPackets.MOVE_PLAYER, "minecraft:move_player_" + variant, packet);
    }

    static PacketReceiveEvent<ServerboundClientInformation> clientInformation(
            CultPlayer player, ServerboundClientInformation packet, ConnectionPhase phase) {
        return record(player, ServerboundPackets.CLIENT_INFORMATION, "minecraft:client_information", packet, phase);
    }

    private static <T extends ServerboundPacket> PacketReceiveEvent<T> record(
            CultPlayer player, PacketType<T> type, String name, T packet) {
        return record(player, type, name, packet, ConnectionPhase.PLAY);
    }

    private static <T extends ServerboundPacket> PacketReceiveEvent<T> record(
            CultPlayer player, PacketType<T> type, String name, T packet, ConnectionPhase phase) {
        return new PacketReceiveEvent<>(player.user, phase, type, packet);
    }
}
