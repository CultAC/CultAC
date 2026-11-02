package ac.cult.cultac.network;

import static ac.cult.cultac.network.InputKeysTest.selfMetadata;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.SETTLED;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.SHALLOWS;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.charge;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.hold;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.lifted;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.release;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.select;
import static ac.cult.cultac.network.JavaRiptideTridentSwitchTest.trident;
import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * TridentItem#releaseUsing for a riptide trident charged in shallow water and released on the ground. The release
 * spins at the released trident's riptide level once the use has run ten ticks.
 */
class JavaRiptideReleaseTest {
    private static final int VEHICLE = 100;

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
    @CsvSource({"V_1_21_11, 2, true", "V_1_21_11, 0, false", "V_26_3, 2, true", "V_26_3, 3, false", "V_26_3, 0, false"})
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

    /** TridentItem#use refuses a riptide trident out of water and rain, so the client releases no use. */
    @ParameterizedTest
    @CsvSource({"V_1_21_11, 0, true", "V_1_21_11, 3, false", "V_26_3, 0, true", "V_26_3, 3, false"})
    void aUseStartedOutOfWaterDoesNotSpin(ClientVersion version, int level, boolean exact) throws Exception {
        try (var f = fixture(version)) {
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET, trident(3));
            f.place(SHALLOWS, SETTLED);
            use(f, Hand.MAIN_HAND);
            f.water(new BlockPos(0, FLOOR_Y, 0), new BlockPos(0, FLOOR_Y, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            hold(f, 11);
            release(f);
            assertRelease(f, level, exact);
        }
    }

    /** Minecraft#startUseItem runs only while the client uses no item, so a use of either hand ends no use. */
    @ParameterizedTest
    @CsvSource({"V_1_21_11, 0, true", "V_1_21_11, 3, false", "V_26_3, 0, true", "V_26_3, 3, false"})
    void aUseOfTheOtherHandEndsTheTridentsUse(ClientVersion version, int level, boolean exact) throws Exception {
        try (var f = fixture(version)) {
            charge(f, trident(3));
            use(f, Hand.OFF_HAND);
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

    /** LocalPlayer#onSyncedDataUpdated stops the use when the server's living-entity flags clear its using bit. */
    @ParameterizedTest
    @CsvSource({"V_1_21_11, 0, true", "V_1_21_11, 3, false", "V_26_3, 0, true", "V_26_3, 3, false"})
    void aUseTheServerStopsDoesNotSpin(ClientVersion version, int level, boolean exact) throws Exception {
        try (var f = fixture(version)) {
            charge(f, trident(3));
            selfMetadata(f, WatchableIndexUtil.LIVING_ENTITY_FLAGS, (byte) 0);
            hold(f, 1);
            release(f);
            assertRelease(f, level, exact);
        }
    }

    /** LocalPlayer#onSyncedDataUpdated starts no use from the server's flags while the client blocks with a shield. */
    @ParameterizedTest
    @CsvSource({"V_1_21_11, 0, true", "V_1_21_11, 3, false", "V_26_3, 0, true", "V_26_3, 3, false"})
    void aUseTheServerFlagsDuringAShieldsUseDoesNotStart(ClientVersion version, int level, boolean exact)
            throws Exception {
        try (var f = fixture(version)) {
            SimItemStack shield = OfflineCultTestBootstrap.item("minecraft:shield");
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, shield);
            charge(f, 0, Hand.OFF_HAND, trident(3));
            f.player.clientItemUse.start(Hand.OFF_HAND, shield, ClientItemUseState.duration(shield), true);
            selfMetadata(f, WatchableIndexUtil.LIVING_ENTITY_FLAGS, (byte) 1);
            hold(f, 10);
            release(f);
            assertRelease(f, level, exact);
        }
    }

    /**
     * Entity#rideTick zeroes a passenger's delta, so its spin leaves the vehicle's travel in water unchanged. A ridden
     * camel deep in water rises by LivingEntity#floatInLiquidWhileRidden from the floor.
     */
    @Test
    void aPassengersSpinLeavesItsVehicleInWater() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_11)) {
            f.water(new BlockPos(-8, FLOOR_Y, -8), new BlockPos(8, FLOOR_Y + 3, 8));
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET, trident(3));
            f.place(SHALLOWS.add(0, 1, 0), Vec3.ZERO);
            f.player.compensatedEntities.addEntity(
                    VEHICLE, EntityTypes.defaults().byKey("minecraft:camel").id(), SHALLOWS, 0, 0, 0);
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            var camel = new FloatingCamel();
            for (int tick = 0; tick < 2; tick++) camel.rideTick(f);
            use(f, Hand.MAIN_HAND);
            for (int tick = 0; tick < 10; tick++) camel.rideTick(f);
            release(f);
            assertEquals(20, f.player.riptideSpinAttackTicks);
            PredictionResult result = camel.rideTick(f);
            assertTrue(result.getSimulationContext().getWorldData().isInWater(), describe(result));
        }
    }

    /**
     * A ridden camel without input that rises from the floor of deep still water: LivingEntity#travelInWater moves it
     * by its delta, then slows that by 0.8 and applies the fluid's gravity, and LivingEntity#floatInLiquidWhileRidden
     * adds 0.04.
     */
    private static final class FloatingCamel {
        private double y = SHALLOWS.y;
        private double deltaY;

        /** One ridden tick that the camel's move must match exactly. */
        PredictionResult rideTick(JavaTickFixture f) {
            boolean onFloor = deltaY == 0;
            y += deltaY;
            deltaY = deltaY * 0.8F - 0.08 / 16.0 + 0.04F;
            PredictionResult result = f.rideTick(new Vec3(SHALLOWS.x, y, SHALLOWS.z), onFloor);
            assertTrue(exact(result), describe(result));
            return result;
        }
    }

    /**
     * LocalPlayer#onSyncedDataUpdated starts a use on the flagged hand when the server's flags set the using bit
     * while the client is not using, and the charge counts from there.
     */
    @ParameterizedTest
    @CsvSource({
        "V_1_21_11, 9, 0, true",
        "V_1_21_11, 9, 3, false",
        "V_1_21_11, 10, 3, true",
        "V_1_21_11, 10, 0, false",
        "V_26_3, 9, 0, true",
        "V_26_3, 9, 3, false",
        "V_26_3, 10, 3, true",
        "V_26_3, 10, 0, false"
    })
    void aUseTheServerStartsChargesFromItsFlags(ClientVersion version, int ticks, int level, boolean exact)
            throws Exception {
        try (var f = fixture(version)) {
            charge(f, trident(3));
            selfMetadata(f, WatchableIndexUtil.LIVING_ENTITY_FLAGS, (byte) 0);
            hold(f, 5);
            selfMetadata(f, WatchableIndexUtil.LIVING_ENTITY_FLAGS, (byte) 1);
            hold(f, ticks);
            release(f);
            assertRelease(f, level, exact);
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

    private static void use(JavaTickFixture f, Hand hand) {
        f.useItem(hand, 0, 0.0F);
    }

    /**
     * Whether the release tick moves exactly by the spin at {@code level}, or rests on the floor of the shallows without
     * one: LivingEntity#travelInWater moves before it applies the fluid's gravity, so the floor keeps the player still.
     */
    private static void assertRelease(JavaTickFixture f, int level, boolean exact) {
        PredictionResult result = level == 0 ? f.tick(SHALLOWS, true) : f.tick(lifted(level), false);
        assertEquals(exact, exact(result), describe(result));
    }
}
