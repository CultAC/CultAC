package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.FluidTypeFlowing;
import org.junit.jupiter.api.Test;

class ClientFluidQueriesTest {
    private static final BlockPos POS = new BlockPos(8, 64, 8);

    @Test
    void bodyDepthUsesTheCurrentFluidColumnWhileEyesCanReachItsSolidCeiling() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            world.updateBlock(
                    POS,
                    DataTables.defaults().registry().block("minecraft:water").defaultState());
            var body = new SimpleCollisionBox(8.2, 64, 8.2, 8.8, 65.8, 8.8, false);
            var feet = new Vec3(8.5, 64, 8.5);
            assertEquals((double) 64 + 8 / 9.0F - 64, ClientFluidQueries.depth(player, body, FluidTags.WATER), 0);
            assertFalse(ClientFluidQueries.eyesInside(player, body, feet, 64.95, FluidTags.WATER));
            world.updateBlock(
                    POS.above(),
                    DataTables.defaults().registry().block("minecraft:stone").defaultState());
            assertTrue(ClientFluidQueries.eyesInside(player, body, feet, 64.95, FluidTags.WATER));
            world.updateBlock(
                    POS.above(),
                    DataTables.defaults()
                            .registry()
                            .with(
                                    DataTables.defaults()
                                            .registry()
                                            .block("minecraft:water")
                                            .defaultState(),
                                    "level",
                                    "7"));
            assertEquals(1.0F, ClientFluidQueries.height(world, POS, ClientFluidQueries.fluidAt(world, POS)), 0);
            world.updateBlock(
                    POS.above(),
                    DataTables.defaults().registry().block("minecraft:lava").defaultState());
            assertEquals(8 / 9.0F, ClientFluidQueries.height(world, POS, ClientFluidQueries.fluidAt(world, POS)), 0);
        }
    }

    @Test
    void fallingCurrentUsesSturdyFacesAndKeepsIceOutOfTheDownwardPull() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            world.updateBlock(
                    POS,
                    DataTables.defaults()
                            .registry()
                            .with(
                                    DataTables.defaults()
                                            .registry()
                                            .block("minecraft:water")
                                            .defaultState(),
                                    "level",
                                    "8"));
            assertEquals(Vec3.ZERO, FluidTypeFlowing.flow(player, POS, ClientFluidQueries.fluidAt(world, POS)));
            world.updateBlock(
                    POS.north(),
                    DataTables.defaults().registry().block("minecraft:stone").defaultState());
            assertEquals(
                    new Vec3(0, -1, 0), FluidTypeFlowing.flow(player, POS, ClientFluidQueries.fluidAt(world, POS)));
            world.updateBlock(
                    POS.north(),
                    DataTables.defaults().registry().block("minecraft:ice").defaultState());
            assertEquals(Vec3.ZERO, FluidTypeFlowing.flow(player, POS, ClientFluidQueries.fluidAt(world, POS)));
            world.updateBlock(
                    POS.north().above(),
                    DataTables.defaults().registry().block("minecraft:stone").defaultState());
            assertEquals(
                    new Vec3(0, -1, 0), FluidTypeFlowing.flow(player, POS, ClientFluidQueries.fluidAt(world, POS)));
        }
    }

    @Test
    void anOpenLowerNeighborPullsFluidHorizontallyAndAnotherFluidDoesNot() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            world.updateBlock(
                    POS,
                    DataTables.defaults().registry().block("minecraft:water").defaultState());
            world.updateBlock(
                    POS.east().below(),
                    DataTables.defaults().registry().block("minecraft:water").defaultState());
            assertEquals(new Vec3(1, 0, 0), FluidTypeFlowing.flow(player, POS, ClientFluidQueries.fluidAt(world, POS)));
            world.updateBlock(
                    POS.east(),
                    DataTables.defaults().registry().block("minecraft:stone").defaultState());
            assertEquals(Vec3.ZERO, FluidTypeFlowing.flow(player, POS, ClientFluidQueries.fluidAt(world, POS)));
            world.updateBlock(
                    POS.east(),
                    DataTables.defaults().registry().block("minecraft:lava").defaultState());
            assertEquals(Vec3.ZERO, FluidTypeFlowing.flow(player, POS, ClientFluidQueries.fluidAt(world, POS)));
        }
    }
}
