package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LivingEntity#travelInFluid's drag on the deltaMovement carried into the next tick. travelInLava applies one drag,
 * chosen by isInShallowFluid(LAVA) after its Entity#move, and both fluid paths read isFalling and getEffectiveGravity
 * before that move.
 */
class JavaFluidDragVersionsTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final double GRAVITY = 0.08D;

    /** On the floor of a pool three blocks deep: Entity#move clips the fall, then only the deep drag applies. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aDeepLavaFloorCarriesOnlyTheDeepDrag(String release, ClientVersion version) throws Exception {
        try (var f = pool(version, BlockIds.LAVA.defaultState(), 3)) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -GRAVITY / 4, 0));
            f.input(NONE);
            var result = f.tick(position, true);
            assertTrue(exact(result), release + ": " + describe(result));
            assertCarried(f, release, new Vec3(0, 0.0 * 0.5 - GRAVITY / 4, 0));
        }
    }

    /** On the floor under a third of a block of flowing lava: only the shallow drag and its fluid falling apply. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aShallowLavaFloorCarriesOnlyTheShallowDrag(String release, ClientVersion version) throws Exception {
        int thin = DataTables.defaults().registry().with(BlockIds.LAVA.defaultState(), "level", "5");
        try (var f = pool(version, thin, 1)) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -GRAVITY / 4, 0));
            f.input(NONE);
            var result = f.tick(position, true);
            assertTrue(exact(result), release + ": " + describe(result));
            assertCarried(f, release, new Vec3(0, 0.0 * 0.8F - GRAVITY / 16 - GRAVITY / 4, 0));
        }
    }

    /**
     * Sinking from 0.29 to 0.49 below the surface. Through 1.21.11 travelInLava reads the height Entity#baseTick cached
     * at the start; from 26.1 LivingEntity#checkFallDamage refreshes it at the moved box.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aSinkingClientCarriesTheDepthItsVersionCaches(String release, ClientVersion version) throws Exception {
        try (var f = pool(version, BlockIds.LAVA.defaultState(), 3)) {
            Vec3 start = new Vec3(0.5, FLOOR_Y + 2.6, 0.5);
            double fall = -0.2;
            f.place(start, new Vec3(0, fall, 0));
            f.input(NONE);
            var result = f.tick(start.add(0, fall, 0), false);
            assertTrue(exact(result), release + ": " + describe(result));
            boolean refreshed = version.isNewerThanOrEquals(ClientVersion.V_26_1);
            double y = refreshed ? fall * 0.5 - GRAVITY / 4 : fall * 0.8F - GRAVITY / 16 - GRAVITY / 4;
            assertCarried(f, release, new Vec3(0, y, 0));
        }
    }

    /**
     * Rising through deep lava on jump: LivingEntity#jumpInLiquid's 0.04 halves to about -gravity/4, so the client
     * rises 0.04 each tick. The shallow drag would carry 0.007 more into the next jump.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aDeepLavaSwimmerCannotChainTheShallowDrag(String release, ClientVersion version) throws Exception {
        double jump = 0.04F;
        double shallowCarry = jump * 0.8F - GRAVITY / 16 - GRAVITY / 4;
        for (boolean cheat : List.of(false, true)) {
            try (var f = pool(version, BlockIds.LAVA.defaultState(), 6)) {
                Vec3 position = new Vec3(0.5, FLOOR_Y + 1, 0.5);
                f.place(position, Vec3.ZERO);
                f.input(JUMP);
                position = position.add(0, jump, 0);
                var first = f.tick(position, false);
                assertTrue(exact(first), release + " tick 0: " + describe(first));
                position = position.add(0, jump + (cheat ? shallowCarry : 0), 0);
                var second = f.tick(position, false);
                String where = release + (cheat ? " shallow chain: " : " tick 1: ") + describe(second);
                if (cheat) {
                    assertFalse(second.isExempt(), where);
                    assertTrue(second.getOffset() > 0.005, where);
                } else {
                    assertTrue(exact(second), where);
                }
            }
        }
    }

    /**
     * A slow-falling swimmer rising into a ceiling: Entity#move clips the rise to 0, but travelInFluid read isFalling
     * and getEffectiveGravity before it, so the full gravity's fluid falling applies without the -0.003 snap.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aSlowFallingSwimmerAtACeilingFallsAtFullGravity(String release, ClientVersion version) throws Exception {
        try (var f = pool(version, BlockIds.WATER.defaultState(), 3)) {
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y + 3, z), BlockIds.STONE.defaultState());
                }
            }
            settle(f);
            f.player.compensatedEntities.getSelf().addPotionEffect(MovementEffect.SLOW_FALLING, 0);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 1.125, 0.5);
            f.place(start, new Vec3(0, 0.1, 0));
            f.input(NONE);
            // Entity#collide stops the head at the ceiling's underside.
            double rise = FLOOR_Y + 3 - (start.y + (double) 1.8F);
            var result = f.tick(start.add(0, rise, 0), false);
            assertTrue(exact(result), release + ": " + describe(result));
            assertCarried(f, release, new Vec3(0, 0.0 * 0.8F - GRAVITY / 16, 0));
        }
    }

    /** A {@code depth}-block pool of {@code fluid} over the fixture's floor. */
    private static JavaTickFixture pool(ClientVersion version, int fluid, int depth) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y < FLOOR_Y + depth; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), fluid);
                }
            }
        }
        settle(f);
        return f;
    }

    /** Lets the block-change timing uncertainty expire at client tick boundaries. */
    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** The next tick starts from exactly {@code expected}, to rounding. */
    private static void assertCarried(JavaTickFixture f, String release, Vec3 expected) {
        Set<Vec3> carried = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
        String where = release + ": expected " + expected + ", carried " + carried;
        assertTrue(carried.size() == 1, where);
        assertTrue(carried.iterator().next().distanceTo(expected) < 1.0E-9, where);
    }
}
