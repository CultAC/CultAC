package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaRiptideBubbleLiftTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    /** Standing in the column: travelInFluid's -0.005, then two submerged drag-down callbacks. */
    private static final double STANDING_Y = 0.0 - 0.08 / 16.0 - 0.03 - 0.03;

    /**
     * From 1.21.5 Entity#move records TridentItem#releaseUsing's grounded lift, and the travel's
     * Entity#applyEffectsFromBlocks replays it before the travel with one visited set. An upward push carries the
     * player out of the column. The travel alone visits the column's blocks 66 and 67, and from 1.21.9 also 65 in its
     * start box; the lift adds 65, or from 1.21.9 64. Before 1.21.5 the pass runs from Entity#oldPosition, which the
     * release already lifted.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void theTravelsBlockPassReplaysTheGroundedLift(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int y = FLOOR_Y; y <= FLOOR_Y + 3; y++)
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(0, y, 0),
                        BLOCKS.with(BLOCKS.block("minecraft:bubble_column").defaultState(), "drag", "true"));
            // Cult's xRot is the yaw and yRot the pitch: the release pushes straight up.
            f.player.xRot = 0;
            f.player.yRot = -90;
            f.place(START, new Vec3(0, STANDING_Y, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var standing = f.tick(START, true);
            assertTrue(exact(standing), describe(standing));

            f.player.packetStateData.riptideLevel = 3;
            f.player.packetStateData.riptideReleaseCertain = true;
            f.player.riptideSpinAttackTicks = 20;
            double travel = STANDING_Y + 3;
            Vec3 released = START.add(0, 1.1999999F + travel, 0);
            var spin = f.tick(released, false);
            assertTrue(exact(spin), describe(spin));

            int contacts = version.isOlderThan(ClientVersion.V_1_21_5)
                    ? 2
                    : version.isOlderThan(ClientVersion.V_1_21_9) ? 3 : 4;
            // travelInFluid's 0.8F drag and -0.005 gravity, then the pass's drag-down callbacks.
            Vec3 carried = new Vec3(0, travel * 0.8F - 0.08 / 16.0 - 0.03 * contacts, 0);
            var next = f.tick(released.add(carried), false);
            assertTrue(exact(next), describe(next));
        }
    }
}
