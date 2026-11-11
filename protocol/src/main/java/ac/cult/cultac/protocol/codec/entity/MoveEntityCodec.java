/*
 * Read layout adapted from PacketEvents entity relative-move/rotation wrappers
 * and VecDelta/SteppedVecDelta, revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2026 PacketEvents contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.entity;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.VariantCodec;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.List;

public final class MoveEntityCodec implements VariantCodec<ClientboundMoveEntity> {
    private enum Variant {
        POS("move_entity_pos", true, false),
        POS_ROT("move_entity_pos_rot", true, true),
        ROT("move_entity_rot", false, true);

        private final String wireName;
        private final boolean position;
        private final boolean rotation;

        Variant(String wireName, boolean position, boolean rotation) {
            this.wireName = wireName;
            this.position = position;
            this.rotation = rotation;
        }
    }

    private static final Variant[] VARIANTS = Variant.values();
    private static final List<String> NAMES =
            Arrays.stream(VARIANTS).map(variant -> variant.wireName).toList();

    @Override
    public List<String> variants() {
        return NAMES;
    }

    @Override
    public ClientboundMoveEntity read(ByteBuf input, ProtocolContext context) {
        int entityId = Wire.readVarInt(input);
        Variant variant = VARIANTS[context.variant()];
        boolean position = variant.position;
        boolean rotation = variant.rotation;
        EntityDelta delta = EntityDelta.ZERO;
        boolean ground = false;
        boolean modern = context.version().atLeast(ProtocolVersion.V26_3);
        if (modern) {
            if (position) {
                int properties = Wire.readVarInt(input);
                ground = (properties & 1) != 0;
                delta = readDelta(input, properties >>> 1);
            } else ground = input.readBoolean();
        } else if (position) delta = readLinear(input);
        float yaw = rotation ? Wire.readAngle(input) : 0;
        float pitch = rotation ? Wire.readAngle(input) : 0;
        if (!modern) ground = input.readBoolean();
        return new ClientboundMoveEntity(entityId, delta, yaw, pitch, ground, position, rotation);
    }

    private static EntityDelta readDelta(ByteBuf input, int steps) {
        if (steps == 0) return readLinear(input);
        if (steps > input.readableBytes() / 7)
            throw new MalformedPacketException("Entity delta step count exceeds remaining bytes: " + steps);
        var result = new EntityDelta.Step[steps];
        for (int i = 0; i < steps; i++) {
            int ticks = Wire.readVarInt(input);
            result[i] = new EntityDelta.Step(input.readShort(), input.readShort(), input.readShort(), ticks);
        }
        return new EntityDelta.Stepped(List.of(result));
    }

    private static EntityDelta.Linear readLinear(ByteBuf input) {
        return new EntityDelta.Linear(input.readShort(), input.readShort(), input.readShort());
    }
}
