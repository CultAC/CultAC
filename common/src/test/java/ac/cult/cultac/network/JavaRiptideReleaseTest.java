package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.SETTLED;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.SHALLOWS;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.charge;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.lifted;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.release;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.select;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.trident;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * TridentItem#releaseUsing for a riptide trident charged in shallow water and released on the ground. The release
 * spins at the released trident's riptide level once the use has run ten ticks.
 */
class JavaRiptideReleaseTest {
    private static final Vec3 RESTED = SHALLOWS.add(SETTLED);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_1", "V_26_2", "V_26_3"})
    void aReleaseAfterNineTicksDoesNotSpin(ClientVersion version) throws Exception {
        for (int level : new int[] {0, 3}) {
            try (var f = fixture(version)) {
                charge(f, 9, Hand.MAIN_HAND, trident(3));
                release(f);
                assertRelease(f, level, level == 0);
            }
        }
    }

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

    /** From 1.21.5 the release moves the use to the held trident, which ItemStack#nextDamageWillBreak keeps still. */
    @ParameterizedTest
    @CsvSource({"V_1_21_11, 0, true", "V_1_21_11, 1, false", "V_26_3, 0, true", "V_26_3, 1, false"})
    void aSwitchToATridentItsNextDamageBreaksDoesNotSpin(ClientVersion version, int level, boolean exact)
            throws Exception {
        try (var f = fixture(version)) {
            charge(f, trident(3), breaking(trident(1)));
            select(f, 1);
            release(f);
            assertRelease(f, level, exact);
        }
    }

    /** TridentItem#use refuses a trident its next damage breaks, so the client releases no use. */
    @ParameterizedTest
    @CsvSource({"V_1_21_11, 0, true", "V_1_21_11, 3, false", "V_26_3, 0, true", "V_26_3, 3, false"})
    void aUseOfATridentItsNextDamageBreaksDoesNotStart(ClientVersion version, int level, boolean exact)
            throws Exception {
        try (var f = fixture(version)) {
            charge(f, breaking(trident(3)), trident(3));
            select(f, 1);
            release(f);
            assertRelease(f, level, exact);
        }
    }

    @ParameterizedTest
    @CsvSource({"V_1_21_11, 0, true", "V_1_21_11, 3, false", "V_26_3, 0, true", "V_26_3, 3, false"})
    void aTridentDamagedDuringTheUseDoesNotSpin(ClientVersion version, int level, boolean exact) throws Exception {
        try (var f = fixture(version)) {
            SimItemStack trident = trident(3);
            charge(f, trident);
            breaking(trident);
            release(f);
            assertRelease(f, level, exact);
        }
    }

    /** LivingEntity#updatingUsingItem keeps a trident's use on any held trident, enchanted or not. */
    @ParameterizedTest
    @CsvSource({"V_1_21_11, 3, true", "V_1_21_11, 0, false", "V_26_3, 3, true", "V_26_3, 0, false"})
    void aSwitchFromATridentWithoutRiptideSpinsAtTheHeldLevel(ClientVersion version, int level, boolean exact)
            throws Exception {
        try (var f = fixture(version)) {
            charge(f, OfflineCultTestBootstrap.item("minecraft:trident"), trident(3));
            select(f, 1);
            release(f);
            assertRelease(f, level, exact);
        }
    }

    /** TridentItem#releaseUsing refuses a passenger from 26.1. */
    @ParameterizedTest
    @CsvSource({"V_1_21_11, true", "V_26_1, false", "V_26_2, false", "V_26_3, false"})
    void aPassengerReleaseSpinsBefore26_1(ClientVersion version, boolean spins) throws Exception {
        try (var f = fixture(version)) {
            charge(f, trident(3));
            f.player.compensatedEntities.addEntity(90, EntityTypeIds.PIG, SHALLOWS, 0, 0, 0);
            f.setPassengers(90, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            release(f);
            assertEquals(spins, f.player.riptideSpinAttackTicks == 20);
        }
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        return new JavaTickFixture(ProtocolVersion.V26_3, version);
    }

    /** {@code trident} one damage from breaking. */
    private static SimItemStack breaking(SimItemStack trident) {
        trident.damage(trident.maxDamage() - 1);
        return trident;
    }

    /** Whether the release tick moves exactly by the spin at {@code level}, or resting in the shallows without one. */
    private static void assertRelease(JavaTickFixture f, int level, boolean exact) {
        PredictionResult result = level == 0 ? f.tick(RESTED, true) : f.tick(lifted(level), false);
        assertEquals(exact, exact(result), describe(result));
    }
}
