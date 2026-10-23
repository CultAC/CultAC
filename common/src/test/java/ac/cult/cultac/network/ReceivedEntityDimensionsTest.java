package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceivedEntityDimensionsTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void cubeMobSpawnsAtItsSynchedDefaultSizeAndResizesFromMetadata() {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = fixture.player;
            player.compensatedEntities.addEntity(42, EntityTypeIds.SLIME, new Vec3(0, 64, 0), 0, 0, 0);
            PacketEntity slime = player.compensatedEntities.getEntity(42);
            // AbstractCubeMob defines ID_SIZE as 1, which pairing never sends.
            assertBox(slime.getPossibleMovementCollisionBoxes(), 0, 64, 0, 0.52, 0.52, 1.0E-3);

            int sizeIndex =
                    WatchableIndexUtil.forModel(player.user.getCultConnection()).slimeSize();
            player.compensatedEntities.updateEntityMetadata(42, List.of(entry(sizeIndex, 4)));
            assertBox(slime.getPossibleMovementCollisionBoxes(), 0, 64, 0, 2.08, 2.08, 1.0E-3);
        }
    }

    @Test
    void zombiesAndPiglinsReadTheirOwnBabyFlags() {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = fixture.player;
            player.compensatedEntities.addEntity(42, EntityTypeIds.ZOMBIE, new Vec3(0, 64, 0), 0, 0, 0);
            player.compensatedEntities.updateEntityMetadata(42, List.of(entry(WatchableIndexUtil.ZOMBIE_BABY, true)));
            assertBox(
                    player.compensatedEntities.getEntity(42).getPossibleMovementCollisionBoxes(),
                    0,
                    64,
                    0,
                    0.49,
                    0.98,
                    1.0E-6);

            player.compensatedEntities.addEntity(43, EntityTypeIds.PIGLIN, new Vec3(0, 64, 0), 0, 0, 0);
            // Index 16 is AbstractPiglin's DATA_IMMUNE_TO_ZOMBIFICATION, not Piglin's baby flag.
            player.compensatedEntities.updateEntityMetadata(43, List.of(entry(16, true)));
            assertBox(
                    player.compensatedEntities.getEntity(43).getPossibleMovementCollisionBoxes(),
                    0,
                    64,
                    0,
                    0.6,
                    1.95,
                    1.0E-6);
            player.compensatedEntities.updateEntityMetadata(43, List.of(entry(WatchableIndexUtil.PIGLIN_BABY, true)));
            assertBox(
                    player.compensatedEntities.getEntity(43).getPossibleMovementCollisionBoxes(),
                    0,
                    64,
                    0,
                    0.49,
                    0.98,
                    1.0E-6);
        }
    }

    @Test
    void phantomSpawnsAtSizeZero() {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = fixture.player;
            player.compensatedEntities.addEntity(42, EntityTypeIds.PHANTOM, new Vec3(0, 64, 0), 0, 0, 0);
            // Phantom defines ID_SIZE as 0: the registered 0.9 x 0.5 box.
            assertBox(
                    player.compensatedEntities.getEntity(42).getPossibleMovementCollisionBoxes(),
                    0,
                    64,
                    0,
                    0.9,
                    0.5,
                    1.0E-6);
        }
    }

    @Test
    void babyMetadataResizesSteppedEntityWithoutCancellingInterpolation() {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            CultPlayer player = fixture.player;
            player.compensatedEntities.addEntity(42, EntityTypeIds.COW, new Vec3(0, 64, 0), 0, 0, 0);
            PacketEntity cow = player.compensatedEntities.getEntity(42);
            cow.onPositionPath(EntityPositionPath.linear(new Vec3(3, 64, 0)), true, null, null, player, false);
            cow.onSecondTransaction();
            cow.onMovement(player, true);
            cow.onMovement(player, true);
            double currentX = center(cow.getPossibleMovementCollisionBoxes());
            assertTrue(currentX > 0 && currentX < 3, "cow should be mid-interpolation: " + currentX);

            player.compensatedEntities.updateEntityMetadata(42, List.of(entry(WatchableIndexUtil.AGEABLE_BABY, true)));
            float width = BoundingBoxSize.getWidth(player, cow);
            float height = BoundingBoxSize.getHeight(player, cow);
            // Entity#refreshDimensions rebuilds the box at the current position.
            assertBox(cow.getPossibleMovementCollisionBoxes(), currentX, 64, 0, width, height, EPSILON);

            for (int tick = 0; tick < 10; tick++) cow.onMovement(player, true);
            assertBox(cow.getPossibleMovementCollisionBoxes(), 3, 64, 0, width, height, EPSILON);
        }
    }

    @Test
    void babyMetadataResizesLegacyInterpolationWithoutSnapping() {
        try (var fixture = RecordReceiveFixture.olderHost(ProtocolVersion.V1_21_11)) {
            CultPlayer player = fixture.player;
            player.compensatedEntities.addEntity(42, EntityTypeIds.COW, new Vec3(0, 64, 0), 0, 0, 0);
            PacketEntity cow = player.compensatedEntities.getEntity(42);
            cow.onFirstTransaction(false, true, 3, 64, 0, player);
            cow.onSecondTransaction();
            cow.onMovement(player, true);
            SimpleCollisionBox before = cow.getPossibleMovementCollisionBoxes();
            float adultWidth = BoundingBoxSize.getWidth(player, cow);
            float adultHeight = BoundingBoxSize.getHeight(player, cow);

            player.compensatedEntities.updateEntityMetadata(42, List.of(entry(WatchableIndexUtil.AGEABLE_BABY, true)));
            float width = BoundingBoxSize.getWidth(player, cow);
            float height = BoundingBoxSize.getHeight(player, cow);
            double halfDelta = (width - adultWidth) / 2.0D;
            SimpleCollisionBox after = cow.getPossibleMovementCollisionBoxes();
            // Each possible interpolation step keeps its position and takes the new size.
            assertEquals(before.minX - halfDelta, after.minX, EPSILON);
            assertEquals(before.maxX + halfDelta, after.maxX, EPSILON);
            assertEquals(before.minZ - halfDelta, after.minZ, EPSILON);
            assertEquals(before.maxZ + halfDelta, after.maxZ, EPSILON);
            assertEquals(before.minY, after.minY, EPSILON);
            assertEquals(before.maxY + (height - adultHeight), after.maxY, EPSILON);

            for (int tick = 0; tick < 3; tick++) cow.onMovement(player, true);
            assertBox(cow.getPossibleMovementCollisionBoxes(), 3, 64, 0, width, height, EPSILON);
        }
    }

    private static EntityMetadata.Entry entry(int index, Object value) {
        return new EntityMetadata.Entry(index, value, ByteBuffer.allocate(0));
    }

    private static double center(SimpleCollisionBox box) {
        return (box.minX + box.maxX) / 2.0D;
    }

    private static void assertBox(
            SimpleCollisionBox box, double x, double y, double z, double width, double height, double epsilon) {
        assertEquals(x, center(box), EPSILON);
        assertEquals(y, box.minY, EPSILON);
        assertEquals(z, (box.minZ + box.maxZ) / 2.0D, EPSILON);
        assertEquals(width, box.maxX - box.minX, epsilon);
        assertEquals(width, box.maxZ - box.minZ, epsilon);
        assertEquals(height, box.maxY - box.minY, epsilon);
    }
}
