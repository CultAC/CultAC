/*
 * Layout adapted from PacketEvents WrapperPlayClientPlayerDigging,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.interaction;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WritablePacketCodec;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class PlayerActionCodec implements WritablePacketCodec<ServerboundPlayerAction> {
    private static final PlayerAction[] ACTIONS = PlayerAction.values();
    private static final Direction[] DIRECTIONS = Direction.values();

    @Override
    public ServerboundPlayerAction read(ByteBuf input, ProtocolContext context) {
        int actionId = Wire.readVarInt(input);
        var version = context.version();
        int count = version.atLeast(ProtocolVersion.V26_3) ? 9 : version.atLeast(ProtocolVersion.V1_21_11) ? 8 : 7;
        if (actionId < 0 || actionId >= count) throw new MalformedPacketException("Invalid player action " + actionId);
        PlayerAction action =
                ACTIONS[version.atLeast(ProtocolVersion.V26_3) || actionId == 0 ? actionId : actionId + 1];
        var position = Wire.readBlockPos(input);
        // Vanilla applies abs(id % 6) to an unsigned byte, including noncanonical faces.
        Direction direction = DIRECTIONS[input.readUnsignedByte() % DIRECTIONS.length];
        return new ServerboundPlayerAction(action, position, direction, Wire.readVarInt(input));
    }

    @Override
    public void write(ByteBuf output, ProtocolContext context, ServerboundPlayerAction packet) {
        Wire.writeVarInt(output, packet.action().wireId(context.version()));
        Wire.writeBlockPos(output, packet.position());
        output.writeByte(packet.direction().ordinal());
        Wire.writeVarInt(output, packet.sequence());
    }
}
