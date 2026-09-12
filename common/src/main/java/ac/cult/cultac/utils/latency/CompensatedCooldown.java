package ac.cult.cultac.utils.latency;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PositionListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.data.CooldownData;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.ConcurrentHashMap;

/** ItemCooldowns uses the stack's cooldown group, including custom component overrides. */
public class CompensatedCooldown extends CultProcessor implements PositionListener, ClientTickEndListener {
    private final ConcurrentHashMap<String, CooldownData> cooldowns = new ConcurrentHashMap<>();

    public CompensatedCooldown(CultPlayer player) {
        super(player);
    }

    @Override
    public void onPositionUpdate(PositionUpdate update) {
        if (!usesClientTickEnd()) tick();
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (usesClientTickEnd()) tick();
    }

    private boolean usesClientTickEnd() {
        return !player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }

    private void tick() {
        cooldowns.entrySet().removeIf(entry -> {
            CooldownData cooldown = entry.getValue();
            if (cooldown.getTransaction() <= player.lastTransactionReceived.get()) cooldown.tick();
            return cooldown.getTicksRemaining() <= 0;
        });
    }

    public boolean hasItem(org.bukkit.inventory.ItemStack item) {
        return !cooldowns.isEmpty() && item != null && !item.isEmpty()
                && cooldowns.containsKey(group(SpigotConversionUtil.toNmsItemStack(item)));
    }

    public static String group(ItemStack item) {
        return NmsIdentifierUtil.useCooldownGroup(item.get(DataComponents.USE_COOLDOWN),
                NmsIdentifierUtil.registryKey(BuiltInRegistries.ITEM, item.getItem()));
    }

    public void addPredictedCooldown(ItemStack beforeUse, int ticks) {
        // Minecraft.handleKeybinds uses the item before Player.tick advances cooldowns.
        // The same tick's tick-end therefore counts, including ticks without movement.
        addCooldown(group(beforeUse), ticks, player.lastTransactionReceived.get());
    }

    public void addCooldown(String group, int ticks, int transaction) {
        if (ticks == 0) cooldowns.remove(group);
        else cooldowns.put(group, new CooldownData(ticks, transaction));
    }
}
