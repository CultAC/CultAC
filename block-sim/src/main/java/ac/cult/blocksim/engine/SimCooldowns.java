package ac.cult.blocksim.engine;

import java.util.HashMap;
import java.util.Map;

/** Client ItemCooldowns clock and groups. The adapter supplies the proven tick state. */
public final class SimCooldowns {
    public record Cooldown(int startTime, int endTime) { }
    private int tickCount;
    private final Map<String, Cooldown> cooldowns;
    public SimCooldowns(int tickCount, Map<String, Cooldown> cooldowns) {
        this.tickCount = tickCount; this.cooldowns = new HashMap<>(cooldowns);
    }
    public String group(SimItemStack stack) {
        var component = ac.cult.blocksim.data.ItemComponents.useCooldown(stack.components());
        return component == null || component.group() == null ? stack.itemKey() : component.group();
    }
    public float percent(SimItemStack stack, float partialTick) {
        Cooldown cooldown = cooldowns.get(group(stack));
        if (cooldown == null) return 0.0F;
        float duration = cooldown.endTime() - cooldown.startTime();
        float remaining = cooldown.endTime() - (tickCount + partialTick);
        float ratio = remaining / duration;
        return ratio < 0.0F ? 0.0F : Math.min(ratio, 1.0F);
    }
    public boolean isOnCooldown(SimItemStack stack) { return percent(stack, 0.0F) > 0.0F; }
    public void add(String group, int time) { cooldowns.put(group, new Cooldown(tickCount, tickCount + time)); }
    public void add(SimItemStack stack, int time) { add(group(stack), time); }
    public void remove(String group) { cooldowns.remove(group); }
    public void tick() {
        tickCount++;
        cooldowns.entrySet().removeIf(entry -> entry.getValue().endTime() <= tickCount);
    }
    public int tickCount() { return tickCount; }
    public Map<String, Cooldown> snapshot() { return Map.copyOf(cooldowns); }
}
