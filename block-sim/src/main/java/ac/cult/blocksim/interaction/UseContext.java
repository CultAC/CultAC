package ac.cult.blocksim.interaction;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.data.ItemDefinition;

public class UseContext {
    private final SimLevel level;
    private final SimPlayer player;
    private final Hand hand;
    private final SimItemStack stack;
    private final BlockHit hit;
    private final ItemDefinition usedItem;
    private final ac.cult.blocksim.engine.SimCooldowns cooldowns;

    public UseContext(SimLevel level, SimPlayer player, Hand hand, SimItemStack stack, BlockHit hit) {
        this(level, player, hand, stack, hit, stack == null ? null : stack.definition());
    }
    public UseContext(SimLevel level, SimPlayer player, Hand hand, SimItemStack stack, BlockHit hit, ItemDefinition usedItem) {
        this(level, player, hand, stack, hit, usedItem, null);
    }
    public UseContext(SimLevel level, SimPlayer player, Hand hand, SimItemStack stack, BlockHit hit, ItemDefinition usedItem, ac.cult.blocksim.engine.SimCooldowns cooldowns) {
        this.level = level; this.player = player; this.hand = hand; this.stack = stack; this.hit = hit;
        this.usedItem = usedItem;
        this.cooldowns = cooldowns;
    }
    /** The dispatched Item instance can differ from the current context hand after a block callback. */
    public ItemDefinition usedItem() { return usedItem; }
    public UseContext forItem(ItemDefinition item) { return new UseContext(level, player, hand, stack, hit, item, cooldowns); }
    public UseContext forStack(SimItemStack stack) { return new UseContext(level, player, hand, stack, hit, usedItem, cooldowns); }
    public ac.cult.blocksim.engine.SimCooldowns cooldowns() { return java.util.Objects.requireNonNull(cooldowns, "Instrument use requires compensated cooldowns"); }
    public SimLevel level() { return level; }
    public SimPlayer player() { return player; }
    public Hand hand() { return hand; }
    public SimItemStack stack() { return stack; }
    public BlockHit hit() { return hit; }
    public BlockPos clickedPos() { return hit.pos(); }
    public Direction clickedFace() { return hit.face(); }
    public Vec3 clickLocation() { return hit.location(); }
    public boolean inside() { return hit.inside(); }
    public Direction horizontalDirection() { return player == null ? Direction.NORTH : Direction.fromYRot(player.state().yaw()); }
    public boolean secondaryUseActive() { return player != null && player.state().secondaryUseActive(); }
    public float rotation() { return player == null ? 0.0F : player.state().yaw(); }
}
