package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;

/** One client path for empty, fluid and mob buckets; entity spawning is server-only. */
public final class BucketItemBehavior implements ItemBehavior {
    private final ItemBehavior general;
    private final ItemRegistry items;
    private final AdventureRuleMatcher adventure;
    public BucketItemBehavior(ItemBehavior general, ItemRegistry items, AdventureRuleMatcher adventure) {
        this.general = general; this.items = items; this.adventure = adventure;
    }
    @Override public SimInteraction useOn(UseContext context) { return general.useOn(context); }
    @Override public SimInteraction use(UseContext context) {
        String content = context.usedItem().bindings().get("BucketItem.content");
        boolean mob = context.usedItem().bindings().get("classHierarchy").contains("net.minecraft.world.item.MobBucketItem");
        var pick = BlockRaycast.playerPick(context.level(), context.player(), content.equals("minecraft:empty") && !mob
            ? BlockRaycast.Fluid.SOURCE_ONLY : BlockRaycast.Fluid.NONE);
        if (pick.miss()) return SimInteraction.PASS;
        var hit = pick.hit(); var level = context.level(); var player = context.player(); var stack = player.hand(context.hand());
        if (!player.state().mayBuild() && !adventure.test(stack, "minecraft:can_place_on", level, hit.pos())) return SimInteraction.FAIL;
        int clicked = level.stateAt(hit.pos());
        boolean container = level.registry().block(clicked).bindings().get("classInterfaces").contains("net.minecraft.world.level.block.LiquidBlockContainer");
        var placePos = container && content.equals("minecraft:water") ? hit.pos() : hit.pos().relative(hit.face());
        SimItemStack filled;
        if (mob && content.equals("minecraft:empty") || emptyContents(level, player, placePos, hit, content)) {
            filled = player.state().infiniteMaterials() ? stack : items.stack("minecraft:bucket", 1);
        } else {
            String pickup = content.equals("minecraft:empty") ? level.behavior(clicked).pickupBlock(level, clicked, hit.pos(), player.state().infiniteMaterials()) : null;
            if (pickup == null) return SimInteraction.FAIL;
            filled = items.stack(pickup, 1);
        }
        return SimInteraction.SUCCESS.transformedTo(ItemResults.filled(stack, player, filled));
    }

    public boolean emptyContents(SimLevel level, SimPlayer player, BlockPos pos, BlockHit hit, String content) {
        if (!content.equals("minecraft:water") && !content.equals("minecraft:lava")) return false;
        int state = level.stateAt(pos); var facts = level.registry().facts(state); var behavior = level.behavior(state);
        boolean replaceable = behavior.canBeReplacedByFluid(level, state, content);
        boolean placeLiquid = replaceable || behavior.canPlaceLiquid(level, state, pos, content, player != null && player.state().infiniteMaterials());
        if (!facts.has(StateFacts.AIR) && (!placeLiquid || player != null && player.state().secondaryUseActive() && hit != null)) {
            return hit != null && emptyContents(level, player, hit.pos().relative(hit.face()), null, content);
        }
        if (content.equals("minecraft:water") && level.waterEvaporatesAt(pos)) return true;
        boolean container = level.registry().block(state).bindings().get("classInterfaces").contains("net.minecraft.world.level.block.LiquidBlockContainer");
        int fluidState = level.registry().block(content).defaultState();
        if (container && content.equals("minecraft:water")) {
            behavior.placeLiquid(level, state, pos, SimFluidState.of(level.registry().facts(fluidState)));
            return true;
        }
        return level.setBlock(pos, fluidState, 11) || facts.has(StateFacts.FLUID_SOURCE);
    }
}
