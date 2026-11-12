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

    /**
     * Returned by {@link #read} in place of a move whose delta is linear when the context's state is a {@link Sink}
     * that {@link Sink#sinksMove() takes moves}, or stepped when it {@link Sink#sinksSteps takes its steps}; the
     * move's values went to {@link Sink#move} or {@link Sink#steppedMove} instead.
     */
    public static final ClientboundMoveEntity SUNK =
            new ClientboundMoveEntity(0, EntityDelta.ZERO, 0, 0, false, false, false);

    /** Codec state that takes a linear move's values in place of its packet, one move at a time. */
    public interface Sink {
        /** Whether the next linear move's values go to {@link #move}. */
        boolean sinksMove();

        /**
         * The values of a move that {@link MoveEntityCodec#read} returned as {@link #SUNK}: those of the packet it would have
         * returned, with {@code x}, {@code y} and {@code z} the components of its {@link EntityDelta.Linear} delta.
         */
        void move(
                int entityId,
                short x,
                short y,
                short z,
                float yaw,
                float pitch,
                boolean onGround,
                boolean hasPosition,
                boolean hasRotation);

        /** Whether the next stepped move of {@code steps} steps goes to {@link #step} and {@link #steppedMove}. */
        default boolean sinksSteps(int steps) {
            return false;
        }

        /**
         * Step {@code index} of the stepped move being read, in order; the values are only the move's once
         * {@link #steppedMove} follows.
         */
        default void step(int index, short x, short y, short z, int ticks) {}

        /**
         * The values of a stepped move that {@link MoveEntityCodec#read} returned as {@link #SUNK}: those of the packet it
         * would have returned, with its {@link EntityDelta.Stepped} delta as the {@code steps} values given to
         * {@link #step}. The move has a position.
         */
        default void steppedMove(
                int entityId, int steps, float yaw, float pitch, boolean onGround, boolean hasRotation) {}
    }

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
        EntityDelta.Stepped stepped = null;
        Sink stepSink = null;
        int steps = 0;
        short x = 0, y = 0, z = 0;
        boolean ground = false;
        boolean modern = context.version().atLeast(ProtocolVersion.V26_3);
        if (modern) {
            if (position) {
                int properties = Wire.readVarInt(input);
                ground = (properties & 1) != 0;
                steps = properties >>> 1;
                if (steps != 0) {
                    if (context.state() instanceof Sink sink && sink.sinksSteps(steps)) {
                        stepSink = sink;
                        readSteps(input, steps, sink);
                    } else stepped = readSteps(input, steps);
                } else {
                    x = input.readShort();
                    y = input.readShort();
                    z = input.readShort();
                }
            } else ground = input.readBoolean();
        } else if (position) {
            x = input.readShort();
            y = input.readShort();
            z = input.readShort();
        }
        float yaw = rotation ? Wire.readAngle(input) : 0;
        float pitch = rotation ? Wire.readAngle(input) : 0;
        if (!modern) ground = input.readBoolean();
        if (stepSink != null) {
            stepSink.steppedMove(entityId, steps, yaw, pitch, ground, rotation);
            return SUNK;
        }
        if (stepped != null)
            return new ClientboundMoveEntity(entityId, stepped, yaw, pitch, ground, position, rotation);
        if (context.state() instanceof Sink sink && sink.sinksMove()) {
            sink.move(entityId, x, y, z, yaw, pitch, ground, position, rotation);
            return SUNK;
        }
        EntityDelta delta = position ? new EntityDelta.Linear(x, y, z) : EntityDelta.ZERO;
        return new ClientboundMoveEntity(entityId, delta, yaw, pitch, ground, position, rotation);
    }

    private static void readSteps(ByteBuf input, int steps, Sink sink) {
        requireSteps(input, steps);
        for (int i = 0; i < steps; i++) {
            int ticks = Wire.readVarInt(input);
            sink.step(i, input.readShort(), input.readShort(), input.readShort(), ticks);
        }
    }

    private static void requireSteps(ByteBuf input, int steps) {
        if (steps > input.readableBytes() / 7)
            throw new MalformedPacketException("Entity delta step count exceeds remaining bytes: " + steps);
    }

    private static EntityDelta.Stepped readSteps(ByteBuf input, int steps) {
        requireSteps(input, steps);
        var result = new EntityDelta.Step[steps];
        for (int i = 0; i < steps; i++) {
            int ticks = Wire.readVarInt(input);
            result[i] = new EntityDelta.Step(input.readShort(), input.readShort(), input.readShort(), ticks);
        }
        return new EntityDelta.Stepped(List.of(result));
    }
}
