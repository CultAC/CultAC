package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaLivingEntityPushes;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JavaRiderInterpolationRestartTest {
    private static final Vec3 PLAYER = new Vec3(.30000001192093, FLOOR_Y, .00568005179361);
    private static final Vec3 BOAT = new Vec3(1.29000001192094, FLOOR_Y, .70505533801543);
    private static final Vec3 OLD_ENDPOINT = new Vec3(.53000001192094, FLOOR_Y + .1875, .323640342099395);

    /**
     * AbstractInterpolationHandler#interpolateTo starts an inactive handler at Entity#storePositionAndRotation.
     * rideTick has reseated this passenger since its previous interpolation finished: that old endpoint is no
     * longer its physical position. A head can block the old path's carry while positionRider still seats it.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aFinishedPassengerPathRestartsAtItsCurrentSeat(boolean bundled) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(PLAYER, new Vec3(0, -.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var entities = f.player.compensatedEntities;
            entities.addEntity(43, EntityTypeIds.OAK_BOAT, BOAT, 17.532085F, 0, 0);
            var boat = entities.getEntity(43);
            entities.addEntity(44, EntityTypeIds.CHICKEN, new Vec3(6, FLOOR_Y, 6), 0, 0, 0);
            entities.getEntity(44).mount(boat);
            entities.addEntity(45, EntityTypeIds.PIG, OLD_ENDPOINT, 0, 0, 0);
            var pig = entities.getEntity(45);
            pig.mount(boat);
            Vec3 tracked = pig.clientPhysicalPosition;
            var trackedPath = pig.newPacketLocation;
            var registry = DataTables.defaults().registry();
            int head = registry.with(registry.block("minecraft:piston_head").defaultState(), "facing", "west");
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), head);

            pig.onPositionPath(EntityPositionPath.linear(Vec3.ZERO), false, 287F, 0F, f.player, true);
            // Sampling consumes the final queued step after its progress reaches the end.
            int lifetime = pig.riderInterpolationPath(false).remainingSteppedTicks() + 2;
            for (int tick = 0; tick < lifetime; tick++) {
                entities.tickClientEntities(entity -> entity.onMovement(f.player));
                f.player.packetStateData.acceptedClientTick++;
            }
            assertFalse(pig.hasRiderInterpolation());
            assertEquals(OLD_ENDPOINT.x, pig.riderInterpolationPath(false).targetCenterX(), 1e-12);
            SimpleCollisionBox seat = entities.untrackedRiderPacketBox(pig);
            double currentX = (seat.minX + seat.maxX) / 2;
            assertTrue(currentX - OLD_ENDPOINT.x > .5, "positionRider has left the completed path behind");

            // A rotation-only packet starts another positional interpolation from the physical seat too.
            pig.onPositionPath(EntityPositionPath.linear(Vec3.ZERO), false, 286F, 0F, f.player, bundled);
            if (!bundled) {
                pig.onUnbundledPathProven();
                pig.onSecondTransaction();
            }
            assertEquals(currentX, pig.riderInterpolationPath(false).targetCenterX(), 1e-12);
            var boxes = entities.untrackedRiderPushBoxes(
                    pig, f.player.boundingBox, new SimpleCollisionBox(), new SimpleCollisionBox());
            assertEquals(1, boxes.size());
            assertEquals(currentX, (boxes.getFirst().minX + boxes.getFirst().maxX) / 2, 1e-12);
            assertSame(tracked, pig.clientPhysicalPosition);
            assertSame(trackedPath, pig.newPacketLocation);

            // The expired endpoint touches the player; the current pig seat does not. Only the boat pushes it.
            Vec3 push = JavaLivingEntityPushes.impulse(BOAT, PLAYER);
            var contact = f.tick(PLAYER, true);
            assertTrue(exact(contact), describe(contact));
            var moved = f.tick(PLAYER.add(push), true);
            assertTrue(exact(moved), describe(moved));
        }
    }
}
