package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class JavaBoatPistonCollisionOrderTest {
    /**
     * PistonMovingBlockEntity#moveCollidedEntities moves the queried entities in section insertion order.
     * Retraction ejects the player from the source base before the boat's move clips against that player.
     */
    @Test
    void aRetractingPistonMovesThePlayerBeforeTheBoatCollidesWithIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 travel = new Vec3(1.88252910007536, FLOOR_Y, .07499998807907);
            f.place(travel, new Vec3(0, -.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var entities = f.player.compensatedEntities;
            entities.observeLocalPlayerPosition(travel);
            entities.addEntity(43, EntityTypeIds.OAK_BOAT, new Vec3(.6875, FLOOR_Y, .70505533801543), 0, 0, 0);
            var boat = entities.getEntity(43);
            var registry = DataTables.defaults().registry();
            int baseState = registry.with(registry.block("minecraft:piston").defaultState(), "facing", "west");
            baseState = registry.with(baseState, "extended", "true");
            int head = registry.with(registry.block("minecraft:piston_head").defaultState(), "facing", "west");
            BlockPos base = new BlockPos(2, FLOOR_Y, 0);
            f.player.compensatedWorld.updateBlock(base, baseState);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), head);
            f.player.compensatedWorld.pistons.handleBlockEvent(base, registry.blockIndex(baseState), 1, 4, 0);

            Vec3 pushed = new Vec3(1.6799999880791, FLOOR_Y, travel.z);
            var result = f.tick(pushed, false);
            assertFalse(result.hasEffectiveFlags(), JavaTickFixture.describe(result));
            assertEquals(pushed.x - .6F / 2 - 1.375 / 2, boat.clientPhysicalPosition.x, 1e-12);
        }
    }
}
