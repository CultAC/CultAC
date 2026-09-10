package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class BedrockGlideEquipmentTest {
    @BeforeClass
    public static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    public void elytraMustHaveMoreThanOneDurabilityPointRemaining() {
        assertTrue(glideAvailable(item(Material.ELYTRA, 0)));
        assertTrue(glideAvailable(item(Material.ELYTRA, 430)));
        assertFalse(glideAvailable(item(Material.ELYTRA, 431)));
        assertFalse(glideAvailable(item(Material.ELYTRA, 432)));
    }

    @Test
    public void emptyOrNonElytraChestSlotCannotEnableGliding() {
        assertFalse(glideAvailable(null));
        assertFalse(glideAvailable(item(Material.AIR, 0)));
        assertFalse(glideAvailable(item(Material.DIAMOND_CHESTPLATE, 0)));
    }

    @Test
    public void eligibilityFollowsCompensatedChestSlotUpdates() {
        CultPlayer player = mock(CultPlayer.class);
        CompensatedInventory inventory = mock(CompensatedInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack worn = item(Material.ELYTRA, 430);
        ItemStack broken = item(Material.ELYTRA, 431);
        ItemStack repaired = item(Material.ELYTRA, 0);

        when(inventory.getChestplate()).thenReturn(worn);
        assertTrue(glideAvailableFor(player));
        when(inventory.getChestplate()).thenReturn(broken);
        assertFalse(glideAvailableFor(player));
        when(inventory.getChestplate()).thenReturn(repaired);
        assertTrue(glideAvailableFor(player));
        when(inventory.getChestplate()).thenReturn(null);
        assertFalse(glideAvailableFor(player));
    }

    private static boolean glideAvailable(ItemStack chestplate) {
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
        return BedrockMovementModifierFactory.create(playerContext, player, context).elytraGlideAvailable();
    }

    private static ItemStack item(Material material, int damage) {
        ItemStack item = mock(ItemStack.class);
        Damageable meta = mock(Damageable.class);
        when(item.getType()).thenReturn(material);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getDamage()).thenReturn(damage);
        return item;
    }
}
