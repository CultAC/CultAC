package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Entity#move multiplies the movement by the block's stuck speed before Entity#collide, then zeroes the delta movement.
 * A setback's correction tick in a cobweb moves the player as slowly as the client's own tick would.
 */
class SetbackStuckSpeedTest {
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    // WebBlock#entityInside: makeStuckInBlock(state, new Vec3(0.25, 0.05F, 0.25)).
    private static final double COBWEB_Y = 0.05F;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @Test
    void aCorrectionTickInACobwebMovesAsSlowlyAsTheClient() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            int cobweb =
                    DataTables.defaults().registry().block("minecraft:cobweb").defaultState();
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), cobweb);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
            f.place(STANDING, GROUNDED);
            f.playerPosition(STANDING, Vec3.ZERO, Set.of());
            f.pongThrough(f.player.sendTrailingTransaction());
            f.input(NONE);
            f.tick(STANDING, true);
            f.tick(STANDING, true);

            // A knockback the client may not have taken yet, so the correction owes it.
            f.velocity(new Vec3(0, 0.5, 0));
            // A movement no tick reaches; the correction tick stands in for it.
            f.move(STANDING.add(0, 2, 0), false);
            f.tickEnd();

            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            SetBackData setback = teleports.getRequiredSetBack();
            assertNotNull(setback);
            assertTrue(teleports.isPendingSetback(), "the impossible movement was not set back");
            double rise = setback.getTeleportData().getLocation().y - STANDING.y;
            assertEquals(0.5 * COBWEB_Y, rise, 1.0E-9, "the correction tick ignored the cobweb's stuck speed");
        }
    }
}
