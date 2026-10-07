package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import org.junit.BeforeClass;
import org.junit.Test;

public final class BedrockGlideEquipmentTest {
    @BeforeClass
    public static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    public void elytraMustHaveMoreThanOneDurabilityPointRemaining() {
        assertTrue(glideAvailable(item("minecraft:elytra", 0)));
        assertTrue(glideAvailable(item("minecraft:elytra", 430)));
        assertFalse(glideAvailable(item("minecraft:elytra", 431)));
        assertFalse(glideAvailable(item("minecraft:elytra", 432)));
    }

    @Test
    public void emptyOrNonElytraChestSlotCannotEnableGliding() {
        assertFalse(glideAvailable(null));
        assertFalse(glideAvailable(item("minecraft:air", 0)));
        assertFalse(glideAvailable(item("minecraft:diamond_chestplate", 0)));
    }

    @Test
    public void eligibilityFollowsCompensatedChestSlotUpdates() {
        CultPlayer player = mock(CultPlayer.class);
        CompensatedInventory inventory = mock(CompensatedInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        SimItemStack worn = item("minecraft:elytra", 430);
        SimItemStack broken = item("minecraft:elytra", 431);
        SimItemStack repaired = item("minecraft:elytra", 0);

        when(inventory.getChestplate()).thenReturn(worn);
        assertTrue(glideAvailableFor(player));
        when(inventory.getChestplate()).thenReturn(broken);
        assertFalse(glideAvailableFor(player));
        when(inventory.getChestplate()).thenReturn(repaired);
        assertTrue(glideAvailableFor(player));
        when(inventory.getChestplate()).thenReturn(null);
        assertFalse(glideAvailableFor(player));
    }

    private static boolean glideAvailable(SimItemStack chestplate) {
        CultPlayer player = mock(CultPlayer.class);
        CompensatedInventory inventory = mock(CompensatedInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getChestplate()).thenReturn(chestplate);
        return glideAvailableFor(player);
    }

    private static boolean glideAvailableFor(CultPlayer player) {
        SimulationContext context = mock(SimulationContext.class);
        when(context.getScale()).thenReturn(1.0F);
        BedrockPlayerContext playerContext = BedrockPlayerContext.from(player, context, null, false);
        return BedrockMovementModifierFactory.create(playerContext, player, context)
                .elytraGlideAvailable();
    }

    private static SimItemStack item(String key, int damage) {
        var item = OfflineCultTestBootstrap.item(key);
        item.damage(damage);
        return item;
    }
}
