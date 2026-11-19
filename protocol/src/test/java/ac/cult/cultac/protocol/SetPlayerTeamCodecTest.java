package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.codec.entity.SetPlayerTeamCodec;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.Test;

class SetPlayerTeamCodecTest {
    @Test
    void receivedRulesAndMembershipFollowEverySupportedClientLayout() {
        for (var version : ProtocolVersion.values()) {
            ByteBuf input = Unpooled.buffer();
            try {
                Wire.writeString(input, "piston", 32767);
                input.writeByte(0);
                component(input);
                if (version.atLeast(ProtocolVersion.V26_2)) {
                    component(input);
                    component(input);
                    Wire.writeVarInt(input, 0);
                    Wire.writeVarInt(input, 1);
                    input.writeBoolean(true);
                    Wire.writeVarInt(input, 12);
                    input.writeByte(3);
                } else {
                    input.writeByte(3);
                    if (version.atLeast(ProtocolVersion.V1_21_5)) {
                        Wire.writeVarInt(input, 0);
                        Wire.writeVarInt(input, 1);
                    } else {
                        Wire.writeString(input, "always", 40);
                        Wire.writeString(input, "never", 40);
                    }
                    Wire.writeVarInt(input, 12);
                    component(input);
                    component(input);
                }
                Wire.writeVarInt(input, 2);
                Wire.writeString(input, "CPiston1214", 32767);
                Wire.writeString(input, "CPiston263", 32767);
                input.writeByte(0x5f);
                var context = new ProtocolContext(
                        ClientboundPackets.SET_PLAYER_TEAM,
                        ProtocolData.load(version),
                        ConnectionPhase.PLAY,
                        "minecraft:set_player_team",
                        0,
                        0);
                var packet = new SetPlayerTeamCodec().read(input, context);
                assertEquals("never", packet.collisionRule(), version.name());
                assertEquals(List.of("CPiston1214", "CPiston263"), packet.members());
                assertEquals(1, input.readableBytes());
                assertEquals(0x5f, input.readUnsignedByte());
            } finally {
                input.release();
            }
        }
    }

    private static void component(ByteBuf bytes) {
        bytes.writeByte(8).writeShort(0); // Anonymous NBT string: the empty component.
    }
}
