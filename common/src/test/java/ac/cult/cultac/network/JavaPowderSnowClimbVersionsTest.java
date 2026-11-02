package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaPowderSnowClimbVersionsTest {
    /**
     * Entity#baseTick copies the preceding inside-block pass's powder contact before clearing it.
     * From 1.21.5 handleRelativeFrictionAndCalculateMovement uses that wasInPowderSnow flag,
     * even when only the player's head touched powder and its feet never entered the block.
     * Through 1.21.4 the same method instead reads the current feet block.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void headContactOnThePreviousTickControlsTheJumpCarry(String release, ClientVersion version) throws Exception {
        headContactCarry(release, version, false);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aBlockUpdatePreservesOldContactUntilTheFollowingTickClearsIt(String release, ClientVersion version)
            throws Exception {
        headContactCarry(release, version, true);
    }

    private static void headContactCarry(String release, ClientVersion version, boolean removePowder) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_BOOTS, new SimItemStack(ItemTypes.LEATHER_BOOTS, 1, Components.EMPTY));
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 1, 0), BlockIds.POWDER_SNOW.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            // Land with the ordinary carried gravity. A zero-Y Entity#move clears onGround,
            // so it cannot supply the grounded jump the following tick exercises.
            f.place(start, new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var contact = f.tick(start, true);
            assertTrue(exact(contact), describe(contact));

            if (removePowder)
                f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 1, 0), BlockIds.AIR.defaultState());

            f.input(new ServerboundPlayerInput(false, false, false, false, true, false, false));
            var jumping = f.tick(start.add(0, (double) 0.42F, 0), false);
            assertTrue(exact(jumping), describe(jumping));
            double beforeDrag = version.isOlderThan(ClientVersion.V_1_21_5) ? (double) 0.42F : 0.2;
            double expectedY = (beforeDrag - 0.08) * 0.98F;
            var carry = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertTrue(
                    carry.stream().anyMatch(v -> Math.abs(v.y - expectedY) < 1e-10),
                    "source powder-contact jump carry for " + release + ": expected " + expectedY + ", got " + carry);
            double wrongY = ((version.isOlderThan(ClientVersion.V_1_21_5) ? 0.2 : (double) 0.42F) - 0.08) * 0.98F;
            assertFalse(
                    carry.stream().anyMatch(v -> Math.abs(v.y - wrongY) < 1e-10),
                    "the incompatible version's carry must not remain: " + carry);
            var following = f.tick(start.add(0, (double) 0.42F + expectedY, 0), false);
            assertTrue(exact(following), describe(following));
            double followingY = !removePowder && version.isNewerThanOrEquals(ClientVersion.V_1_21_5)
                    ? (0.2 - 0.08) * 0.98F
                    : (expectedY - 0.08) * 0.98F;
            var nextCarry = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertTrue(
                    nextCarry.stream().anyMatch(v -> Math.abs(v.y - followingY) < 1e-10),
                    "powder contact must be updated by each tick's own callbacks: " + nextCarry);
            if (removePowder && version.isNewerThanOrEquals(ClientVersion.V_1_21_5))
                assertFalse(
                        nextCarry.stream().anyMatch(v -> Math.abs(v.y - expectedY) < 1e-10),
                        "powder contact cannot survive a tick without callbacks: " + nextCarry);
        }
    }

    /** Newly entered powder is remembered after travel; it cannot retroactively supply wasInPowderSnow. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void theFirstPowderContactDoesNotUseTheFollowingTicksFlag(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_BOOTS, new SimItemStack(ItemTypes.LEATHER_BOOTS, 1, Components.EMPTY));
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.POWDER_SNOW.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, true, false, false));
            var jumping = f.tick(start.add(0, (double) 0.42F, 0), false);
            assertTrue(exact(jumping), describe(jumping));
            double beforeDrag = version.isOlderThan(ClientVersion.V_1_21_5) ? 0.2 : (double) 0.42F;
            double expectedY = (beforeDrag - 0.08) * 0.98F;
            var carry = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertTrue(
                    carry.stream().anyMatch(v -> Math.abs(v.y - expectedY) < 1e-10),
                    "first powder-contact carry for " + release + ": " + carry);
            double wrongY = ((version.isOlderThan(ClientVersion.V_1_21_5) ? (double) 0.42F : 0.2) - 0.08) * 0.98F;
            assertFalse(
                    carry.stream().anyMatch(v -> Math.abs(v.y - wrongY) < 1e-10),
                    "new contact cannot invent an extra previous-contact branch: " + carry);
        }
    }
}
