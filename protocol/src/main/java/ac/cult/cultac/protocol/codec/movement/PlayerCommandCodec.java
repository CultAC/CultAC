/*
 * Read layout adapted from PacketEvents WrapperPlayClientEntityAction,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.movement;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class PlayerCommandCodec implements PacketCodec<ServerboundPlayerCommand> {
    private static final PlayerCommandAction[] ACTIONS = PlayerCommandAction.values();

    @Override
    public ServerboundPlayerCommand read(ByteBuf input, ProtocolContext context) {
        int entityId = Wire.readVarInt(input);
        int actionId = Wire.readVarInt(input);
        // V1_21_6 removed the first two actions.
        int offset = context.version().atLeast(ProtocolVersion.V1_21_6) ? 2 : 0;
        if (actionId < 0 || actionId >= ACTIONS.length - offset) {
            throw new MalformedPacketException("Invalid player command action " + actionId);
        }
        return new ServerboundPlayerCommand(entityId, ACTIONS[actionId + offset], Wire.readVarInt(input));
    }
}
