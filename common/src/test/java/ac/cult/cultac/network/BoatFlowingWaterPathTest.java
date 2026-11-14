package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AbstractBoat#tick moves an idle boat by its own buoyancy and the water's current alone, so a boat in a flowing or a
 * still channel has one accepted path: probing each tick for the accepted move closest to a target a block east and
 * one closest to a target a block west finds the same position.
 */
class BoatFlowingWaterPathTest {
    private static final int BOAT = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 0.1, 0.5);
    private static final int TICKS = 9;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anIdleBoatHasOneAcceptedPath(boolean flowing) throws Exception {
        List<Vec3> east = closestPath(flowing, 1.0);
        List<Vec3> west = closestPath(flowing, -1.0);
        for (int tick = 0; tick < TICKS; tick++) {
            double spread = east.get(tick).x - west.get(tick).x;
            assertTrue(Math.abs(spread) < 1.0E-6, "tick " + tick + " east-west spread " + spread);
        }
    }

    private static JavaTickFixture mount(boolean flowing) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        var registry = DataTables.defaults().registry();
        for (int x = -6; x <= 8; x++) {
            int level = Math.max(1, Math.min(7, x + 4));
            int water = flowing
                    ? registry.with(BlockIds.WATER.defaultState(), "level", String.valueOf(level))
                    : BlockIds.WATER.defaultState();
            for (int z = -6; z <= 6; z++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), water);
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        return f;
    }

    /** Each tick, the engine's accepted position closest to a target {@code side} blocks along x from the last one. */
    private static List<Vec3> closestPath(boolean flowing, double side) throws Exception {
        List<Vec3> path = new ArrayList<>();
        for (int tick = 0; tick < TICKS; tick++) {
            try (JavaTickFixture f = mount(flowing)) {
                for (Vec3 position : path) f.rideTick(position, false);
                Vec3 previous = path.isEmpty() ? SEAT : path.get(path.size() - 1);
                PredictionResult probe = f.rideTick(previous.add(side, 0, 0), false);
                path.add(probe.getSimulationContext().getStart().add(probe.getAcceptedClosestToTarget()));
            }
        }
        return path;
    }
}
