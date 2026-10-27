package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 26.3 queries current tags on cached raw holders; older clients cache the tag/tracker result. */
class JavaFluidEyeRetagVersionsTest {
    private static final Vec3 AT = new Vec3(.5, 64, .5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest(name = "{0}: add WATER to cached {2}")
    @MethodSource("lavaCases")
    void addingWaterMembershipRetagsTheCachedRawHolderOnlyOn26_3(
            String release, ClientVersion version, String holder, int block) throws Exception {
        try (var f = setup(version, block)) {
            waterTags(f, List.of());
            assertFalse(tick(f).getSimulationContext().getWorldData().isEyeInWater());
            waterTags(f, List.of(holder));
            var next = tick(f).getSimulationContext().getWorldData();
            assertEquals(
                    version.isNewerThanOrEquals(ClientVersion.V_26_3),
                    next.isUnderwater(),
                    "Player.tick queries the preceding eye contact before baseTick refreshes it");
            assertTrue(next.isEyeInWater(), "baseTick now sees the received WATER membership");
            assertTrue(tick(f).getSimulationContext().getWorldData().isUnderwater());
        }
    }

    @ParameterizedTest(name = "{0}: remove WATER from cached water")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void removingMembershipDoesNotRewriteOlderCachedTagResults(String release, ClientVersion version) throws Exception {
        try (var f = setup(version, BlockIds.WATER.defaultState())) {
            waterTags(f, List.of("minecraft:water", "minecraft:flowing_water"));
            assertTrue(tick(f).getSimulationContext().getWorldData().isEyeInWater());
            waterTags(f, List.of());
            var next = tick(f).getSimulationContext().getWorldData();
            assertEquals(version.isOlderThan(ClientVersion.V_26_3), next.isUnderwater());
            assertFalse(next.isEyeInWater(), "the new geometric refresh stores the new tag result");
            assertFalse(tick(f).getSimulationContext().getWorldData().isUnderwater());
        }
    }

    @ParameterizedTest(name = "{0}: cached holder survives a block replacement until refresh")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void changingGeometryCannotSubstituteForTheCachedContact(String release, ClientVersion version) throws Exception {
        try (var f = setup(version, BlockIds.LAVA.defaultState())) {
            waterTags(f, List.of());
            assertFalse(tick(f).getSimulationContext().getWorldData().isEyeInWater());
            f.player.compensatedWorld.updateBlock(new BlockPos(0, 64, 0), BlockIds.AIR.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(0, 65, 0), BlockIds.AIR.defaultState());
            waterTags(f, List.of("minecraft:lava"));
            var next = tick(f).getSimulationContext().getWorldData();
            assertEquals(version.isNewerThanOrEquals(ClientVersion.V_26_3), next.isUnderwater());
            assertFalse(next.isEyeInWater(), "the cached pre-baseTick query and the fresh geometry disagree here");
        }
    }

    @ParameterizedTest(name = "{0}: a fresh LocalPlayer has no cached raw holder")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void respawnClearsRawContactBeforeAReceivedTagChange(String release, ClientVersion version) throws Exception {
        try (var f = setup(version, BlockIds.LAVA.defaultState())) {
            waterTags(f, List.of());
            tick(f);
            f.player.checkManager.getSimulationProcessor().handleRespawn();
            waterTags(f, List.of("minecraft:lava"));
            var next = tick(f).getSimulationContext().getWorldData();
            assertFalse(next.isUnderwater());
            assertTrue(next.isEyeInWater());
        }
    }

    @ParameterizedTest(name = "{0}: swimming entry queries received WATER membership")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void swimmingEntryUsesTheCachedEyeAndReceivedFeetTag(String release, ClientVersion version) throws Exception {
        try (var f = setup(version, BlockIds.LAVA.defaultState())) {
            waterTags(f, List.of());
            tick(f);
            waterTags(f, List.of("minecraft:lava"));
            // Compensated self metadata writes the pre-aiStep flag; this tick's aiStep stops sprinting.
            f.player.sprintState.onServerFlag(true);
            var world = tick(f).getSimulationContext().getWorldData();
            boolean swimming = version.isNewerThanOrEquals(ClientVersion.V_26_3);
            assertEquals(swimming, world.isSwimming(), "Entity.updateSwimming uses the received feet tag");
            assertEquals(
                    swimming,
                    f.player.packetStateData.swimmingPossible,
                    "flight-toggle tracking must query the preceding raw cache against the current tags");
        }
    }

    @ParameterizedTest(name = "{0}: removing the feet tag prevents swimming entry")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void cachedWetEyesCannotReplaceTheFeetMembershipTest(String release, ClientVersion version) throws Exception {
        try (var f = setup(version, BlockIds.WATER.defaultState())) {
            tick(f);
            waterTags(f, List.of());
            f.player.sprintState.onServerFlag(true);
            var world = tick(f).getSimulationContext().getWorldData();
            assertFalse(world.isSwimming(), "the physical water holder is no longer in the received WATER tag");
            assertFalse(f.player.packetStateData.swimmingPossible);
        }
    }

    @ParameterizedTest(name = "{0}: swimming entry does not require current body water")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void cachedWetEyesAndTaggedFeetCanStartSwimmingWithoutBodyContact(String release, ClientVersion version)
            throws Exception {
        try (var f = setup(version, BlockIds.WATER.defaultState())) {
            tick(f);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, 64, 0), BlockIds.AIR.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(0, 65, 0), BlockIds.AIR.defaultState());
            waterTags(f, List.of("minecraft:empty", "minecraft:water", "minecraft:flowing_water"));
            f.player.sprintState.onServerFlag(true);
            var world = tick(f).getSimulationContext().getWorldData();
            assertTrue(world.isUnderwater(), "the previous eye contact survives until this tick's refresh");
            assertTrue(world.isSwimming(), "the entry branch tests feet membership, without an isInWater gate");
            assertTrue(f.player.packetStateData.swimmingPossible);
        }
    }

    @ParameterizedTest(name = "{0}: an upward piston caches raw {2} contact")
    @MethodSource("lavaCases")
    void aLatePistonCallbackKeepsTheExactUntaggedHolderUntilRefresh(
            String release, ClientVersion version, String holder, int fluid) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var blocks = DataTables.defaults().registry();
            var base = new BlockPos(8, 71, 8);
            var contact = new BlockPos(8, 74, 8);
            int piston = blocks.with(BlockIds.PISTON.defaultState(), "facing", "up");
            f.player.compensatedWorld.updateBlock(base, piston);
            f.player.compensatedWorld.updateBlock(contact, fluid);
            for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            waterTags(f, List.of());
            Vec3 start = new Vec3(8.5, 72, 8.5);
            f.place(start, Vec3.ZERO);
            f.input(NONE);
            f.player.compensatedWorld.pistons.handleBlockEvent(base, blocks.blockIndex(piston), 0, 1, 0);
            Vec3 report = version.isNewerThanOrEquals(ClientVersion.V_26_3) ? start.add(0, .51, 0) : start;
            var moved = f.tick(report, false);
            assertTrue(exact(moved), describe(moved));
            assertEquals(72.51, f.player.y, 1e-12, "the piston actually moved the standing eye into the fluid");
            assertFalse(moved.getSimulationContext().getWorldData().isEyeInWater());
            var simulation = f.player.checkManager.getSimulationProcessor();
            assertFalse(simulation.isLastWorldEyeInWater());
            f.player.compensatedWorld.updateBlock(contact, BlockIds.AIR.defaultState());
            String other = holder.equals("minecraft:lava") ? "minecraft:flowing_lava" : "minecraft:lava";
            waterTags(f, List.of(other));
            assertFalse(simulation.isLastWorldEyeInWater(), "source and flowing holders must remain distinct");
            waterTags(f, List.of(holder));
            assertEquals(
                    version.isNewerThanOrEquals(ClientVersion.V_26_3),
                    simulation.isLastWorldEyeInWater(),
                    "retagging queries the late callback's cached holder, without rescanning the cleared geometry");
        }
    }

    private static Stream<Arguments> lavaCases() {
        var blocks = DataTables.defaults().registry();
        int source = BlockIds.LAVA.defaultState();
        int flowing = blocks.with(source, "level", "1");
        return JavaPistonReleaseCases.releases()
                .flatMap(v -> Stream.of(
                        Arguments.of(v.get()[0], v.get()[1], "minecraft:lava", source),
                        Arguments.of(v.get()[0], v.get()[1], "minecraft:flowing_lava", flowing)));
    }

    private static JavaTickFixture setup(ClientVersion version, int fluid) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.compensatedWorld.updateBlock(new BlockPos(0, 64, 0), fluid);
        f.player.compensatedWorld.updateBlock(new BlockPos(0, 65, 0), fluid);
        for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(AT, Vec3.ZERO);
        f.input(NONE);
        return f;
    }

    private static ac.cult.cultac.checks.impl.prediction.PredictionResult tick(JavaTickFixture f) {
        // These stationary eye/tag cases have a floor, but Entity#move with zero Y is not grounded.
        // LivingEntity's .003 cutoff precedes the move; a fresh/respawned player's first zero-delta tick
        // clears onGround, and the following gravity-driven downward move lands again.
        boolean grounded = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels().stream()
                .anyMatch(velocity -> velocity.y <= -0.003D);
        var result = f.tick(AT, grounded);
        assertTrue(exact(result), describe(result));
        return result;
    }

    private static void waterTags(JavaTickFixture f, List<String> members) {
        var names = ClientWorldDefaults.defaults().initialNames("minecraft:fluid");
        var ids = members.stream().map(names::indexOf).toList();
        assertTrue(ids.stream().allMatch(id -> id >= 0));
        if (f.player.registryState == null) f.player.registryState = new ClientComponentRegistries();
        f.player.registryState.appendTags(new RegistryTags(Map.of(
                "minecraft:fluid",
                new RegistryTags.Payload(Map.of("minecraft:water", ids, "minecraft:lava", List.of())))));
    }
}
