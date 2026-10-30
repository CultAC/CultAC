package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.blocksim.engine.SimItemStack;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaStriderStateVersionsTest {
    private static final int STRIDER = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double BASE = (double) 0.175F;
    private static final double FALL = -0.0784000015258789D;

    /** NoAI leaves DATA_SUFFOCATING as received, even while travelRidden still moves the strider. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aNoAiStriderTravelsAtItsSynchronizedWarmSpeed(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            PacketEntityStrider strider = add(f, STRIDER, START);
            metadata(f, true, false);
            strider.hasSaddle = true;
            f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            f.setPassengers(STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            strider.onGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, FALL, 0));
            float speed = (float) (BASE * 0.55F);
            double distance = speed * (0.21600002F / (0.6F * 0.6F * 0.6F));
            // 1.21.5 alone clears ridden NoAI motion after tickRidden instead of calling travel.
            var result = f.rideTick(START.add(0, 0, version == ClientVersion.V_1_21_5 ? 0 : distance), true);
            assertTrue(exact(result), describe(result));
            if (version == ClientVersion.V_1_21_5)
                assertEquals(java.util.Set.of(Vec3.ZERO), f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            assertFalse(strider.isSuffocating());
            assertEquals(BASE, strider.movementSpeedAttribute);
        }
    }

    /** A retained cold bit still constrains speed; item switching never grants a warm-speed allowance. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aNoAiColdStriderRejectsWarmSpeed(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            PacketEntityStrider strider = add(f, STRIDER, START);
            metadata(f, true, true);
            strider.hasSaddle = true;
            f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            f.setPassengers(STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.packetStateData.acceptedClientTick += 10;
            strider.onGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, FALL, 0));
            float speed = (float) (BASE * 0.55F);
            double distance = speed * (0.21600002F / (0.6F * 0.6F * 0.6F));
            var result = f.rideTick(START.add(0, 0, distance), true);
            JavaTickFixture.assertRejected(result, "VehicleOffset");
            assertTrue(strider.isSuffocating());
        }
    }

    /** Metadata writes only the cold bit; NoAI toggles retain the last local flag and attribute independently. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void passiveTicksRetainThenRecomputeBothStateFields(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            PacketEntityStrider strider = add(f, STRIDER, START);
            metadata(f, true, true);
            next(f, strider);
            assertTrue(strider.isSuffocating());
            assertEquals(BASE, strider.movementSpeedAttribute);
            metadata(f, false, false);
            next(f, strider);
            assertTrue(strider.isSuffocating());
            double coldSpeed = BASE + BASE * (double) -0.34F;
            assertEquals(coldSpeed, strider.movementSpeedAttribute);
            metadata(f, true, false);
            next(f, strider);
            assertFalse(strider.isSuffocating());
            assertEquals(coldSpeed, strider.movementSpeedAttribute);
            // Attribute snapshots overwrite the modifier without rewriting DATA_SUFFOCATING.
            f.player.compensatedEntities.updateAttributes(STRIDER,
                    List.of(new AttributeSnapshot("minecraft:movement_speed", BASE, List.of())));
            assertFalse(strider.isSuffocating());
            assertEquals(BASE, strider.movementSpeedAttribute);
            metadata(f, false, false);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), BlockIds.LAVA.defaultState());
            next(f, strider);
            assertFalse(strider.isSuffocating());
            assertEquals(BASE, strider.movementSpeedAttribute);
        }
    }

    /** Strider#tick precedes its own fluid refresh, including during passive ticks with NoAI. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void warmthReadsTheLastFluidRefresh(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            PacketEntityStrider strider = add(f, STRIDER, START);
            metadata(f, true, false);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.LAVA.defaultState());
            next(f, strider);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.AIR.defaultState());
            metadata(f, false, false);
            next(f, strider);
            assertFalse(strider.isSuffocating(), "cached lava survives the block update");
            next(f, strider);
            assertTrue(strider.isSuffocating(), "the subsequent dry base tick replaces the cache");
        }
    }

    /** Before 26.1 a cold parent forces cold; from 26.1 a warm parent supplies warmth instead. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void stackWarmthFollowsTheReleaseSemantics(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            PacketEntityStrider parent = add(f, 101, START);
            parent.noAI = true;
            PacketEntityStrider child = add(f, STRIDER, START.add(0, 2, 0));
            child.mount(parent);
            next(f, child);
            assertEquals(version.isOlderThan(ClientVersion.V_26_1), child.isSuffocating());
            parent.onSuffocatingMetadata(true);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 2, 0), BlockIds.LAVA.defaultState());
            next(f, child);
            assertEquals(version.isOlderThan(ClientVersion.V_26_1), child.isSuffocating());
        }
    }

    /** Through 26.2 the old physical block is sampled; commonTick makes 26.3 sample after interpolation. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void interpolationAndWarmthRunInClientOrder(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            PacketEntityStrider strider = add(f, STRIDER, START);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, 0), BlockIds.LAVA.defaultState());
            strider.onFirstTransaction(false, true, 3.5, FLOOR_Y, 0.5, f.player);
            strider.onSecondTransaction();
            next(f, strider);
            assertEquals(version.isOlderThan(ClientVersion.V_26_3), strider.isSuffocating());
        }
    }

    /** addOrUpdateTransientModifier leaves an old operation bucket when the packet used the same id differently. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aForeignSuffocatingOperationSurvivesLocalReplacementAndLaterPackets(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            PacketEntityStrider strider = add(f, STRIDER, START);
            var foreign = new AttributeModifier("minecraft:suffocating", 0.1D, AttributeModifier.Operation.ADD_VALUE);
            f.player.compensatedEntities.updateAttributes(STRIDER,
                    List.of(new AttributeSnapshot("minecraft:movement_speed", BASE, List.of(foreign))));
            next(f, strider);
            assertEquals((BASE + 0.1D) + (BASE + 0.1D) * (double) -0.34F, strider.movementSpeedAttribute);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, 0), BlockIds.LAVA.defaultState());
            next(f, strider);
            assertFalse(strider.isSuffocating());
            assertEquals(BASE + 0.1D, strider.movementSpeedAttribute);
            f.player.compensatedEntities.updateAttributes(STRIDER,
                    List.of(new AttributeSnapshot("minecraft:movement_speed", BASE * 2, List.of())));
            assertEquals(BASE * 2 + 0.1D, strider.movementSpeedAttribute);
        }
    }

    /** The 26.1+ fluid surface addition is double, including the raw-holder cache introduced in 26.3. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void shallowLavaRetainsTheReleaseSpecificPositiveHeight(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            Vec3 at = new Vec3(0.5, FLOOR_Y + 0.886887D, 0.5);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.LAVA.defaultState());
            var body = GetBoundingBox.getBoundingBoxFromPosAndSize(at.x, at.y, at.z, 0.9F, 1.7F);
            double depth = ClientFluidHeights.capture(f.player, body).lavaHeight(f.player);
            double expected = version.isNewerThanOrEquals(ClientVersion.V_26_1)
                    ? (double) FLOOR_Y + (double) (8.0F / 9.0F) - at.y
                    : (double) (FLOOR_Y + 8.0F / 9.0F) - (at.y + 0.001D);
            assertEquals(expected, depth);
        }
    }

    /** A 26.1+ move callback can replace the base-tick lava cache after travel enters its first lava cell. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void selectedTravelCommitsItsReachedFluidRefresh(String release, ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            PacketEntityStrider strider = add(f, STRIDER, START);
            metadata(f, true, false);
            strider.hasSaddle = true;
            f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            f.setPassengers(STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            strider.onGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, FALL, 0));
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 1), BlockIds.LAVA.defaultState());
            float speed = (float) (BASE * 0.55F);
            double distance = speed * (0.21600002F / (0.6F * 0.6F * 0.6F));
            var result = f.rideTick(START.add(0, 0, version == ClientVersion.V_1_21_5 ? 0 : distance), true);
            assertTrue(exact(result), describe(result));
            // From 26.1 checkFallDamage refreshes lava during the entering move. Strider#floatStrider
            // consumes that refreshed cache after travel, so buoyancy already belongs to this tick's carry.
            assertFalse(result.getSimulationContext().getWorldData().isInLava());
            boolean entersLava = version.isNewerThanOrEquals(ClientVersion.V_26_1);
            assertEquals(entersLava, result.getJavaCommittedMove().touchingLava());
            double drag = (double) (0.6F * 0.91F);
            Vec3 expectedCarry = version == ClientVersion.V_1_21_5
                    ? Vec3.ZERO
                    : new Vec3(0, FALL, distance * drag);
            if (entersLava) expectedCarry = expectedCarry.scale(0.5D).add(0, 0.05D, 0);
            var carried = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertEquals(1, carried.size());
            Vec3 actualCarry = carried.iterator().next();
            assertEquals(expectedCarry.x, actualCarry.x, 1.0E-12);
            assertEquals(expectedCarry.y, actualCarry.y, 1.0E-12);
            assertEquals(expectedCarry.z, actualCarry.z, 1.0E-12);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 1), BlockIds.AIR.defaultState());
            f.player.getInventory().inventory.setHeldItem(SimItemStack.EMPTY);
            metadata(f, false, false);
            f.rideTick();
            assertEquals(version.isOlderThan(ClientVersion.V_26_1), strider.isSuffocating());
        }
    }

    /** flag-80: the entering move refreshed lava, but its buoyancy was missing from the following tick. */
    @Test
    void enteringFlowingLavaCarriesBuoyancyIntoTheReportedMove() throws Exception {
        try (var f = fixture(ClientVersion.V_26_3)) {
            // Translate the captured stone floor and falling-lava edge to the fixture's origin.
            Vec3 start = new Vec3(249.69379455520638 - 249, FLOOR_Y, -33.5473916986739 + 34);
            f.place(start, Vec3.ZERO);
            PacketEntityStrider strider = add(f, STRIDER, start);
            metadata(f, true, true);
            f.player.compensatedEntities.updateAttributes(STRIDER, List.of(new AttributeSnapshot(
                    "minecraft:movement_speed", BASE, List.of(new AttributeModifier(
                            "minecraft:suffocating", (double) -0.34F,
                            AttributeModifier.Operation.ADD_MULTIPLIED_BASE)))));
            strider.hasSaddle = true;
            f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:warped_fungus_on_a_stick", 1));
            f.setPassengers(STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            strider.onGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(
                    new Vec3(-0.009263867010203134, FALL, -0.04059946313201133));
            int fallingLava = DataTables.defaults().registry().with(BlockIds.LAVA.defaultState(), "level", "8");
            int flowingLava = DataTables.defaults().registry().with(BlockIds.LAVA.defaultState(), "level", "2");
            for (int x = -2; x <= 4; x++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 4; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, -1), fallingLava);
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, -3), fallingLava);
                }
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, -2), flowingLava);
            }
            f.player.xRot = 189.26958F;
            var entering = f.rideTick(new Vec3(249.6910405219138 - 249, FLOOR_Y, -33.62788856302265 + 34), true);
            assertTrue(exact(entering), describe(entering));
            assertFalse(entering.getSimulationContext().getWorldData().isInLava());
            assertTrue(entering.getJavaCommittedMove().touchingLava());

            f.player.xRot = 170.21957F;
            var next = f.rideTick(new Vec3(
                    249.68764206780045 - 249,
                    FLOOR_Y + 0.010799999237065094,
                    -33.67254369091616 + 34), false);
            assertTrue(exact(next), describe(next));
            assertEquals(0.0D, next.getOffset(), 1.0E-12, describe(next));
        }
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(START, Vec3.ZERO);
        return f;
    }

    private static PacketEntityStrider add(JavaTickFixture f, int id, Vec3 at) {
        f.player.compensatedEntities.addEntity(id, EntityTypeIds.STRIDER, at, 0, 0, 0);
        return (PacketEntityStrider) f.player.compensatedEntities.getEntity(id);
    }

    private static void metadata(JavaTickFixture f, boolean noAi, boolean cold) {
        var layout = WatchableIndexUtil.forModel(f.player.user.getCultConnection());
        f.player.compensatedEntities.updateEntityMetadata(STRIDER, List.of(
                new EntityMetadata.Entry(WatchableIndexUtil.MOB_FLAGS, (byte) (noAi ? 1 : 0), ByteBuffer.allocate(0)),
                new EntityMetadata.Entry(layout.striderBoostTime() + 1, cold, ByteBuffer.allocate(0))));
    }

    private static void next(JavaTickFixture f, PacketEntityStrider strider) {
        f.player.packetStateData.acceptedClientTick++;
        strider.onMovement(f.player);
    }
}
