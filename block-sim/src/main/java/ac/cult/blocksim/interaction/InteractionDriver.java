package ac.cult.blocksim.interaction;

import ac.cult.blocksim.engine.*;
import java.util.Set;

/** The client use driver. Packet sending and acknowledgement belong to the adapter. */
public final class InteractionDriver {
    private final Set<String> enabledFeatures;
    private final StackActions stacks;
    private final SimCooldowns cooldowns;
    public InteractionDriver(Set<String> enabledFeatures, StackActions stacks, SimCooldowns cooldowns) {
        this.enabledFeatures = Set.copyOf(enabledFeatures); this.stacks = java.util.Objects.requireNonNull(stacks);
        this.cooldowns = java.util.Objects.requireNonNull(cooldowns);
    }
    public SimInteraction useItemOn(SimLevel level, SimPlayer player, Hand hand, BlockHit hit) {
        if (!level.isWithinBorder(hit.pos())) return SimInteraction.FAIL;
        return performUseItemOn(level, player, hand, hit);
    }
    private SimInteraction performUseItemOn(SimLevel level, SimPlayer player, Hand hand, BlockHit hit) {
        SimItemStack original = player.hand(hand);
        if (player.state().gameMode() == SimPlayer.GameMode.SPECTATOR) return SimInteraction.CONSUME;
        boolean haveSomething = !player.hand(Hand.MAIN_HAND).isEmpty() || !player.hand(Hand.OFF_HAND).isEmpty();
        boolean suppressUsingBlock = player.state().secondaryUseActive() && haveSomething;
        if (!suppressUsingBlock) {
            int state = level.stateAt(hit.pos());
            if (!enabledFeatures.containsAll(level.registry().block(state).features())) return SimInteraction.FAIL;
            var context = new UseContext(level, player, hand, player.hand(hand), hit, player.hand(hand).definition(), cooldowns);
            SimInteraction result = level.behavior(state).useItemOn(state, context);
            if (result.consumesAction()) return result;
            if (result.kind() == InteractionKind.TRY_WITH_EMPTY_HAND && hand == Hand.MAIN_HAND) {
                result = level.behavior(state).useWithoutItem(state, context);
                if (result.consumesAction()) return result;
            }
        }
        if (original.isEmpty() || cooldowns.isOnCooldown(original)) return SimInteraction.PASS;
        var context = new UseContext(level, player, hand, player.hand(hand), hit, player.hand(hand).definition(), cooldowns);
        SimInteraction result;
        if (player.state().infiniteMaterials()) {
            int count = original.count();
            result = stacks.useOn(original, context);
            original.count(count);
        } else {
            result = stacks.useOn(original, context);
            if (result.kind() == InteractionKind.SUCCESS || result.kind() == InteractionKind.CONSUME) {
                SimItemStack transformed = result.transformedStack() == null ? player.hand(hand) : result.transformedStack();
                if (transformed != original) player.hand(hand, transformed);
            }
        }
        return result;
    }
    public SimInteraction useItem(SimLevel level, SimPlayer player, Hand hand) {
        if (player.state().gameMode() == SimPlayer.GameMode.SPECTATOR) return SimInteraction.PASS;
        SimItemStack original = player.hand(hand);
        if (cooldowns.isOnCooldown(original)) return SimInteraction.PASS;
        SimInteraction result = stacks.use(original, new UseContext(level, player, hand, original, null, original.definition(), cooldowns));
        SimItemStack transformed = (result.kind() == InteractionKind.SUCCESS || result.kind() == InteractionKind.CONSUME) && result.transformedStack() != null
            ? result.transformedStack() : player.hand(hand);
        if (transformed != original) player.hand(hand, transformed);
        return result;
    }
}
