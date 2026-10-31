package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.FluidCurrent;
import ac.cult.cultac.utils.nmsutil.FluidTypeFlowing;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 26.1+ queries actual neighbor chunks; older ClientLevel#hasChunk always returns true. */
class JavaFluidCurrentLoadedVersionsTest {
    @ParameterizedTest(name = "{0}: only the combined interaction requires the dry neighbor chunk")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aLoadedWaterCellStillRequiresItsDryNeighborChunk(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            var water = new BlockPos(15, 64, 8);
            flowing(f, water, water.west().below(), new Vec3(-1, 0, 0));
            var current = new FluidCurrent();
            boolean oldClient = version.isOlderThan(ClientVersion.V_26_1);
            assertEquals(oldClient, current.compute(f.player, box(15.2, 8.2), true, 1, 1, FluidTags.WATER, .014D));
            assertEquals(oldClient, current.touching);
            if (oldClient) assertEquals(-.014D, current.x, 1e-13);
        }
    }

    @ParameterizedTest(name = "{0}: the modern expanded cell range fits before the next chunk")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aNeighborOutsideTheModernCellRangeDoesNotBlockFluid(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            var water = new BlockPos(14, 64, 8);
            flowing(f, water, water.east().below(), new Vec3(1, 0, 0));
            var current = new FluidCurrent();
            assertTrue(current.compute(f.player, box(14.2, 8.2), true, 1, 1, FluidTags.WATER, .014D));
            assertTrue(current.touching);
            assertEquals(.014D, current.x, 1e-13);
        }
    }

    @ParameterizedTest(name = "{0}: a negative-Z missing neighbor clears modern contact only")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void reusedContactIsClearedAndReturnsOnlyAfterTheNeighborLoads(String release, ClientVersion version)
            throws Exception {
        try (var f = fixture(version)) {
            var center = new BlockPos(8, 64, 8);
            flowing(f, center, center.east().below(), new Vec3(1, 0, 0));
            var edge = new BlockPos(8, 64, 0);
            flowing(f, edge, edge.south().below(), new Vec3(0, 0, 1));
            var current = new FluidCurrent();
            assertTrue(current.compute(f.player, box(8.2, 8.2), true, 1, 1, FluidTags.WATER, .014D));
            assertTrue(current.touching);
            boolean oldClient = version.isOlderThan(ClientVersion.V_26_1);
            assertEquals(oldClient, current.compute(f.player, box(8.2, .2), true, 1, 1, FluidTags.WATER, .014D));
            assertEquals(oldClient, current.touching);
            f.player.compensatedWorld.ensureValidationChunkLoaded(0, -1);
            assertTrue(current.compute(f.player, box(8.2, .2), true, 1, 1, FluidTags.WATER, .014D));
            assertTrue(current.touching);
            assertEquals(0, current.x, 0);
            assertEquals(.014D, current.z, 1e-13);
        }
    }

    @ParameterizedTest(name = "{0}: base-tick water and flow flags obey the loaded-neighbor gate")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void onlyTheCombinedBaseTickInteractionRequiresTheMissingNeighbor(String release, ClientVersion version)
            throws Exception {
        baseTick(version, 15, version.isOlderThan(ClientVersion.V_26_1));
    }

    @ParameterizedTest(name = "{0}: a neighbor outside the modern cell range does not clear base-tick flags")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void baseTickUsesTheReleasesOwnNeighborRange(String release, ClientVersion version) throws Exception {
        baseTick(version, 14, true);
    }

    @ParameterizedTest(name = "{0}: cached body depth is empty when the fluid update cannot read its neighbors")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void cachedDepthObeysTheSameLoadedNeighborhood(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            var water = new BlockPos(15, 64, 8);
            flowing(f, water, water.west().below(), new Vec3(-1, 0, 0));
            var body = box(15.2, 8.2);
            double depth = ClientFluidQueries.depth(f.player, body, FluidTags.WATER);
            if (version.isOlderThan(ClientVersion.V_26_1)) assertTrue(depth > 0);
            else assertEquals(0, depth, 0);
            f.player.compensatedWorld.ensureValidationChunkLoaded(1, 0);
            assertTrue(ClientFluidQueries.depth(f.player, body, FluidTags.WATER) > 0);
        }
    }

    @ParameterizedTest(name = "{0}: the independent old eye query differs from the guarded 26.1+ interaction")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void onlyTheCombinedInteractionGatesTheEyeOnNeighborChunks(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            var water = new BlockPos(15, 64, 8);
            flowing(f, water, water.west().below(), new Vec3(-1, 0, 0));
            // Swimming-pose body and eye are both physically in this loaded water cell.
            var body = new SimpleCollisionBox(15.2, 64, 8.2, 15.8, 64.6, 8.8);
            var feet = new Vec3(15.5, 64, 8.5);
            assertEquals(
                    version.isOlderThan(ClientVersion.V_26_1),
                    ClientFluidQueries.eyesInWater(f.player, body, feet, 64.4));
            f.player.compensatedWorld.ensureValidationChunkLoaded(1, 0);
            assertTrue(ClientFluidQueries.eyesInWater(f.player, body, feet, 64.4));
        }
    }

    private static void baseTick(ClientVersion version, int x, boolean expectedWater) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var world = f.player.compensatedWorld;
            world.removeChunkLater(1, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertFalse(world.isChunkLoaded(1, 0));
            var water = new BlockPos(x, 64, 8);
            world.updateBlock(water.below(), BlockIds.STONE.defaultState());
            world.updateBlock(water, BlockIds.WATER.defaultState());
            world.updateBlock(water.west().below(), BlockIds.WATER.defaultState());
            // This test isolates the loaded-neighbor rule after the collision-change window expires.
            for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff(0);
            var at = new Vec3(x + .5, 64, 8.5);
            f.place(at, new Vec3(0, -.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            // The loaded interaction adds its exact westward current before travel.
            var result = f.tick(at.add(expectedWater ? -.014D : 0, 0, 0), true);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
            var state = result.getSimulationContext().getWorldData();
            assertEquals(expectedWater, state.isInWater());
            assertEquals(expectedWater, state.isInFlowingLiquid());
        }
    }

    private static SimpleCollisionBox box(double x, double z) {
        return new SimpleCollisionBox(x, 64, z, x + .6, 65.8, z + .6);
    }

    private static RecordReceiveFixture fixture(ClientVersion version) throws Exception {
        var f = new RecordReceiveFixture(ProtocolVersion.V26_3);
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
        assertFalse(f.player.compensatedWorld.isChunkLoaded(1, 0));
        assertFalse(f.player.compensatedWorld.isChunkLoaded(0, -1));
        return f;
    }

    private static void flowing(RecordReceiveFixture f, BlockPos water, BlockPos lower, Vec3 expected) {
        f.player.compensatedWorld.updateBlock(water, BlockIds.WATER.defaultState());
        f.player.compensatedWorld.updateBlock(lower, BlockIds.WATER.defaultState());
        assertEquals(
                expected,
                FluidTypeFlowing.flow(f.player, water, ClientFluidQueries.fluidAt(f.player.compensatedWorld, water)));
    }
}
