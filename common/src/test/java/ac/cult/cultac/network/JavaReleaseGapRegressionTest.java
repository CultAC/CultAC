package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.environment.ClientWorldDefaults;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.ClientFluidTagOrder;
import ac.cult.cultac.utils.nmsutil.FluidCurrent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Release boundaries found by the all-version source audit; each assertion observes a native movement invariant. */
class JavaReleaseGapRegressionTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /** The live horse-tag sequence exposed a death-animation tick pushing after LocalPlayer#tick. */
    @ParameterizedTest(name = "{0}: a dying entity's own tick still pushes the living player")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void dyingEntityStillWritesTheNextPlayerVelocity(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            Vec3 at = new Vec3(.5, 64, .5);
            f.place(at, new Vec3(0, -.0784000015258789, 0));
            f.input(NONE);
            var entities = f.player.compensatedEntities;
            entities.addEntity(100, EntityTypeIds.HORSE, at.add(.8, 0, 0), 0, 0, 0);
            var horse = entities.getEntity(100);
            horse.isDead = true;
            var first = f.tick(at, true);
            assertTrue(exact(first), describe(first));
            Vec3 push = ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaLivingEntityPushes.impulse(
                    horse.getExactMovementCollisionBox(), at);
            assertTrue(push.x < -.04);
            var carry = (JavaPredictionCarry)
                    f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
            assertTrue(carry.states().stream().anyMatch(state -> Math.abs(state.velocity().x - push.x) <= 1e-13));
            var next = f.tick(at.add(push.x, 0, 0), true);
            assertTrue(exact(next), describe(next));
            assertEquals(0, next.selectionOffset(), 1e-13, describe(next));
        }
    }

    @ParameterizedTest(name = "{0}: walking uses the receiving client's Mth table")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void walkingAtATableBoundaryIsAnExactForwardMove(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            float yaw = -222.995F, radians = yaw * ((float) Math.PI / 180F);
            var mathVersion = version.isOlderThan(ClientVersion.V_1_21_11)
                    ? ac.cult.blocksim.engine.BlockSimClientVersion.V1_21_4
                    : ac.cult.blocksim.engine.BlockSimClientVersion.V1_21_11;
            float sin = ac.cult.blocksim.engine.VanillaMath.sin(mathVersion, radians);
            float cos = ac.cult.blocksim.engine.VanillaMath.cos(mathVersion, radians);
            Vec3 start = new Vec3(.5, 64, .5);
            f.player.xRot = yaw;
            f.place(start, new Vec3(0, -.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            double speed = (double) .98F * .1F;
            var result = f.tick(start.add(-sin * speed, 0, cos * speed), true);
            assertTrue(exact(result), describe(result));
            assertEquals(0, result.selectionOffset(), 1e-13, describe(result));
        }
    }

    @ParameterizedTest(name = "{0}: scaled opening query is distinct from the physical shell")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void scaledShulkerOpeningUsesTheOldTranslationOnlyBefore1214(String release, ClientVersion version)
            throws Exception {
        try (var f = fixture(version)) {
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.SHULKER, new Vec3(8.5, 70, 8.5), 0, 0, 0);
            var shulker = (PacketEntityShulker) f.player.compensatedEntities.getEntity(100);
            shulker.scale = 2;
            shulker.onShapeData(Direction.DOWN, (byte) 100);
            var peek = shulker.peekMove();
            assertNotNull(peek);
            double minimum = version.isOlderThan(ClientVersion.V_1_21_4) ? 8 : 7.5;
            assertEquals(minimum, peek.area().minX, 0);
            assertEquals(minimum + 2, peek.area().maxX, 0);
            assertEquals(minimum, peek.area().minZ, 0);
            assertEquals(minimum + 2, peek.area().maxZ, 0);
        }
    }

    @ParameterizedTest(name = "{0}: fall-reset ray reaches beyond eight only before 1.21.9")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void longTravelResetsFallDistanceFromTheActualReleaseRay(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            Vec3 start = new Vec3(.5, 72.1, .5), velocity = new Vec3(12, 0, 0);
            f.player.compensatedWorld.updateBlock(new BlockPos(10, 72, 0), BlockIds.WATER.defaultState());
            f.place(start, velocity);
            f.input(NONE);
            var processor = f.player.checkManager.getSimulationProcessor();
            JavaTickFixture.setField(
                    processor.getClass(),
                    processor,
                    "profileCarry",
                    new JavaPredictionCarry(f.player.compensatedEntities.getSelf(), 3));
            var result = f.tick(start.add(velocity), false);
            assertTrue(exact(result), describe(result));
            var carry = (JavaPredictionCarry) processor.getCurrentPredictionCarry();
            assertEquals(version.isOlderThan(ClientVersion.V_1_21_9) ? 0 : 3, carry.fallDistance(), 0);
        }
    }

    @ParameterizedTest(name = "{0}: received ridden-floating entity tag is authoritative")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void removingHorseFromReceivedFloatTagRemovesTheCarry(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            Vec3 at = new Vec3(.5, 64, .5);
            f.place(at, Vec3.ZERO);
            var context = f.tick(at, true).getSimulationContext();
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.HORSE, at, 0, 0, 0);
            JavaTickFixture.setField(
                    context.getClass(), context, "vehicle", f.player.compensatedEntities.getEntity(100));
            tags(f, "minecraft:entity_type", Map.of("minecraft:can_float_while_ridden", List.of()));
            assertFalse(ClientFluidQueries.floatWhileRidden(f.player, context, true, 1));
        }
    }

    @ParameterizedTest(name = "{0}: teleport velocity uses raw or clamped pitch at 1.21.5")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void teleportRotationKeepsItsPreClampVelocityAngle(String release, ClientVersion version) {
        int mask = RelativeFlag.ROTATE_DELTA.getMask()
                | RelativeFlag.DELTA_X.getMask()
                | RelativeFlag.DELTA_Y.getMask()
                | RelativeFlag.DELTA_Z.getMask();
        var teleport = new TeleportData(Vec3.ZERO, new RelativeFlag(mask), Vec3.ZERO, 1, 1, 0, 0, 0, 180);
        teleport.configureJavaRotation(version, 180);
        Vec3 velocity = teleport.applyToVelocity(new Vec3(0, 0, 1));
        boolean old = version.isOlderThan(ClientVersion.V_1_21_5);
        assertEquals(0, velocity.x, 1e-13);
        assertEquals(old ? 0 : -1, velocity.y, 1e-13);
        assertEquals(old ? -1 : 0, velocity.z, 1e-13);
        assertEquals(90, teleport.getFinalPitch(), 0);
        teleport.configureJavaRotation(version, 375);
        assertEquals(old ? 15 : 90, teleport.getFinalPitch(), 0);
        assertEquals(
                teleport.applyToVelocity(new Vec3(0, 0, 1)),
                teleport.copyWithLocation(new Vec3(1, 2, 3)).applyToVelocity(new Vec3(0, 0, 1)));
    }

    @ParameterizedTest(name = "{0}: one source-valid fluid accumulator per overlapping cell")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void overlappingFluidTagsUseTheSelectedCompleteOrder(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            var names = ClientWorldDefaults.defaults().initialNames("minecraft:fluid");
            var members = List.of(names.indexOf("minecraft:water"), names.indexOf("minecraft:flowing_water"));
            tags(f, "minecraft:fluid", Map.of("minecraft:water", members, "minecraft:lava", members));
            var at = new BlockPos(8, 72, 8);
            f.player.compensatedWorld.updateBlock(at, BlockIds.WATER.defaultState());
            f.player.compensatedWorld.updateBlock(at.east().below(), BlockIds.WATER.defaultState());
            for (var order : ClientFluidTagOrder.values())
                try (var ignored = ClientFluidTagOrder.use(f.player, order)) {
                    var water = new FluidCurrent();
                    var lava = new FluidCurrent();
                    var box = new SimpleCollisionBox(8.2, 72, 8.2, 8.8, 73.8, 8.8);
                    boolean w = water.compute(f.player, box, true, .1, 0, FluidTags.WATER, .014);
                    boolean l = lava.compute(f.player, box, true, .1, 0, FluidTags.LAVA, .0023333333333333335);
                    boolean combined = version.isNewerThanOrEquals(ClientVersion.V_26_1);
                    assertEquals(!combined || order == ClientFluidTagOrder.WATER_FIRST, w);
                    assertEquals(!combined || order == ClientFluidTagOrder.LAVA_FIRST, l);
                    boolean holderContact = version.isNewerThanOrEquals(ClientVersion.V_26_3);
                    assertEquals(
                            !combined || holderContact || order == ClientFluidTagOrder.WATER_FIRST,
                            ClientFluidQueries.depth(f.player, box, FluidTags.WATER) > 0);
                    assertEquals(
                            !combined || holderContact || order == ClientFluidTagOrder.LAVA_FIRST,
                            ClientFluidQueries.depth(f.player, box, FluidTags.LAVA) > 0);
                }
        }
    }

    @ParameterizedTest(name = "{0}: {2} remains one exact state across travel and resets")
    @MethodSource("combinedFluidCases")
    void nativeFluidOrderIsSelectedBeforeWorldBuildingAndSurvivesTheCarry(
            String release, ClientVersion version, ClientFluidTagOrder order) throws Exception {
        try (var f = fixture(version)) {
            var names = ClientWorldDefaults.defaults().initialNames("minecraft:fluid");
            var members = List.of(names.indexOf("minecraft:water"), names.indexOf("minecraft:flowing_water"));
            tags(f, "minecraft:fluid", Map.of("minecraft:water", members, "minecraft:lava", members));
            var at = new BlockPos(8, 72, 8);
            f.player.compensatedWorld.updateBlock(at, BlockIds.WATER.defaultState());
            f.player.compensatedWorld.updateBlock(at.east().below(), BlockIds.WATER.defaultState());
            for (int i = 0; i < 3; i++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 start = new Vec3(8.5, 72, 8.5), velocity = new Vec3(.1, 0, 0);
            f.place(start, velocity);
            f.input(NONE);
            double current = order == ClientFluidTagOrder.WATER_FIRST ? .014 : .0023333333333333335;
            var result = f.tick(start.add(.1 + current, 0, 0), false);
            assertTrue(exact(result), describe(result));
            assertEquals(0, result.selectionOffset(), 1e-13, describe(result));
            assertEquals(order, result.getSimulationContext().getFluidTagOrder());
            assertEquals(order, f.player.provenClientFluidTagOrder);
            var processor = f.player.checkManager.getSimulationProcessor();
            var carry = (JavaPredictionCarry) processor.getCurrentPredictionCarry();
            assertFalse(carry.states().isEmpty());
            assertTrue(carry.states().stream().allMatch(state -> state.fluidTagOrder() == order));
            boolean both = version.isNewerThanOrEquals(ClientVersion.V_26_3);
            assertEquals(
                    both || order == ClientFluidTagOrder.WATER_FIRST,
                    result.getSimulationContext().getWorldData().isInWater());
            assertEquals(
                    both || order == ClientFluidTagOrder.LAVA_FIRST,
                    result.getSimulationContext().getWorldData().isInLava());
            var teleport = new TeleportData(Vec3.ZERO, new RelativeFlag(0), Vec3.ZERO, 1, 1);
            assertTrue(
                    carry.afterTeleport(teleport).states().stream().allMatch(state -> state.fluidTagOrder() == order));
            processor.handleRespawn();
            assertEquals(
                    order,
                    f.player.provenClientFluidTagOrder,
                    "A replacement entity does not change the client's JVM Set order");
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> combinedFluidCases() {
        return JavaPistonReleaseCases.releases()
                .filter(args -> ((ClientVersion) args.get()[1]).isNewerThanOrEquals(ClientVersion.V_26_1))
                .flatMap(args -> java.util.Arrays.stream(ClientFluidTagOrder.values())
                        .map(order ->
                                org.junit.jupiter.params.provider.Arguments.of(args.get()[0], args.get()[1], order)));
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        return f;
    }

    private static void tags(JavaTickFixture f, String registry, Map<String, List<Integer>> tags) {
        if (f.player.registryState == null) f.player.registryState = new ClientComponentRegistries();
        f.player.registryState.appendTags(new RegistryTags(Map.of(registry, new RegistryTags.Payload(tags))));
    }
}
