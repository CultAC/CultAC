/*
 * Framing adapted from PacketEvents WrapperPlayServerTimeUpdate, ClockNetworkState,
 * RecipePropertySet, WrapperConfigServerUpdateEnabledFeatures and WrapperPlayServerDifficulty
 * at 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022-2026 retrooper and contributors.
 * Licensed under GPL-3.0-or-later; see https://www.gnu.org/licenses/.
 */
package ac.cult.cultac.network.codec;

import ac.cult.blocksim.engine.ClientClocks;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.HashSet;
import java.util.function.BiFunction;

/** Name-based received values shared by the native and older-wire adapters. */
final class ClientMetadataValues {
    private ClientMetadataValues() {}

    static WorldPackets.EnabledFeatures features(ByteBuf input) {
        int count = Wire.readLength(input, input.readableBytes());
        var features = new HashSet<String>();
        for (int index = 0; index < count; index++) features.add(Wire.readString(input, Wire.MAX_STRING_LENGTH));
        return new WorldPackets.EnabledFeatures(features);
    }

    static WorldPackets.Difficulty difficulty(ByteBuf input, ProtocolVersion version) {
        int difficulty = version.atLeast(ProtocolVersion.V1_21_6) ? Wire.readVarInt(input) : input.readUnsignedByte();
        input.readBoolean();
        return new WorldPackets.Difficulty(Math.floorMod(difficulty, 4) == 0);
    }

    static WorldPackets.TimeUpdate time(
            ByteBuf input, ProtocolVersion version, BiFunction<String, Integer, String> names) {
        long gameTime = input.readLong();
        var clocks = new HashMap<String, ClientClocks.State>();
        if (version.atLeast(ProtocolVersion.V26_1)) {
            int count = Wire.readLength(input, input.readableBytes());
            for (int index = 0; index < count; index++) {
                String name = names.apply("minecraft:world_clock", Wire.readVarInt(input));
                clocks.put(name, new ClientClocks.State(Wire.readVarLong(input), input.readFloat(), input.readFloat()));
            }
        } else {
            long totalTicks = input.readLong();
            // Supported backends (1.21.2+) send the separate tick-time boolean.
            boolean ticking = input.readBoolean();
            clocks.put("minecraft:overworld", new ClientClocks.State(totalTicks, 0, ticking ? 1 : 0));
        }
        return new WorldPackets.TimeUpdate(gameTime, clocks);
    }

    /** The stonecutter display suffix is unrelated to interaction input membership. */
    static WorldPackets.RecipeInputs recipes(ByteBuf input, BiFunction<String, Integer, String> names) {
        int count = Wire.readLength(input, input.readableBytes());
        var inputs = new HashMap<String, java.util.Set<String>>();
        for (int index = 0; index < count; index++) {
            String key = Wire.readString(input, Wire.MAX_STRING_LENGTH);
            int size = Wire.readLength(input, input.readableBytes());
            var items = new HashSet<String>();
            for (int item = 0; item < size; item++) items.add(names.apply("minecraft:item", Wire.readVarInt(input)));
            inputs.put(key, items);
        }
        return new WorldPackets.RecipeInputs(inputs);
    }
}
