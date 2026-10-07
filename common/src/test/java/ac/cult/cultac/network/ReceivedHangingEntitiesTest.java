package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.testing.CodecFixture;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.blockplace.BlockSimulatorWorldView;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHanging;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ReceivedHangingEntitiesTest {
    @Test
    void compensatedFramesAndPaintingsUseReceivedDimensionsAndUniqueFrameOutput() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var type = ac.cult.blocksim.entity.EntityTypes.defaults()
                    .byKey("minecraft:item_frame")
                    .id();
            var position = new Vec3(1, 64, 1);
            player.compensatedEntities.addEntity(42, type, position, 0, 0, Direction.NORTH.ordinal());
            var frame = (PacketEntityHanging) player.compensatedEntities.getEntity(42);
            assertEquals(new Box(1.125, 64.125, 1.9375, 1.875, 64.875, 2), frame.actionBounds());
            var codec = new CodecFixture(ProtocolRuntime.create(
                    ProtocolData.load(ProtocolVersion.V26_3), OfflineCultTestBootstrap.catalog()));
            codec.phase(ConnectionPhase.PLAY);
            var metadata = Unpooled.buffer();
            try {
                Wire.writeVarInt(metadata, 42);
                metadata.writeByte(8);
                Wire.writeVarInt(metadata, 7); // Item metadata.
                Wire.writeVarInt(metadata, 1);
                var names = ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults();
                Wire.writeVarInt(metadata, names.id("minecraft:item", "minecraft:filled_map"));
                Wire.writeVarInt(metadata, 2);
                Wire.writeVarInt(metadata, 0);
                Wire.writeVarInt(metadata, names.id("minecraft:data_component_type", "minecraft:map_id"));
                Wire.writeVarInt(metadata, 5);
                Wire.writeVarInt(metadata, names.id("minecraft:data_component_type", "minecraft:custom_data"));
                // Compound {opaque: -0.0F}, retained as the received wire encoding.
                metadata.writeByte(10).writeByte(5);
                new io.netty.buffer.ByteBufOutputStream(metadata).writeUTF("opaque");
                metadata.writeFloat(-0.0F).writeByte(0);
                metadata.writeByte(9);
                Wire.writeVarInt(metadata, 1); // Rotation integer.
                Wire.writeVarInt(metadata, 3);
                metadata.writeByte(255);
                var packet = decodedMetadata(codec, metadata);
                var received = assertInstanceOf(
                        SimItemStack.class, packet.packedItems().getFirst().value());
                assertEquals("minecraft:filled_map", received.itemKey());
                assertTrue(received.components().has("minecraft:map_id"));
                assertTrue(received.components().has("minecraft:custom_data"));
                var rewritten = Unpooled.buffer();
                try {
                    codec.write(packet, rewritten);
                    ac.cult.cultac.protocol.wire.Wire.readVarInt(rewritten);
                    assertArrayEquals(ByteBufUtil.getBytes(metadata), ByteBufUtil.getBytes(rewritten));
                } finally {
                    rewritten.release();
                }
                player.compensatedEntities.updateEntityMetadata(packet.id(), packet.packedItems());
            } finally {
                metadata.release();
            }
            assertEquals(new Box(1, 64, 1.9375, 2, 65, 2), frame.actionBounds());
            var view = new BlockSimulatorWorldView(
                    player.compensatedWorld,
                    player.checkManager.getListener(PacketWorldBorder.class),
                    ModelBlockStates.project(ProtocolVersion.V26_3, ProtocolVersion.V26_3));
            assertEquals(4, view.itemFrameOutputAt(new BlockPos(1, 64, 1), Direction.NORTH));
            player.compensatedEntities.addEntity(43, type, position, 0, 0, Direction.NORTH.ordinal());
            assertEquals(Integer.MIN_VALUE, view.itemFrameOutputAt(new BlockPos(1, 64, 1), Direction.NORTH));
            player.compensatedEntities.removeEntity(43);
            assertEquals(4, view.itemFrameOutputAt(new BlockPos(1, 64, 1), Direction.NORTH));

            var paintingType = ac.cult.blocksim.entity.EntityTypes.defaults()
                    .byKey("minecraft:painting")
                    .id();
            player.compensatedEntities.addEntity(44, paintingType, new Vec3(-5, 64, 0), 0, 0, Direction.EAST.ordinal());
            metadata = Unpooled.buffer();
            try {
                Wire.writeVarInt(metadata, 44);
                metadata.writeByte(8);
                Wire.writeVarInt(metadata, 34); // Painting variant metadata.
                Wire.writeVarInt(metadata, 0); // Inline holder.
                Wire.writeVarInt(metadata, 4);
                Wire.writeVarInt(metadata, 3);
                Wire.writeString(metadata, "test:asset", 32767);
                metadata.writeByte(0).writeByte(0).writeByte(255); // No title or author.
                var packet = decodedMetadata(codec, metadata);
                player.compensatedEntities.updateEntityMetadata(packet.id(), packet.packedItems());
            } finally {
                metadata.release();
            }
            var painting = (PacketEntityHanging) player.compensatedEntities.getEntity(44);
            assertEquals(new Box(-5, 63, -2, -4.9375, 66, 2), painting.actionBounds());
            assertTrue(view.hasHangingEntityOverlap(
                    painting.actionBounds(), Direction.NORTH, "minecraft:painting", false));
            assertFalse(view.hasHangingEntityOverlap(
                    painting.actionBounds(), Direction.NORTH, "minecraft:item_frame", true));
        }
    }

    private static EntityMetadata decodedMetadata(CodecFixture codec, io.netty.buffer.ByteBuf input) {
        int id = ProtocolData.load(ProtocolVersion.V26_3)
                .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                .id("minecraft:set_entity_data");
        return (EntityMetadata) codec.read(PacketDirection.CLIENTBOUND, id, input);
    }
}
