package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A hotbar switch while a trident charges in shallow water. ServerGamePacketListenerImpl#handleSetCarriedItem stops only
 * the server's use; LivingEntity#updatingUsingItem keeps the client's use and its timer while the hand holds the same
 * item. From 1.21.5 LivingEntity#releaseUsingItem releases the stack the hand holds at the release; through 1.21.4 the
 * stack the last entity tick bound.
 */
class JavaRiptideSwitchReleaseTest {
    private static final Vec3 SHALLOWS = new Vec3(0.5, FLOOR_Y, 0.5);
    /** LivingEntity#travelInFluid's gravity on a player resting on the floor of the water. */
    private static final Vec3 SETTLED = new Vec3(0, -0.005, 0);

    private static final float LIFT = 1.1999999F;
    private static final float PITCH = -10.0F;

    /** A switch from a riptide 1 trident to a riptide 3 one releases the riptide 3 one at the charge the use kept. */
    @Test
    void aSwitchToAnotherRiptideTridentKeepsTheCharge() throws Exception {
        try (var f = new JavaTickFixture()) {
            inShallows(f, trident(1), trident(3));
            chargeAndSwitch(f);
            f.releaseUsingItem();
            assertEquals(3, f.player.packetStateData.riptideLevel);
            assertTrue(f.player.riptideSpinCertain, "the release is not certain");
            PredictionResult released = f.tick(lifted(), false);
            assertTrue(exact(released), describe(released));
        }
    }

    /** Any trident use can end in a riptide release: a switch from a plain trident keeps its charge. */
    @Test
    void aSwitchFromAPlainTridentReleasesTheRiptideOne() throws Exception {
        try (var f = new JavaTickFixture()) {
            inShallows(f, OfflineCultTestBootstrap.item("minecraft:trident"), trident(3));
            chargeAndSwitch(f);
            f.releaseUsingItem();
            assertEquals(3, f.player.packetStateData.riptideLevel);
            PredictionResult released = f.tick(lifted(), false);
            assertTrue(exact(released), describe(released));
        }
    }

    /** From 1.21.5 a release in the tick of a switch away from the trident hands TridentItem nothing. */
    @Test
    void aSwitchAwayFromTheTridentInTheReleaseTickReleasesNothing() throws Exception {
        try (var f = new JavaTickFixture()) {
            inShallows(f, trident(3), OfflineCultTestBootstrap.item("minecraft:stick"));
            charge(f, 11);
            f.selectHotbarSlot(1);
            f.releaseUsingItem();
            assertEquals(0, f.player.packetStateData.riptideLevel);
            assertFalse(f.player.packetStateData.invalidRiptideRelease);
        }
    }

    /** Through 1.21.4 the same release hands over the trident the last entity tick bound, which spins. */
    @Test
    void throughOneTwentyOneFourASwitchInTheReleaseTickReleasesTheBoundTrident() throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            inShallows(f, trident(3), OfflineCultTestBootstrap.item("minecraft:stick"));
            charge(f, 11);
            f.selectHotbarSlot(1);
            f.releaseUsingItem();
            assertEquals(3, f.player.packetStateData.riptideLevel);
            assertTrue(f.player.riptideSpinCertain, "the release is not certain");
        }
    }

    /** {@code first} in hotbar slot 0, selected, and {@code second} in slot 1, standing in water at SHALLOWS. */
    private static void inShallows(JavaTickFixture f, SimItemStack first, SimItemStack second) {
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.WATER.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        var storage = f.player.getInventory().inventory.getInventoryStorage();
        storage.setItem(Inventory.HOTBAR_OFFSET, first);
        storage.setItem(Inventory.HOTBAR_OFFSET + 1, second);
        f.place(SHALLOWS, SETTLED);
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
    }

    /** Uses the main hand for {@code ticks} client ticks, then looks along +Z at PITCH for the release. */
    private static void charge(JavaTickFixture f, int ticks) {
        f.useItem(Hand.MAIN_HAND, 0, PITCH);
        for (int tick = 0; tick < ticks; tick++) f.tick(SHALLOWS, true);
        f.player.xRot = 0.0F;
        f.player.yRot = PITCH;
    }

    /** Eleven ticks of use that switch to hotbar slot 1 after the sixth: under ten ticks before the switch. */
    private static void chargeAndSwitch(JavaTickFixture f) {
        charge(f, 6);
        f.selectHotbarSlot(1);
        for (int tick = 0; tick < 5; tick++) f.tick(SHALLOWS, true);
    }

    private static SimItemStack trident(int riptide) {
        SimItemStack trident = OfflineCultTestBootstrap.item("minecraft:trident");
        trident.setComponent("minecraft:enchantments", CanonicalSnbt.parse("{'minecraft:riptide':" + riptide + "}"));
        return trident;
    }

    /** Where the release tick leaves a riptide 3 release on the floor: the lift, then the push. */
    private static Vec3 lifted() {
        return SHALLOWS.add(0, LIFT, 0).add(SETTLED.add(push()));
    }

    /** TridentItem#releaseUsing at riptide 3, looking along +Z at PITCH. */
    private static Vec3 push() {
        float xRot = PITCH * (float) (Math.PI / 180.0);
        float xd = -VanillaMath.sin(0.0F) * VanillaMath.cos(xRot);
        float yd = -VanillaMath.sin(xRot);
        float zd = VanillaMath.cos(0.0F) * VanillaMath.cos(xRot);
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        return new Vec3(xd * (3.0F / dist), yd * (3.0F / dist), zd * (3.0F / dist));
    }
}
