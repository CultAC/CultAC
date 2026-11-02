package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A server starts the main-hand use through DATA_LIVING_ENTITY_FLAGS while the client's slot change is still
 * unreported: LocalPlayer#onSyncedDataUpdated starts the use of whatever stack the client selected, so any hotbar stack
 * may be the used one. From 1.21.11 UseEffects#speedMultiplier gives each its own LocalPlayer#modifyInput slowdown.
 * The client's following set_carried_item ends the server's own use obligation; the client still travels with the
 * stack it is using.
 */
class JavaItemUseMultiplierTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void aUseBetweenTheFastestAndSlowestPossibleOnesMovesAtItsOwnSpeed(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            Vec3 position = startUseWithUnreportedSlot(f);
            Vec3 delta = ClientMoveInput.of(f, FORWARD, 0.5F, groundSpeed(version), f.player.xRot);
            PredictionResult result = f.tick(position.add(delta), true);
            assertTrue(exact(result), describe(result));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void noPossibleUseMovesAtAnUnheldSpeed(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            Vec3 position = startUseWithUnreportedSlot(f);
            Vec3 delta = ClientMoveInput.of(f, FORWARD, 0.7F, groundSpeed(version), f.player.xRot);
            PredictionResult result = f.tick(position.add(delta), true);
            assertFalse(exact(result), describe(result));
        }
    }

    /**
     * Slot 0 holds a spyglass (the default 0.2) and slot 1 a bow whose use_effects slow to 0.5; the other slots are
     * empty, so the client may also hold no use. The server starts the slot-0 use; the client reports slot 1.
     */
    private static Vec3 startUseWithUnreportedSlot(JavaTickFixture f) {
        var storage = f.player.getInventory().inventory.getInventoryStorage();
        SimItemStack slow = ItemUtil.modelItems().stack("minecraft:spyglass", 1);
        SimItemStack half = ItemUtil.modelItems().stack("minecraft:bow", 1);
        half.components(
                half.components().with("minecraft:use_effects", JsonParser.parseString("{\"speed_multiplier\":0.5}")));
        storage.setItem(Inventory.HOTBAR_OFFSET, slow);
        storage.setItem(Inventory.HOTBAR_OFFSET + 1, half);
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
        f.place(start, new Vec3(0, -0.0784000015258789, 0));
        f.input(FORWARD);
        InputKeysTest.selfMetadata(f, WatchableIndexUtil.LIVING_ENTITY_FLAGS, (byte) 1);
        f.selectHotbarSlot(1);
        assertEquals(List.of(slow, half), List.of(storage.getItem(36), storage.getItem(37)));
        return start;
    }

    /** LivingEntity#getFrictionInfluencedSpeed on stone; from 26.2 only friction above 0.6 boosts it. */
    private static float groundSpeed(ClientVersion version) {
        if (version.isNewerThanOrEquals(ClientVersion.V_26_2)) return 0.1F;
        return 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F));
    }
}
