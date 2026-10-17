package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PositionListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.data.CooldownData;
import java.util.concurrent.ConcurrentHashMap;

/** ItemCooldowns uses the stack's cooldown group, including custom component overrides. */
public class CompensatedCooldown extends CultProcessor implements PositionListener, ClientTickEndListener {
    private final ConcurrentHashMap<String, CooldownData> cooldowns = new ConcurrentHashMap<>();
    // Groups that only some client realities started; a use in one may or may not start.
    private final ConcurrentHashMap<String, CooldownData> possible = new ConcurrentHashMap<>();

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
        return !player.isBedrockMovement();
    }

    private void tick() {
        tick(cooldowns);
        tick(possible);
    }

    private void tick(ConcurrentHashMap<String, CooldownData> groups) {
        if (groups.isEmpty()) return;
        groups.entrySet().removeIf(entry -> {
            CooldownData cooldown = entry.getValue();
            if (cooldown.getTransaction() <= player.lastTransactionReceived.get()) cooldown.tick();
            return cooldown.getTicksRemaining() <= 0;
        });
    }

    public boolean hasItem(ac.cult.blocksim.engine.SimItemStack item) {
        return !cooldowns.isEmpty() && item != null && !item.isEmpty() && cooldowns.containsKey(group(item));
    }

    public boolean possiblyHasItem(SimItemStack item) {
        return !possible.isEmpty() && item != null && !item.isEmpty() && possible.containsKey(group(item));
    }

    public static String group(SimItemStack item) {
        var cooldown = ac.cult.blocksim.data.ItemComponents.useCooldown(item.components());
        return cooldown == null || cooldown.group() == null ? item.itemKey() : cooldown.group();
    }

    public void addPredictedCooldown(SimItemStack beforeUse, int ticks) {
        // Minecraft.handleKeybinds uses the item before Player.tick advances cooldowns.
        // The same tick's tick-end therefore counts, including ticks without movement.
        put(group(beforeUse), ticks, player.lastTransactionReceived.get());
    }

    /** ClientboundCooldown and every-reality completions set ItemCooldowns for the group outright. */
    public void addCooldown(String group, int ticks, int transaction) {
        possible.remove(group);
        put(group, ticks, transaction);
    }

    /** A completion that only some client realities performed. */
    public void addPossibleCooldown(String group, int ticks) {
        var existing = possible.get(group);
        if (existing == null || existing.getTicksRemaining() < ticks)
            possible.put(group, new CooldownData(ticks, player.lastTransactionReceived.get()));
    }

    public void clearPossibleCooldowns() {
        possible.clear();
    }

    private void put(String group, int ticks, int transaction) {
        if (ticks == 0) cooldowns.remove(group);
        else cooldowns.put(group, new CooldownData(ticks, transaction));
    }

    /** Actions query remaining durations; elapsed client ticks stay owned by this processor. */
    public ac.cult.blocksim.engine.SimCooldowns blockSimulatorSnapshot() {
        var values = new java.util.HashMap<String, ac.cult.blocksim.engine.SimCooldowns.Cooldown>();
        cooldowns.forEach((group, value) ->
                values.put(group, new ac.cult.blocksim.engine.SimCooldowns.Cooldown(0, value.getTicksRemaining())));
        return new ac.cult.blocksim.engine.SimCooldowns(0, values);
    }

    public void applyBlockSimulatorChanges(
            ac.cult.blocksim.engine.SimCooldowns before, ac.cult.blocksim.engine.SimCooldowns after) {
        var previous = before.snapshot();
        var changed = after.snapshot();
        previous.keySet().stream()
                .filter(group -> !changed.containsKey(group))
                .forEach(group -> put(group, 0, player.lastTransactionReceived.get()));
        // A predicted action computed from the definite state does not end a possible
        // cooldown: in that reality the same action may have been refused.
        changed.forEach((group, value) -> {
            if (!value.equals(previous.get(group)))
                put(group, value.endTime() - after.tickCount(), player.lastTransactionReceived.get());
        });
    }
}
