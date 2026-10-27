package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.InputKeys;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class JavaPistonIdleCollisionTest {
    /** Travel hits the old head; only the later block-entity tick retracts that collider out of the way. */
    @Test
    void aNoPositionTickUsesThePistonColliderBeforeItsBlockEntityPass() throws Exception {
        try (var f = new JavaTickFixture()) {
            var blocks = DataTables.defaults().registry();
            int piston = blocks.with(
                    blocks.with(blocks.block("minecraft:piston").defaultState(), "facing", "west"), "extended", "true");
            int head = blocks.with(blocks.block("minecraft:piston_head").defaultState(), "facing", "west");
            BlockPos base = new BlockPos(2, FLOOR_Y + 1, 0);
            f.player.compensatedWorld.updateBlock(base, piston);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y + 1, 0), head);
            Vec3 position = new Vec3(1.0 - (double) (0.6F / 2), FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            f.tick(position, true);
            f.input(new ServerboundPlayerInput(false, false, true, false, false, false, false));
            f.statusTick(true, true);
            f.player.compensatedWorld.pistons.handleBlockEvent(base, blocks.blockIndex(piston), 1, 4, 0);
            var previous = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            f.tickEnd();

            var result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertNotSame(previous, result, "the tick without a movement packet must run prediction");
            assertFalse(result.hasFlag(InputKeys.class), result.getFlags().toString());
            assertEquals(0, f.player.checkManager.getListener(InputKeys.class).violations);
            assertEquals(position.x, f.player.x, 1.0E-12);

            // On the next tick the old head has retracted away: those held keys now must move the player.
            f.tickEnd();
            var ignoredInput = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(ignoredInput.hasEffectiveFlags(), "withheld movement must reach the normal checks");
            assertTrue(ignoredInput.getOffset() > 0.05, "exact walking input cannot stay within the report threshold");
            var move = (JavaMoveCollision) ignoredInput.getCollideAxisData();
            assertTrue(move.attempted().x > 0.09);
            assertFalse(move.collidedX(), "the retracted head cannot clip this input");
        }
    }
}
