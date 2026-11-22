package ac.cult.cultac.network;

import static ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item;
import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.elytra.ElytraD;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * LivingEntity#canGlide tests every EquipmentSlot, the main hand included: an item with the minecraft:glider component
 * glides from the slot its minecraft:equippable names (LivingEntity#canGlideUsing).
 */
class ElytraDMainHandGliderTest {
    @Test
    void aGliderEquippableInTheMainHandGlidesFromIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            assertEquals(0.0, startGlidingHolding(f, glider("mainhand")), 0.0);
        }
    }

    @Test
    void aGliderEquippableInTheOffHandDoesNotGlideFromTheMainHand() throws Exception {
        try (var f = new JavaTickFixture()) {
            double violations = startGlidingHolding(f, glider("offhand"));
            assertTrue(violations > 0, "ElytraD violations " + violations);
        }
    }

    private static SimItemStack glider(String slot) {
        SimItemStack stick = item("minecraft:stick");
        stick.components(stick.components()
                .with("minecraft:glider", JsonParser.parseString("{}"))
                .with("minecraft:equippable", JsonParser.parseString("{\"slot\":\"" + slot + "\"}")));
        return stick;
    }

    private static double startGlidingHolding(JavaTickFixture f, SimItemStack held) {
        f.player.setExperimentalChecks(true);
        f.place(new Vec3(0.5, FLOOR_Y + 3, 0.5), Vec3.ZERO);
        f.player.getInventory().inventory.setHeldItem(held);
        ElytraD elytraD = f.player.checkManager.getCheck(ElytraD.class);
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
        elytraD.onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, packet), f.player, packet);
        return elytraD.violations;
    }
}
