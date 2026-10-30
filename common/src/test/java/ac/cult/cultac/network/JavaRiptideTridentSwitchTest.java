package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A riptide trident charged in shallow water, then the hotbar switched before the release on the ground.
 * LivingEntity#updatingUsingItem keeps the use on another trident and moves it to the held stack in the entity tick,
 * after Minecraft#handleKeybinds; from 1.21.5 LivingEntity#releaseUsingItem moves it there first.
 */
class JavaRiptideTridentSwitchTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    static final Vec3 SHALLOWS = new Vec3(0.5, FLOOR_Y, 0.5);
    /** LivingEntity#travelInFluid's gravity on a player resting on the floor of the water. */
    static final Vec3 SETTLED = new Vec3(0, -0.005, 0);

    private static final float LIFT = 1.1999999F;
    private static final float PITCH = -10.0F;

    @ParameterizedTest
    @CsvSource({"V_1_21_4, 1, true", "V_1_21_4, 3, false", "V_26_3, 1, true", "V_26_3, 3, false"})
    void aSwitchBeforeTheReleaseTickSpinsAtTheHeldTridentsLevel(ClientVersion version, int level, boolean exact)
            throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            charge(f, trident(3), trident(1));
            select(f, 1);
            f.tick(SHALLOWS, true);
            release(f);
            PredictionResult result = f.tick(lifted(level), false);
            assertTrue(exact(result) == exact, describe(result));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "V_1_21_5, 1, true",
        "V_1_21_5, 3, false",
        "V_26_3, 1, true",
        "V_26_3, 3, false",
        "V_26_3, 0, false",
        "V_1_21_4, 1, true",
        "V_1_21_4, 3, true"
    })
    void aSwitchInTheReleaseTickSpinsAtTheLevelTheClientReleases(ClientVersion version, int level, boolean exact)
            throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            charge(f, trident(3), trident(1));
            select(f, 1);
            release(f);
            PredictionResult result = level == 0 ? f.tick(SHALLOWS.add(SETTLED), true) : f.tick(lifted(level), false);
            assertTrue(exact(result) == exact, describe(result));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aSwitchToAnotherItemBeforeTheReleaseTickStopsTheUse(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            charge(f, trident(3), stick());
            select(f, 1);
            f.tick(SHALLOWS, true);
            release(f);
            PredictionResult result = f.tick(SHALLOWS.add(SETTLED), true);
            assertTrue(exact(result), describe(result));
            assertFalse(f.player.riptideSpinCertain, "the release spins");
        }
    }

    @ParameterizedTest
    @CsvSource({"V_1_21_4, 3, true", "V_26_3, 3, false", "V_26_3, 0, true"})
    void aSwitchToAnotherItemInTheReleaseTickReleasesTheUsedStack(ClientVersion version, int level, boolean exact)
            throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            charge(f, trident(3), stick());
            select(f, 1);
            release(f);
            PredictionResult result = level == 0 ? f.tick(SHALLOWS.add(SETTLED), true) : f.tick(lifted(level), false);
            assertTrue(exact(result) == exact, describe(result));
        }
    }

    /** Key 2 in the previous tick's Minecraft#handleKeybinds, then key 1 and the release in this one. */
    @ParameterizedTest
    @CsvSource({"V_1_21_4, 1, true", "V_1_21_4, 3, true", "V_26_3, 3, true", "V_26_3, 1, false"})
    void aSwitchBackInTheReleaseTickSpinsAtTheLevelTheClientReleases(ClientVersion version, int level, boolean exact)
            throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            charge(f, trident(3), trident(1));
            select(f, 1);
            select(f, 0);
            release(f);
            PredictionResult result = f.tick(lifted(level), false);
            assertTrue(exact(result) == exact, describe(result));
        }
    }

    @ParameterizedTest
    @CsvSource({"V_1_21_4, 1, true", "V_1_21_4, 3, false", "V_26_3, 3, true", "V_26_3, 1, false"})
    void aSwitchPastAnotherItemInTheReleaseTickSpinsAtTheLevelTheClientReleases(
            ClientVersion version, int level, boolean exact) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            charge(f, trident(1), stick(), trident(3));
            select(f, 1);
            select(f, 2);
            release(f);
            PredictionResult result = f.tick(lifted(level), false);
            assertTrue(exact(result) == exact, describe(result));
        }
    }

    /** A scroll to the stick between ticks, then key 1 in the next tick's Minecraft#handleKeybinds. */
    @ParameterizedTest
    @CsvSource({"V_1_21_4, 3, true", "V_26_3, 3, true", "V_26_3, 0, false"})
    void aSwitchBackFromAnotherItemInTheNextTickSpinsAtTheLevelTheClientReleases(
            ClientVersion version, int level, boolean exact) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            charge(f, trident(3), stick());
            select(f, 1);
            f.tick(SHALLOWS, true);
            select(f, 0);
            release(f);
            PredictionResult result = level == 0 ? f.tick(SHALLOWS.add(SETTLED), true) : f.tick(lifted(level), false);
            assertTrue(exact(result) == exact, describe(result));
        }
    }

    static SimItemStack trident(int level) {
        SimItemStack trident = OfflineCultTestBootstrap.item("minecraft:trident");
        trident.setComponent("minecraft:enchantments", CanonicalSnbt.parse("{'minecraft:riptide':" + level + "}"));
        return trident;
    }

    private static SimItemStack stick() {
        return OfflineCultTestBootstrap.item("minecraft:stick");
    }

    /** Charges the trident in slot 0 with {@code hotbar} in the first slots. */
    static void charge(JavaTickFixture f, SimItemStack... hotbar) {
        charge(f, 11, Hand.MAIN_HAND, hotbar);
    }

    /** Uses {@code hand} for {@code ticks} client ticks in the shallows with {@code hotbar} in the first slots. */
    static void charge(JavaTickFixture f, int ticks, Hand hand, SimItemStack... hotbar) {
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.WATER.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        var storage = f.player.getInventory().inventory.getInventoryStorage();
        for (int slot = 0; slot < hotbar.length; slot++) storage.setItem(Inventory.HOTBAR_OFFSET + slot, hotbar[slot]);
        f.place(SHALLOWS, SETTLED);
        f.input(NONE);
        f.useItem(hand, 0, PITCH);
        hold(f, ticks);
    }

    /** {@code ticks} client ticks resting in the shallows, looking along +Z at {@link #PITCH}. */
    static void hold(JavaTickFixture f, int ticks) {
        for (int tick = 0; tick < ticks; tick++) f.tick(SHALLOWS, true);
        f.player.xRot = 0.0F;
        f.player.yRot = PITCH;
    }

    static void select(JavaTickFixture f, int slot) {
        var packet = new ServerboundSetCarriedItem(slot);
        var event = f.event(ServerboundPackets.SET_CARRIED_ITEM, packet);
        f.player.getInventory().onSetCarriedItem(event, f.player, packet);
        f.selectHotbarSlot(slot);
    }

    static void release(JavaTickFixture f) {
        f.releaseUsingItem();
    }

    /** The release tick's position: TridentItem#releaseUsing's push at {@code level}, then its lift. */
    static Vec3 lifted(int level) {
        return SHALLOWS.add(0, LIFT, 0).add(SETTLED).add(push(level));
    }

    /** TridentItem#releaseUsing at riptide {@code level}, looking along +Z at {@link #PITCH}. */
    private static Vec3 push(int level) {
        float xRot = PITCH * (float) (Math.PI / 180.0);
        float xd = -VanillaMath.sin(0.0F) * VanillaMath.cos(xRot);
        float yd = -VanillaMath.sin(xRot);
        float zd = VanillaMath.cos(0.0F) * VanillaMath.cos(xRot);
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        float strength = 3.0F * (1.0F + level) / 4.0F;
        return new Vec3(xd * (strength / dist), yd * (strength / dist), zd * (strength / dist));
    }
}
