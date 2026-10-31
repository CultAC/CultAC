package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.JavaPassengerFluids;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Entity's pre-26.1 fluid updater tests received tags before EmptyFluid's zero height and flow. */
class JavaPassengerFluidsVersionsTest {
    private static final Vec3 FEET = new Vec3(8.5, 64.25, 8.5);

    @ParameterizedTest(name = "{0}: tagged empty cells supply legacy passenger contact and depth")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void taggedEmptyCellsHaveLegacyContactButNoCurrent(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            tags(f, List.of("minecraft:empty"), List.of("minecraft:empty"));
            var sample = JavaPassengerFluids.sample(f.player, body(), FEET, Vec3.ZERO, null);
            boolean legacy = version.isOlderThan(ClientVersion.V_26_1);
            assertEquals(legacy, sample.water());
            assertEquals(legacy, sample.lava());
            double depth = legacy ? 66.0 - (FEET.y + .001D) : 0;
            assertEquals(depth, sample.waterHeight(), 0);
            assertEquals(depth, sample.lavaHeight(), 0);
            assertEquals(Vec3.ZERO, sample.velocity());
            assertEquals(0, sample.eye());
        }
    }

    @ParameterizedTest(name = "{0}: tagged empty cells count in the legacy passenger current average")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void emptyCellsDiluteTheLegacyCurrentAlongsideFlowingWater(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            tags(f, List.of("minecraft:empty", "minecraft:water", "minecraft:flowing_water"), List.of());
            var water = new BlockPos(8, 64, 8);
            f.player.compensatedWorld.updateBlock(water, BlockIds.WATER.defaultState());
            f.player.compensatedWorld.updateBlock(water.east().below(), BlockIds.WATER.defaultState());
            var sample = JavaPassengerFluids.sample(f.player, body(), FEET, Vec3.ZERO, null);
            assertTrue(sample.water());
            // One flowing cell and two tagged empty cells; EmptyFluid#getFlow is ZERO in every release.
            double current = version.isOlderThan(ClientVersion.V_26_1) ? .014D / 3 : .014D;
            assertEquals(current, sample.velocity().x, 1.0E-15);
            assertEquals(0, sample.velocity().y, 0);
            assertEquals(0, sample.velocity().z, 0);
        }
    }

    private static SimpleCollisionBox body() {
        return new SimpleCollisionBox(8.2, FEET.y, 8.2, 8.8, FEET.y + 1.8, 8.8, false);
    }

    private static void tags(JavaTickFixture f, List<String> water, List<String> lava) {
        var names = ClientWorldDefaults.defaults().initialNames("minecraft:fluid");
        List<Integer> waterIds = water.stream().map(names::indexOf).toList();
        List<Integer> lavaIds = lava.stream().map(names::indexOf).toList();
        assertTrue(waterIds.stream().allMatch(id -> id >= 0));
        assertTrue(lavaIds.stream().allMatch(id -> id >= 0));
        if (f.player.registryState == null) f.player.registryState = new ClientComponentRegistries();
        f.player.registryState.appendTags(new RegistryTags(Map.of(
                "minecraft:fluid",
                new RegistryTags.Payload(Map.of("minecraft:water", waterIds, "minecraft:lava", lavaIds)))));
    }
}
