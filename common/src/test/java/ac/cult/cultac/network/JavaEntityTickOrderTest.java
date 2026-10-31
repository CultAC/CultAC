package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaEntityTickOrderTest {
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void shulkerLidProjectionIsLimitedToMovementAndReplayReads(ClientVersion version) throws Exception {
        for (boolean shulkerFirst : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                var entities = f.player.compensatedEntities;
                entities.addEntity(100, EntityTypeIds.SHULKER, new Vec3(.5, 65, .5), 0, 0, 0);
                var shulker = (PacketEntityShulker) entities.getEntity(100);
                if (shulkerFirst) entities.appendSelfToClientTickOrder();
                shulker.onShapeData(Direction.WEST, (byte) 100);
                double before = shulker.getExactMovementCollisionBox().maxX;
                double projected;
                entities.beginMovementPositionReads();
                try {
                    projected = shulker.getExactMovementCollisionBox().maxX;
                } finally {
                    entities.endMovementPositionReads();
                }
                assertEquals(before, shulker.getExactMovementCollisionBox().maxX);

                shulker.onMovement(f.player);
                double after = shulker.getExactMovementCollisionBox().maxX;
                assertTrue(after > before);
                assertEquals(shulkerFirst ? after : before, projected);
                entities.beginMovementPositionReads();
                try {
                    assertEquals(projected, shulker.getExactMovementCollisionBox().maxX);
                } finally {
                    entities.endMovementPositionReads();
                }
                for (boolean afterTick : new boolean[] {false, true}) {
                    entities.setPassTickedThroughOrder(entities.clientTickOrder(shulker) - (afterTick ? 0 : 1));
                    try {
                        assertEquals(afterTick ? after : before, shulker.getExactMovementCollisionBox().maxX);
                    } finally {
                        entities.setPassTickedThroughOrder(Long.MIN_VALUE);
                    }
                    assertEquals(after, shulker.getExactMovementCollisionBox().maxX);
                }
            }
        }
    }

    /** EntityTickList preserves add order, including a replacement LocalPlayer added to a retained level. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void movementReadsOnlyTheInterpolationStepsBeforeItsPlace(ClientVersion version) throws Exception {
        for (boolean entityFirst : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                var entities = f.player.compensatedEntities;
                entities.addEntity(100, EntityTypeIds.ZOMBIE, new Vec3(0.5, 64, 0.5), 0, 0, 0);
                var entity = entities.getEntity(100);
                if (entityFirst) entities.appendSelfToClientTickOrder();
                entity.onBundledPositionSyncTransaction(3.5, 64, 0.5, f.player);
                double before = entity.getExactMovementCollisionBox().minX;
                entities.beginMovementPositionReads();
                double read;
                try {
                    read = entity.possibleMovementCollisionBoxCandidate(0, new SimpleCollisionBox()).minX;
                    assertEquals(
                            before,
                            entity.newPacketLocation.getExactMovementLocation().minX,
                            "a prediction must not advance the packet/interaction entity");
                } finally {
                    entities.endMovementPositionReads();
                }
                entity.onMovement(f.player);
                double after = entity.getExactMovementCollisionBox().minX;
                assertEquals(entityFirst ? after : before, read, 1.0E-12);
                entities.setPassTickedThroughOrder(entity.getClientTickOrder() - 1);
                try {
                    assertEquals(
                            before,
                            entity.getExactMovementCollisionBox().minX,
                            1.0E-12,
                            "an earlier pass reads the pre-interpolation box after a deferred tick");
                } finally {
                    entities.setPassTickedThroughOrder(Long.MIN_VALUE);
                }
                assertEquals(after, entity.getExactMovementCollisionBox().minX, 1.0E-12);
            }
        }
    }
}
