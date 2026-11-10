package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidTagOrder;
import ac.cult.cultac.utils.nmsutil.JavaLivingMoveFluids;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** EntityFluidInteraction's contact under a received tag payload that puts water in both WATER and LAVA. */
class JavaFluidTagContactVersionsTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /**
     * 26.3 tracks contact per fluid holder and asks Holder#is(tag) afterwards, so water touches both tags whichever
     * tag its current accumulator took first; Entity#updateFluidInteraction then resets the fall distance. 26.1 and
     * 26.2 keep one tracker per tag, so only the first matching tag is touched.
     */
    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("combinedFluidCases")
    void enteringWaterResetsTheFallDistanceFromHolderContact(
            String release, ClientVersion version, ClientFluidTagOrder order) throws Exception {
        try (var f = overlapping(version, order)) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.WATER.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 2, 0.5);
            f.place(position, new Vec3(0, -0.6, 0));
            f.input(NONE);
            position = position.add(0, -0.6, 0);
            var air = f.tick(position, false);
            assertTrue(exact(air), release + " " + describe(air));
            // LivingEntity#checkFallDamage refreshes the interaction after this move enters the water.
            position = position.add(0, (-0.6 - 0.08) * 0.98F, 0);
            var entering = f.tick(position, false);
            assertTrue(exact(entering), release + " " + describe(entering));
            boolean touchesWater =
                    version.isNewerThanOrEquals(ClientVersion.V_26_3) || order == ClientFluidTagOrder.WATER_FIRST;
            var carry = (JavaPredictionCarry)
                    f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
            if (touchesWater) assertEquals(0, carry.fallDistance(), 0, release + " " + order);
            else assertTrue(carry.fallDistance() > 1.2, release + " " + order + " " + carry.fallDistance());
        }
    }

    /**
     * The refreshed contact also gates the next Entity#move's callback: once 26.3 water touches WATER, a later move
     * in the same tick does not refresh and push again.
     */
    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("combinedFluidCases")
    void theRefreshedContactIsTheTagsAndNotTheCurrentsSelection(
            String release, ClientVersion version, ClientFluidTagOrder order) throws Exception {
        try (var f = overlapping(version, order)) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.WATER.defaultState());
            var self = f.player.compensatedEntities.getSelf();
            var box = new SimpleCollisionBox(0.2, FLOOR_Y + 0.5, 0.2, 0.8, FLOOR_Y + 2.3, 0.8);
            JavaLivingMoveFluids.Result result;
            try (var ignored = ClientFluidTagOrder.use(f.player, order)) {
                result = JavaLivingMoveFluids.apply(f.player, version, self, box, Vec3.ZERO, false, false);
            }
            boolean holder = version.isNewerThanOrEquals(ClientVersion.V_26_3);
            assertEquals(holder || order == ClientFluidTagOrder.WATER_FIRST, result.inWater(), release);
            assertEquals(holder || order == ClientFluidTagOrder.LAVA_FIRST, result.inLava(), release);
            assertEquals(result.inWater(), result.resetFallDistance(), release);
            if (holder)
                assertFalse(
                        JavaLivingMoveFluids.apply(
                                        f.player, version, self, box, Vec3.ZERO, result.inWater(), result.inLava())
                                .refreshed(),
                        release);
        }
    }

    static Stream<Arguments> combinedFluidCases() {
        return JavaPistonReleaseCases.releases()
                .filter(args -> ((ClientVersion) args.get()[1]).isNewerThanOrEquals(ClientVersion.V_26_1))
                .flatMap(args -> Stream.of(ClientFluidTagOrder.values())
                        .map(order -> Arguments.of(args.get()[0], args.get()[1], order)));
    }

    private static JavaTickFixture overlapping(ClientVersion version, ClientFluidTagOrder order) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        var names = ClientWorldDefaults.defaults().initialNames("minecraft:fluid");
        var members = List.of(names.indexOf("minecraft:water"), names.indexOf("minecraft:flowing_water"));
        if (f.player.registryState == null) f.player.registryState = new ClientComponentRegistries();
        f.player.registryState.appendTags(new RegistryTags(Map.of(
                "minecraft:fluid",
                new RegistryTags.Payload(Map.of("minecraft:water", members, "minecraft:lava", members)))));
        // A proven JVM Set order, as JavaMovementEngine records it from an observed current.
        f.player.provenClientFluidTagOrder = order;
        return f;
    }
}
