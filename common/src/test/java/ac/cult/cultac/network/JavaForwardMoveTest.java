package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaForwardMove;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Entity#collide's ordinary movement and geometry-derived stepping use one forward resolution. */
class JavaForwardMoveTest {
    private static final double FALL = -0.0784000015258789;
    private static final Vec3 START = new Vec3(0.65, FLOOR_Y, 0.5);

    @ParameterizedTest
    @ValueSource(doubles = {1.0, 0.75, 0.05, 1.5})
    void aStepUsesTheActualSlabTopAndUnscaledStepAttribute(double stuckY) throws Exception {
        try (var f = new JavaTickFixture()) {
            slab(f);
            f.place(START, new Vec3(0.1, FALL, 0));
            Vec3 move = collide(f, new Vec3(0.1, FALL * stuckY, 0), 0.6);
            assertEquals(0.1, move.x, 1e-12);
            assertEquals(0.5, move.y, 1e-12);
        }
    }

    @Test
    void aStepCannotPassThroughACeiling() throws Exception {
        try (var f = new JavaTickFixture()) {
            slab(f);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y + 2, 0), BlockIds.STONE.defaultState());
            f.place(START, new Vec3(0.1, FALL, 0));
            Vec3 move = collide(f, new Vec3(0.1, FALL, 0), 0.6);
            assertEquals(1 - f.player.boundingBox.maxX, move.x, 1e-12);
            assertEquals(0, move.y, 1e-12);
        }
    }

    @Test
    void zeroStepHeightKeepsTheOrdinaryCollision() throws Exception {
        try (var f = new JavaTickFixture()) {
            slab(f);
            f.place(START, new Vec3(0.1, FALL, 0));
            Vec3 move = collide(f, new Vec3(0.1, FALL, 0), 0);
            assertEquals(1 - f.player.boundingBox.maxX, move.x, 1e-12);
            assertEquals(0, move.y, 1e-12);
        }
    }

    @Test
    void manyEntitiesKeepOneDeterministicCollisionResult() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int i = 0; i < 24; i++)
                f.player.compensatedEntities.addEntity(
                        100 + i, EntityTypeIds.OAK_BOAT, new Vec3(1.5, FLOOR_Y + 2, 0.5 + i * 0.01), 0, 0, 0);
            f.place(START, new Vec3(0, FALL, 0));
            var result = f.tick(START, true);
            assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertTrue(result.getCollideAxisData().getUnknown().isEmpty());
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
        }
    }

    @Test
    void aWithheldPositionStillResolvesTheActualMoveBeforeItsReportTolerance() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = START.add(0, 3, 0);
            f.player.compensatedEntities.getSelf().hasGravity = false;
            f.player.compensatedEntities.updateAttributes(
                    f.player.entityID, List.of(new AttributeSnapshot("minecraft:sneaking_speed", 0.0025, List.of())));
            f.place(start, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, true, false));
            f.tick(start, false);
            var keys = new ServerboundPlayerInput(true, false, false, false, false, true, false);
            f.input(keys);
            Vec3 step = ClientMoveInput.of(f, keys, 0.0025F, 0.02F, 0);
            f.tickEnd();
            var result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            JavaMoveCollision move = assertInstanceOf(JavaMoveCollision.class, result.getCollideAxisData());
            assertEquals(step.z, move.collided().z, 1e-12);
            assertFalse(move.verticalCollisionBelow());
            assertEquals(step.z, result.getAcceptedClosestToTarget().z, 1e-12);
            assertEquals(start.z + step.z, f.player.z, 1e-12);
        }
    }

    private static Vec3 collide(JavaTickFixture f, Vec3 delta, double stepHeight) {
        return JavaForwardMove.collide(
                f.player,
                f.player.boundingBox,
                delta,
                true,
                stepHeight,
                JavaCollisionState.of(f.player, f.player.compensatedEntities.getSelf(), 0),
                List.of());
    }

    private static void slab(JavaTickFixture f) {
        int state =
                DataTables.defaults().registry().block("minecraft:stone_slab").defaultState();
        f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), state);
    }
}
