package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }

    private static void assertFlagged(PredictionResult result) {
        assertFalse(result.isExempt(), describe(result));
        assertTrue(result.hasEffectiveFlags(), describe(result));
    }
}
