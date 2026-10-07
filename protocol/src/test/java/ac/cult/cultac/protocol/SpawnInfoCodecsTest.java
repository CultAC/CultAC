package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.codec.world.LoginCodec;
import ac.cult.cultac.protocol.codec.world.RespawnCodec;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SpawnInfoCodecsTest {
    @Test
    void loginReadsSeaLevelAfterOptionalDeathLocationOnEverySupportedWire() {
        for (var version : ProtocolVersion.values())
            for (boolean deathLocation : new boolean[] {false, true}) {
                ByteBuf bytes = Unpooled.buffer();
                try {
                    bytes.writeInt(42).writeBoolean(false);
                    Wire.writeVarInt(bytes, 1);
                    Wire.writeString(bytes, "cult:level", 32767);
                    Wire.writeVarInt(bytes, 20);
                    Wire.writeVarInt(bytes, 10);
                    Wire.writeVarInt(bytes, 8);
                    bytes.writeBoolean(false).writeBoolean(true).writeBoolean(false);
                    spawn(bytes, version, deathLocation, 130);
                    bytes.writeBoolean(true); // Remaining login option
                    var context = new ProtocolContext(
                            ClientboundPackets.LOGIN,
                            ProtocolData.load(version),
                            ConnectionPhase.PLAY,
                            "minecraft:login",
                            0,
                            0);
                    var login = new LoginCodec().read(bytes, context);
                    assertEquals(42, login.playerId());
                    assertTrue(login.showDeathScreen());
                    assertEquals(
                            new PlayerSpawnInfo(2, "cult:level", GameMode.ADVENTURE, 130, 1234), login.spawnInfo());
                    assertEquals(1, bytes.readableBytes());
                    assertTrue(bytes.readBoolean());
                } finally {
                    bytes.release();
                }
            }
    }

    @Test
    void respawnPreservesNegativeSeaLevelAndLeavesTheKeptDataByteUnread() {
        for (var version : ProtocolVersion.values())
            for (boolean deathLocation : new boolean[] {false, true}) {
                ByteBuf bytes = Unpooled.buffer();
                try {
                    spawn(bytes, version, deathLocation, -48);
                    bytes.writeByte(3);
                    var context = new ProtocolContext(
                            ClientboundPackets.RESPAWN,
                            ProtocolData.load(version),
                            ConnectionPhase.PLAY,
                            "minecraft:respawn",
                            0,
                            0);
                    var respawn = new RespawnCodec().read(bytes, context);
                    assertEquals(
                            new PlayerSpawnInfo(2, "cult:level", GameMode.ADVENTURE, -48, 1234), respawn.spawnInfo());
                    assertEquals(1, bytes.readableBytes());
                    assertEquals(3, bytes.readUnsignedByte());
                } finally {
                    bytes.release();
                }
            }
    }

    private static void spawn(ByteBuf bytes, ProtocolVersion version, boolean deathLocation, int seaLevel) {
        Wire.writeVarInt(bytes, 2);
        Wire.writeString(bytes, "cult:level", 32767);
        bytes.writeLong(1234);
        if (version.atLeast(ProtocolVersion.V26_3)) {
            Wire.writeVarInt(bytes, 2);
            Wire.writeVarInt(bytes, 4); // Previous spectator mode, optional ID + 1
        } else bytes.writeByte(2).writeByte(3);
        bytes.writeBoolean(true).writeBoolean(false).writeBoolean(deathLocation);
        if (deathLocation) {
            Wire.writeString(bytes, "cult:death_level", 32767);
            bytes.writeLong(0x123456789ABCDEFL);
        }
        Wire.writeVarInt(bytes, 300);
        Wire.writeVarInt(bytes, seaLevel);
    }
}
