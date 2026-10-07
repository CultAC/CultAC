package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.codec.ObservedPacketValues;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.testing.CodecFixture;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ReceivedEntityValuesTest {
    @Test
    void receivedCrouchingPoseChangesRemoteBodyThroughBothDecoders() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.compensatedEntities.addEntity(42, EntityTypeIds.PLAYER, new Vec3(0, 64, 0), 0, 0, 0);
            var remote = (PacketEntityPlayer) player.compensatedEntities.getEntity(42);
            var position = new SimpleCollisionBox(-.3, 64, -.3, .3, 65.8, .3);
            assertEquals((double) 1.8F, remote.actionBounds(position).maxY() - 64);
            var data = ProtocolData.load(ProtocolVersion.V26_3);
            var runtime = ProtocolRuntime.create(data, OfflineCultTestBootstrap.catalog());
            var codec = new CodecFixture(runtime);
            codec.phase(ConnectionPhase.PLAY);
            int id = data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                    .id("minecraft:set_entity_data");
            var observed = new ObservedPacketValues(
                    ProtocolCodecs.decoder(),
                    ProtocolVersion.V26_3,
                    () -> player.user.getCultConnection().platform().registryNames(),
                    player.getWorldRegistries()::painting,
                    () -> new int[] {-64, 384});
            var bytes = Unpooled.buffer();
            try {
                Wire.writeVarInt(bytes, 42);
                bytes.writeByte(6);
                Wire.writeVarInt(bytes, 20);
                Wire.writeVarInt(bytes, EntityPose.CROUCHING.ordinal());
                bytes.writeByte(255);
                var decoded = (EntityMetadata) codec.read(PacketDirection.CLIENTBOUND, id, bytes);
                assertEquals(EntityPose.CROUCHING, decoded.packedItems().get(0).value());
                player.compensatedEntities.updateEntityMetadata(42, decoded.packedItems());
                assertEquals(1.5D, remote.actionBounds(position).maxY() - 64);
                remote.actionPose = EntityPose.STANDING;
                var observedDecoded = (EntityMetadata) observed.read(
                        bytes.duplicate(),
                        runtime.binding(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND, id)
                                .context());
                assertEquals(
                        EntityPose.CROUCHING,
                        observedDecoded.packedItems().get(0).value());
                player.compensatedEntities.updateEntityMetadata(42, observedDecoded.packedItems());
                assertEquals(1.5D, remote.actionBounds(position).maxY() - 64);
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    void receivedEquipmentKeepsWireSlotOrderAndPendingSaddleBeforeSpawn() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var data = ProtocolData.load(ProtocolVersion.V26_3);
            var codec = new CodecFixture(ProtocolRuntime.create(data, OfflineCultTestBootstrap.catalog()));
            codec.phase(ConnectionPhase.PLAY);
            int id = data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                    .id("minecraft:set_equipment");
            var bytes = Unpooled.buffer();
            try {
                Wire.writeVarInt(bytes, 42);
                bytes.writeByte(5 | 128); // HEAD is wire 5; component 5 is OFFHAND.
                writeDefaultItem(bytes, "minecraft:diamond_helmet");
                bytes.writeByte(7);
                writeDefaultItem(bytes, "minecraft:saddle");
                var packet = (InventoryPackets.Equipment) codec.read(PacketDirection.CLIENTBOUND, id, bytes);
                assertEquals(EquipmentSlot.HEAD, packet.slots().get(0).slot());
                assertEquals(EquipmentSlot.SADDLE, packet.slots().get(1).slot());
                var entities = fixture.player.compensatedEntities;
                entities.updateEntityEquipment(42, packet.slots());
                entities.addEntity(42, EntityTypeIds.HORSE, Vec3.ZERO, 0, 0, 0);
                assertTrue(((PacketEntityHorse) entities.getEntity(42)).hasSaddle);
            } finally {
                bytes.release();
            }
        }
    }

    private static void writeDefaultItem(io.netty.buffer.ByteBuf output, String key) {
        Wire.writeVarInt(output, 1);
        Wire.writeVarInt(
                output,
                ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults().id("minecraft:item", key));
        Wire.writeVarInt(output, 0);
        Wire.writeVarInt(output, 0);
    }
}
