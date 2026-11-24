package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * The fluid currents a claimed mod loader may leave out. NeoForge 21.10.31 through 21.11.x LivingEntity#checkFallDamage
 * refreshes water with pushing disabled; Forge 26.3 before 66.0.5 Entity#updateFluidInteraction pushes no entity.
 */
class JavaLoaderFluidCurrentTest {
    private static final double VX = 0.04D;
    private static final double DY = -0.4D;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 1.2, 0.5);
    private static final Vec3 ENTRY = START.add(VX, DY, 0);
    // Entity#updateFluidHeightAndDoFluidPushing adds Y and height as floats through 1.21.11.
    private static final double SURFACE = FLOOR_Y + 8 / 9.0F;
    private static final double SHALLOW_CURRENT = (SURFACE - (ENTRY.y + 0.001D)) * 0.014D;

    private static final int CAMERA = 4242;
    private static final double CURRENT = 0.014D;
    private static final Vec3 WADING = new Vec3(-0.5, FLOOR_Y, 0.5);
    private static final Vec3 WADING_VELOCITY = new Vec3(0.05, -0.005, 0.0);

    @ParameterizedTest
    @CsvSource({
        "V1_21_9,V_1_21_9,false",
        "V1_21_9,V_1_21_9,true",
        "V1_21_11,V_1_21_11,false",
        "V1_21_11,V_1_21_11,true"
    })
    void neoForgeWaterEntryCarriesTheVelocityWithAndWithoutTheCurrent(
            ProtocolVersion protocol, ClientVersion client, boolean contactOnly) throws Exception {
        try (var f = stream(protocol, client)) {
            brand(f, "neoforge");
            enterStream(f);
            assertExact(f.tick(afterEntry(contactOnly), false));
        }
    }

    @ParameterizedTest
    @CsvSource({"V1_21_9,V_1_21_9", "V1_21_11,V_1_21_11"})
    void anotherBrandKeepsTheEntryCurrent(ProtocolVersion protocol, ClientVersion client) throws Exception {
        try (var f = stream(protocol, client)) {
            brand(f, "fabric");
            enterStream(f);
            assertFlagged(f.tick(afterEntry(true), false));
        }
        try (var f = stream(protocol, client)) {
            brand(f, "fabric");
            enterStream(f);
            assertExact(f.tick(afterEntry(false), false));
        }
    }

    @Test
    void neoForgeOn26Dot3KeepsTheBaseTickCurrent() throws Exception {
        try (var f = channel()) {
            brand(f, "neoforge");
            f.place(WADING, WADING_VELOCITY);
            assertFlagged(f.tick(WADING.add(WADING_VELOCITY.x, 0.0, 0.0), true));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void neoForgeEntryStillChecksMovementNeitherCallbackLeaves(boolean contactOnly) throws Exception {
        try (var f = stream(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            brand(f, "neoforge");
            enterStream(f);
            assertFlagged(f.tick(afterEntry(contactOnly).add(0.02D, 0, 0), false));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forgeBaseTickMovesWithOrWithoutTheCurrent(boolean pushed) throws Exception {
        try (var f = channel()) {
            brand(f, "forge");
            f.place(WADING, WADING_VELOCITY);
            assertExact(f.tick(WADING.add(WADING_VELOCITY.x + (pushed ? CURRENT : 0.0), 0.0, 0.0), true));
        }
    }

    @Test
    void anotherBrandKeepsTheBaseTickCurrent() throws Exception {
        try (var f = channel()) {
            brand(f, "fabric");
            f.place(WADING, WADING_VELOCITY);
            assertFlagged(f.tick(WADING.add(WADING_VELOCITY.x, 0.0, 0.0), true));
        }
    }

    @Test
    void forgeStillChecksMovementNoCurrentLeaves() throws Exception {
        try (var f = channel()) {
            brand(f, "forge");
            f.place(WADING, WADING_VELOCITY);
            assertFlagged(f.tick(WADING.add(0.3, 0.0, 0.0), true));
        }
    }

    @Test
    void forgeEntersAndWadesWithoutAnyCurrent() throws Exception {
        try (var f = channel()) {
            brand(f, "forge");
            Vec3 start = new Vec3(-0.6, FLOOR_Y + 1.1, 0.5);
            Vec3 falling = new Vec3(0.1, -0.6, 0.0);
            Vec3 entered = start.add(falling);
            f.place(start, falling);
            assertExact(f.tick(entered, false));

            // The dry entry tick still uses air drag. Once wet, the landing and later moves use water drag.
            double dx = falling.x * 0.91F;
            Vec3 position = new Vec3(entered.x + dx, FLOOR_Y, entered.z);
            assertExact(f.tick(position, true));
            for (int tick = 0; tick < 3; tick++) {
                dx *= 0.8F;
                position = position.add(dx, 0.0, 0.0);
                assertExact(f.tick(position, true));
            }
        }
    }

    /** A client runs one build for its session: the first move only one build explains names it for every later one. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forgeKeepsTheBuildItsMovementShowed(boolean pushed) throws Exception {
        try (var f = channel()) {
            brand(f, "forge");
            f.place(WADING, Vec3.ZERO);
            double dx = 0.0;
            Vec3 position = WADING;
            for (int tick = 0; tick < 20; tick++) {
                dx = dx * 0.8F + (pushed ? CURRENT : 0.0);
                position = position.add(dx, 0.0, 0.0);
                assertExact(f.tick(position, true));
                assertEquals(!pushed, f.player.loaderFluids.known());
            }
            dx = dx * 0.8F + (pushed ? 0.0 : CURRENT);
            assertFlagged(f.tick(position.add(dx, 0.0, 0.0), true));
        }
    }

    /**
     * While another entity is the camera, LocalPlayer#sendPosition reports nothing: the current pushes the player
     * unseen, and the reports after the camera returns continue the pushed travel.
     */
    @Test
    void forgeDriftsInTheCurrentWhileAnotherEntityIsTheCamera() throws Exception {
        try (var f = channel()) {
            brand(f, "forge");
            f.player.nettyScheduler.removeScheduler();
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.place(WADING, Vec3.ZERO);
            SetbackFixtures.seedSafePosition(f.player, new SetbackPosWithVector(WADING, Vec3.ZERO));
            f.addRemotePlayer(CAMERA, new Vec3(3.5, FLOOR_Y, 3.5), GameMode.SURVIVAL);
            camera(f, CAMERA);
            double dx = 0.0;
            Vec3 position = WADING;
            for (int tick = 0; tick < 3; tick++) {
                f.tickEnd();
                dx = dx * 0.8F + CURRENT;
                position = position.add(dx, 0.0, 0.0);
                assertEquals(position.x, f.player.x, 1.0E-9, "camera tick " + tick);
                assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), "camera tick " + tick);
            }
            assertNull(f.player.loaderFluids.known());
            camera(f, f.player.entityID);
            for (int tick = 0; tick < 5; tick++) {
                dx = dx * 0.8F + CURRENT;
                position = position.add(dx, 0.0, 0.0);
                assertExact(f.tick(position, true));
                assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), "report " + tick);
            }
            assertEquals(false, f.player.loaderFluids.known());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aReportedForgeBuildIsTheOnlyOneSimulated(boolean withoutCurrent) throws Exception {
        try (var f = channel()) {
            brand(f, "forge");
            f.player.loaderFluids.know(withoutCurrent);
            f.place(WADING, WADING_VELOCITY);
            assertFlagged(f.tick(WADING.add(WADING_VELOCITY.x + (withoutCurrent ? CURRENT : 0.0), 0.0, 0.0), true));
        }
        try (var f = channel()) {
            brand(f, "forge");
            f.player.loaderFluids.know(withoutCurrent);
            f.place(WADING, WADING_VELOCITY);
            assertExact(f.tick(WADING.add(WADING_VELOCITY.x + (withoutCurrent ? 0.0 : CURRENT), 0.0, 0.0), true));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aReportedNeoForgeBuildCarriesOnlyItsOwnEntryVelocity(boolean contactOnly) throws Exception {
        try (var f = stream(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            brand(f, "neoforge");
            f.player.loaderFluids.know(contactOnly);
            enterStream(f);
            assertFlagged(f.tick(afterEntry(!contactOnly), false));
        }
        try (var f = stream(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            brand(f, "neoforge");
            f.player.loaderFluids.know(contactOnly);
            enterStream(f);
            assertExact(f.tick(afterEntry(contactOnly), false));
        }
    }

    private static void brand(JavaTickFixture f, String brand) {
        f.player.checkManager.getCheck(ClientBrand.class).restore(brand);
    }

    private static void enterStream(JavaTickFixture f) {
        f.place(START, new Vec3(VX, DY, 0));
        assertExact(f.tick(ENTRY, false));
    }

    /** The report after the entry tick: its air drag on the callback's deltaMovement, then baseTick's own current. */
    private static Vec3 afterEntry(boolean contactOnly) {
        double carried = (VX + (contactOnly ? 0 : SHALLOW_CURRENT)) * 0.91F;
        return ENTRY.add(carried + SHALLOW_CURRENT, (DY - 0.08D) * 0.98F, 0);
    }

    /** A water source whose only lower neighbour is east of it, under a falling player. */
    private static JavaTickFixture stream(ProtocolVersion protocol, ClientVersion client) throws Exception {
        var f = new JavaTickFixture(protocol, client);
        var source = BlockIds.WATER.defaultState();
        var east = DataTables.defaults().registry().with(source, "level", "1");
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), source);
        f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), east);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        return f;
    }

    /** A 26.3 channel of water falling one level per block towards +X. */
    private static JavaTickFixture channel() throws Exception {
        var f = new JavaTickFixture();
        var registry = DataTables.defaults().registry();
        for (int x = -2; x <= 4; x++) {
            int water = registry.with(BlockIds.WATER.defaultState(), "level", Integer.toString(x + 2));
            for (int z = -3; z <= 3; z++) f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), water);
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        return f;
    }

    /** ClientboundSetCameraPacket, handled by the client once both of its pings are answered. */
    private static void camera(JavaTickFixture f, int id) {
        f.player.cameraEntity.onSetCamera(id);
        f.player.sendTransaction();
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }

    private static void assertFlagged(PredictionResult result) {
        assertFalse(result.isExempt(), describe(result));
        assertTrue(result.hasEffectiveFlags(), describe(result));
    }
}
