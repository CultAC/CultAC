package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.SETTLED;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.SHALLOWS;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.charge;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.lifted;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.release;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.trident;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * TridentItem#releaseUsing for a riptide trident charged in shallow water and released on the ground. The release
 * spins at the released trident's riptide level once the use has run ten ticks.
 */
class JavaRiptideReleaseTest {
    private static final Vec3 RESTED = SHALLOWS.add(SETTLED);

    @ParameterizedTest
    @CsvSource({
        "V_1_21_11, 3, true",
        "V_1_21_11, 2, false",
        "V_1_21_11, 0, false",
        "V_26_1, 3, true",
        "V_26_1, 0, false",
        "V_26_2, 3, true",
        "V_26_2, 0, false",
        "V_26_3, 3, true",
        "V_26_3, 2, false",
        "V_26_3, 0, false"
    })
    void aReleaseAfterTenTicksSpinsAtTheTridentsLevel(ClientVersion version, int level, boolean exact)
            throws Exception {
        try (var f = fixture(version)) {
            charge(f, 10, Hand.MAIN_HAND, trident(3));
            release(f);
            assertRelease(f, level, exact);
        }
    }

    /** LivingEntity#releaseUsingItem reads the used hand, so an offhand use spins at the offhand trident's level. */
    @ParameterizedTest
    @CsvSource({
        "V_1_21_11, 2, true",
        "V_1_21_11, 0, false",
        "V_26_3, 2, true",
        "V_26_3, 3, false",
        "V_26_3, 0, false"
    })
    void anOffhandReleaseSpinsAtTheOffhandTridentsLevel(ClientVersion version, int level, boolean exact)
            throws Exception {
        try (var f = fixture(version)) {
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, trident(2));
            charge(f, 11, Hand.OFF_HAND, trident(3));
            release(f);
            assertRelease(f, level, exact);
        }
    }

    /** TridentItem#releaseUsing reads Player#hasInfiniteMaterials only for a thrown trident's pickup. */
    @ParameterizedTest
    @CsvSource({"V_1_21_11, 3, true", "V_1_21_11, 0, false", "V_26_3, 3, true", "V_26_3, 0, false"})
    void aCreativeReleaseSpins(ClientVersion version, int level, boolean exact) throws Exception {
        try (var f = fixture(version)) {
            f.player.gamemode = GameMode.CREATIVE;
            charge(f, trident(3));
            release(f);
            assertRelease(f, level, exact);
        }
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        return new JavaTickFixture(ProtocolVersion.V26_3, version);
    }

    /** Whether the release tick moves exactly by the spin at {@code level}, or resting in the shallows without one. */
    private static void assertRelease(JavaTickFixture f, int level, boolean exact) {
        PredictionResult result = level == 0 ? f.tick(RESTED, true) : f.tick(lifted(level), false);
        assertEquals(exact, exact(result), describe(result));
    }
}
