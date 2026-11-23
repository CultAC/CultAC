package ac.cult.blocksim.interaction;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockSimClientVersion;
import ac.cult.blocksim.engine.SimItemStack;
import java.util.Objects;

/** Tool rules and Player.getDestroySpeed's float operations in vanilla order. */
public final class MiningSpeed {
    /** Attribute values are already resolved at the client tick. -1 means no effect. */
    public record State(boolean onGround, boolean eyesInWater, double efficiency, double blockBreakSpeed,
                        double submergedSpeed, int haste, int conduitPower, int fatigue) { }
    private final DataTables data;
    public MiningSpeed(DataTables data) { this.data = Objects.requireNonNull(data); }

    public float toolSpeed(SimItemStack stack, int state) {
        var tool = ItemComponents.tool(stack.components());
        if (tool == null) return 1.0F;
        for (var rule : tool.rules()) if (rule.speed() != null
                && rule.blocks().contains(data, "block", data.registry().block(state).key())) return rule.speed();
        return tool.defaultMiningSpeed();
    }

    public boolean correctTool(SimItemStack stack, int state) {
        if (!data.registry().facts(state).has(StateFacts.CORRECT_TOOL)) return true;
        var tool = ItemComponents.tool(stack.components());
        if (tool != null) for (var rule : tool.rules()) if (rule.correctForDrops() != null
                && rule.blocks().contains(data, "block", data.registry().block(state).key())) return rule.correctForDrops();
        return false;
    }

    /** {@code fatigue} selects the Mining Fatigue form: 26.3 computes it, earlier clients use a table. */
    public float speed(SimItemStack stack, int state, State player, BlockSimClientVersion fatigue) {
        float speed = toolSpeed(stack, state);
        if (speed > 1.0F) speed += (float)player.efficiency();
        if (player.haste() >= 0 || player.conduitPower() >= 0) speed *= 1.0F + (Math.max(player.haste(), player.conduitPower()) + 1) * 0.2F;
        if (player.fatigue() >= 0) speed *= fatigue.atLeast(BlockSimClientVersion.V26_3)
                ? (float)Math.pow(0.3, player.fatigue() + 1)
                : switch (player.fatigue()) { case 0 -> 0.3F; case 1 -> 0.09F; case 2 -> 0.0027F; default -> 8.1E-4F; };
        speed *= (float)player.blockBreakSpeed();
        if (player.eyesInWater()) speed *= (float)player.submergedSpeed();
        if (!player.onGround()) speed /= 5.0F;
        return speed;
    }

    public float progress(SimItemStack stack, int state, State player, BlockSimClientVersion fatigue) {
        float hardness = data.registry().facts(state).destroyTime();
        return hardness == -1.0F ? 0.0F : speed(stack, state, player, fatigue) / hardness / (correctTool(stack, state) ? 30 : 100);
    }
}
