package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.player.CultPlayer.TrackedTransaction;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.UseCooldown;
import org.bukkit.Material;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PacketPlayerCooldown {
    private static final ConcurrentHashMap<String, List<Material>> COOLDOWN_GROUP_ITEMS = new ConcurrentHashMap<>();

    //HIGH
    @CultPacketHandler
    public void onCooldown(PacketSendEvent event, CultPlayer player, ClientboundCooldownPacket packet) {
        List<Material> items = resolveCooldownItems(NmsIdentifierUtil.cooldownGroup(packet));
        if (items.isEmpty()) {
            return;
        }

        int proofTransaction = player.lastTransactionSent.get();
        TrackedTransaction trackedTransaction = player.createTrackedTransactionPacketForBundle();
        if (trackedTransaction != null) {
            proofTransaction = trackedTransaction.transaction();
            TrackedTransaction sentTransaction = trackedTransaction;
            event.getTasksAfterSend().add(() -> {
                player.user.writePacket(sentTransaction.packet());
                player.markTrackedTransactionPacketSent(sentTransaction);
            });
        }

        int lastTransactionSent = proofTransaction;
        int duration = NmsIdentifierUtil.cooldownDuration(packet);
        if (duration == 0) {
            player.latencyUtils.addRealTimeTask(lastTransactionSent, () -> { for (Material item : items) {
                    player.checkManager.getCompensatedCooldown().removeCooldown(item);
                }
            });
            return;
        }

        player.latencyUtils.addRealTimeTask(lastTransactionSent, () -> { for (Material item : items) {
                player.checkManager.getCompensatedCooldown().addCooldown(item, duration, lastTransactionSent);
            }
        });
    }

    private static List<Material> resolveCooldownItems(String cooldownGroup) {
        return COOLDOWN_GROUP_ITEMS.computeIfAbsent(cooldownGroup, PacketPlayerCooldown::buildCooldownItems);
    }

    private static List<Material> buildCooldownItems(String cooldownGroup) {
        Set<Material> items = new LinkedHashSet<>();

        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isItem()) {
                continue;
            }

            net.minecraft.world.item.ItemStack itemStack = SpigotConversionUtil.toNmsItemStack(new org.bukkit.inventory.ItemStack(material));
            if (itemStack.isEmpty()) {
                continue;
            }

            Item item = itemStack.getItem();
            String itemKey = material.getKey().toString();
            UseCooldown useCooldown = item.components().get(DataComponents.USE_COOLDOWN);
            String itemCooldownGroup = NmsIdentifierUtil.useCooldownGroup(useCooldown, itemKey);

            if (!cooldownGroup.equals(itemCooldownGroup)) {
                continue;
            }

            items.add(material);
        }

        if (items.isEmpty()) {
            Material fallback = resolveMaterial(cooldownGroup);
            if (fallback != null) {
                items.add(fallback);
            }
        }

        return List.copyOf(items);
    }

    private static Material resolveMaterial(String key) {
        Material material = Material.matchMaterial(key);
        if (material != null) {
            return material;
        }
        int separator = key.indexOf(':');
        String path = separator >= 0 ? key.substring(separator + 1) : key;
        return Material.getMaterial(path.toUpperCase(java.util.Locale.ROOT));
    }
}
