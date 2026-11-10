package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlimeSizeMetadataTest {
    /** From 1.20.5 AbstractCubeMob#getDefaultDimensions scales the type's 0.52 × 0.52 by the size. */
    @Test
    void aSizeUpdateGivesTheSlimeTheTypesDimensionsScaledBySize() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V1_21_11)) {
            var entities = f.player.compensatedEntities;
            entities.addEntity(4343, EntityTypeIds.SLIME, new Vec3(0.5, 64, 0.5), 0, 0, 0);
            entities.addEntity(4344, EntityTypeIds.MAGMA_CUBE, new Vec3(8.5, 64, 0.5), 0, 0, 0);
            size(entities, 4343, 10, ProtocolVersion.V1_21_11);
            size(entities, 4344, 3, ProtocolVersion.V1_21_11);
            assertBox(entities.getEntity(4343).getPossibleMovementCollisionBoxes(), 0.5, 64, 0.5, 0.52F * 10);
            assertBox(entities.getEntity(4344).getPossibleMovementCollisionBoxes(), 8.5, 64, 0.5, 0.52F * 3);
        }
    }

    /** Before 1.20.5 and on Bedrock the existing 2.04 by 0.255 per size formula stays; Bedrock is unverified. */
    @Test
    void olderAndBedrockClientsKeepTheExistingSlimeFormula() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V1_21_11)) {
            var entities = f.player.compensatedEntities;
            entities.addEntity(4343, EntityTypeIds.SLIME, new Vec3(0.5, 64, 0.5), 0, 0, 0);
            entities.addEntity(4344, EntityTypeIds.MAGMA_CUBE, new Vec3(8.5, 64, 0.5), 0, 0, 0);
            size(entities, 4343, 10, ProtocolVersion.V1_21_11);
            size(entities, 4344, 3, ProtocolVersion.V1_21_11);
            for (boolean bedrock : new boolean[] {false, true}) {
                ClientVersion version = bedrock ? ClientVersion.V_26_3 : ClientVersion.V_1_20_3;
                for (int id : new int[] {4343, 4344}) {
                    PacketEntity cube = entities.getEntity(id);
                    float legacy = 2.04F * (0.255F * (id == 4343 ? 10 : 3));
                    assertEquals(legacy, BoundingBoxSize.getWidth(cube, version, bedrock));
                    assertEquals(legacy, BoundingBoxSize.getHeight(cube, version, bedrock));
                }
            }
        }
    }

    /**
     * SulfurCube inherits AbstractCubeMob#getDefaultDimensions: the type's 0.49 × 0.49 scaled by ID_SIZE, with no age
     * scale for a baby.
     */
    @Test
    void aSulfurCubeTakesTheCubeMobDimensionsForItsSize() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var entities = f.player.compensatedEntities;
            int sulfurCube = EntityTypes.defaults().byKey("sulfur_cube").id();
            entities.addEntity(1076, sulfurCube, new Vec3(412.5, 67.0, -181.5), 0, 0, 0);
            entities.addEntity(1077, sulfurCube, new Vec3(420.5, 67.0, -181.5), 0, 0, 0);
            size(entities, 1076, 2, ProtocolVersion.V26_3);
            int sizeIndex = WatchableIndexUtil.forModel(ProtocolVersion.V26_3).slimeSize();
            entities.updateEntityMetadata(
                    1077,
                    List.of(
                            new EntityMetadata.Entry(WatchableIndexUtil.AGEABLE_BABY, true, ByteBuffer.allocate(0)),
                            new EntityMetadata.Entry(sizeIndex, 1, ByteBuffer.allocate(0))));
            assertBox(entities.getEntity(1076).getPossibleMovementCollisionBoxes(), 412.5, 67.0, -181.5, 0.49F * 2);
            assertBox(entities.getEntity(1077).getPossibleMovementCollisionBoxes(), 420.5, 67.0, -181.5, 0.49F);
        }
    }

    /** Entity#refreshDimensions resizes a seated slime around the feet its vehicle placed it at. */
    @Test
    void aSizeUpdateResizesASeatedSlimeAroundItsSeat() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var entities = f.player.compensatedEntities;
            entities.addEntity(4342, EntityTypeIds.OAK_BOAT, new Vec3(0.5, 64, 0.5), 0, 0, 0);
            entities.addEntity(4343, EntityTypeIds.SLIME, new Vec3(0.5, 64, 0.5), 0, 0, 0);
            PacketEntity boat = entities.getEntity(4342);
            PacketEntity slime = entities.getEntity(4343);
            slime.mount(boat);
            entities.positionPassenger(boat, slime);
            SimpleCollisionBox seated = slime.getPossibleMovementCollisionBoxes();

            size(entities, 4343, 4, ProtocolVersion.V26_3);
            assertBox(
                    slime.getPossibleMovementCollisionBoxes(),
                    (seated.minX + seated.maxX) / 2.0,
                    seated.minY,
                    (seated.minZ + seated.maxZ) / 2.0,
                    0.52F * 4);
        }
    }

    private static void size(CompensatedEntities entities, int id, int size, ProtocolVersion version) {
        int index = WatchableIndexUtil.forModel(version).slimeSize();
        entities.updateEntityMetadata(id, List.of(new EntityMetadata.Entry(index, size, ByteBuffer.allocate(0))));
    }

    private static void assertBox(SimpleCollisionBox box, double x, double y, double z, float size) {
        assertEquals(x - size / 2.0, box.minX, 1e-9);
        assertEquals(x + size / 2.0, box.maxX, 1e-9);
        assertEquals(z - size / 2.0, box.minZ, 1e-9);
        assertEquals(z + size / 2.0, box.maxZ, 1e-9);
        assertEquals(y, box.minY, 1e-9);
        assertEquals(y + size, box.maxY, 1e-9);
    }
}
