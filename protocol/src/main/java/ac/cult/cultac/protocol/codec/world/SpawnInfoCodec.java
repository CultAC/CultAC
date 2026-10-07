/*
 * Consumed prefix adapted from PacketEvents WrapperPlayServerJoinGame/Respawn,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.world;

import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

final class SpawnInfoCodec {
    private SpawnInfoCodec() {}

    static PlayerSpawnInfo read(ByteBuf input, boolean varIntGameMode) {
        int dimensionType = Wire.readVarInt(input);
        String dimension = Wire.readIdentifier(input);
        long biomeZoomSeed = input.readLong();
        // 26.3's CommonPlayerSpawnInfo uses GameType.STREAM_CODEC (VarInt).
        int mode = varIntGameMode ? Wire.readVarInt(input) : input.readByte();
        // GameType.byId uses ZERO for every out-of-range ID on all supported versions.
        GameMode gameMode = switch (mode) {
            case 1 -> GameMode.CREATIVE;
            case 2 -> GameMode.ADVENTURE;
            case 3 -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
        if (varIntGameMode) Wire.readVarInt(input); // Optional previous game mode
        else input.readByte();
        input.readBoolean(); // Debug level
        input.readBoolean(); // Flat level
        if (input.readBoolean()) {
            Wire.readIdentifier(input); // Last-death dimension
            input.skipBytes(Long.BYTES); // Last-death position
        }
        Wire.readVarInt(input); // Portal cooldown
        int seaLevel = Wire.readVarInt(input); // Present on every supported version (1.21.3+).
        return new PlayerSpawnInfo(dimensionType, dimension, gameMode, seaLevel, biomeZoomSeed);
    }
}
