package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BlockBehavior;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Expectations follow MultiPlayerGameMode's branch order, including hand identity. */
class InteractionDriverTest {
    private static final DataTables DATA;
    private static final ItemRegistry ITEMS;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    private static final BlockHit HIT = new BlockHit(POS, Direction.UP, new Vec3(.5, 65, .5), false);
    static {
        try { DATA = DataTables.load("26.3"); ITEMS = new ItemRegistry(DATA); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @Test void worldBorderPrecedesSpectatorAndSpectatorPrecedesEveryUseBranch() {
        var fixture = new Fixture(SimPlayer.GameMode.SPECTATOR, false, 1, 0);
        fixture.withinBorder = false;
        assertEquals(SimInteraction.FAIL, fixture.on(Hand.MAIN_HAND));
        fixture.withinBorder = true;
        assertEquals(SimInteraction.CONSUME, fixture.on(Hand.MAIN_HAND));
        assertEquals(SimInteraction.PASS, fixture.driver.useItem(fixture.level, fixture.player, Hand.MAIN_HAND));
        assertTrue(fixture.calls.isEmpty());
    }

    @Test void blockUseRunsBeforeTheItemCooldownAndOnlyMainHandTriesEmptyHand() {
        for (Hand hand : Hand.values()) {
            var fixture = new Fixture(SimPlayer.GameMode.SURVIVAL, false, 2, 2);
            fixture.cooldowns.add(fixture.player.hand(hand), 10);
            fixture.blockResult = SimInteraction.TRY_WITH_EMPTY_HAND;
            fixture.emptyResult = SimInteraction.SUCCESS.withoutItem();
            assertEquals(hand == Hand.MAIN_HAND ? InteractionKind.SUCCESS : InteractionKind.PASS, fixture.on(hand).kind());
            assertEquals(hand == Hand.MAIN_HAND ? List.of("block", "empty") : List.of("block"), fixture.calls);
        }
        var fixture = new Fixture(SimPlayer.GameMode.SURVIVAL, false, 2, 0);
        fixture.blockResult = SimInteraction.PASS;
        fixture.cooldowns.add(fixture.player.hand(Hand.MAIN_HAND), 10);
        assertEquals(SimInteraction.PASS, fixture.on(Hand.MAIN_HAND));
        assertEquals(List.of("block"), fixture.calls);
    }

    @Test void sneakingSuppressesBlockAndFeatureChecksWhenEitherHandHasAnItem() {
        var fixture = new Fixture(SimPlayer.GameMode.SURVIVAL, true, 0, 1);
        fixture.driver = new InteractionDriver(Set.of(), fixture.stacks, fixture.cooldowns);
        assertEquals(SimInteraction.PASS, fixture.on(Hand.MAIN_HAND));
        assertTrue(fixture.calls.isEmpty());
        assertEquals(SimInteraction.SUCCESS, fixture.on(Hand.OFF_HAND));
        assertEquals(List.of("item-on"), fixture.calls);
        var empty = new Fixture(SimPlayer.GameMode.SURVIVAL, true, 0, 0);
        empty.blockResult = SimInteraction.SUCCESS.withoutItem();
        assertEquals(InteractionKind.SUCCESS, empty.on(Hand.MAIN_HAND).kind());
        assertEquals(List.of("block"), empty.calls);
        var disabled = new Fixture(SimPlayer.GameMode.SURVIVAL, false, 1, 0);
        assertFalse(DATA.registry().block(disabled.state).features().isEmpty());
        disabled.driver = new InteractionDriver(Set.of(), disabled.stacks, disabled.cooldowns);
        assertEquals(SimInteraction.FAIL, disabled.on(Hand.MAIN_HAND));
        assertTrue(disabled.calls.isEmpty());
    }

    @Test void failureFromBlockUseFallsThroughToTheItem() {
        var fixture = new Fixture(SimPlayer.GameMode.SURVIVAL, false, 1, 0);
        fixture.blockResult = SimInteraction.FAIL;
        assertEquals(SimInteraction.SUCCESS, fixture.on(Hand.MAIN_HAND));
        assertEquals(List.of("block", "item-on"), fixture.calls);
    }

    @Test void creativeRestoresTheOriginalCountAndIgnoresUseOnTransformation() {
        var fixture = new Fixture(SimPlayer.GameMode.CREATIVE, true, 3, 0);
        var original = fixture.player.hand(Hand.MAIN_HAND);
        fixture.consume = true;
        fixture.itemResult = SimInteraction.CONSUME.transformedTo(ITEMS.stack("minecraft:bucket", 1));
        fixture.on(Hand.MAIN_HAND);
        assertEquals(3, original.count());
        assertSame(original, fixture.player.hand(Hand.MAIN_HAND));
        fixture.driver.useItem(fixture.level, fixture.player, Hand.MAIN_HAND);
        assertEquals(2, original.count());
        assertSame(fixture.itemResult.transformedStack(), fixture.player.hand(Hand.MAIN_HAND));
    }

    @Test void survivalUseOnTransformsOnlySuccessfulResultsAndUseAlwaysKeepsTheCurrentHand() {
        for (SimInteraction result : List.of(SimInteraction.SUCCESS, SimInteraction.CONSUME, SimInteraction.FAIL, SimInteraction.PASS)) {
            var fixture = new Fixture(SimPlayer.GameMode.SURVIVAL, true, 1, 0);
            var original = fixture.player.hand(Hand.MAIN_HAND);
            var replacement = ITEMS.stack("minecraft:bucket", 1);
            fixture.itemResult = new SimInteraction(result.kind(), result.swing(), result.itemInteraction(), replacement);
            fixture.on(Hand.MAIN_HAND);
            assertSame(result.consumesAction() ? replacement : original, fixture.player.hand(Hand.MAIN_HAND));
        }
        var fixture = new Fixture(SimPlayer.GameMode.SURVIVAL, true, 1, 0);
        var replacement = ITEMS.stack("minecraft:bucket", 1);
        fixture.replaceHandDuringItem = replacement;
        fixture.itemResult = SimInteraction.PASS;
        assertEquals(SimInteraction.PASS, fixture.driver.useItem(fixture.level, fixture.player, Hand.MAIN_HAND));
        assertSame(replacement, fixture.player.hand(Hand.MAIN_HAND));
    }

    @Test void originalStackAndCurrentUseContextRemainDistinctAfterBlockCallbacks() {
        var fixture = new Fixture(SimPlayer.GameMode.SURVIVAL, false, 2, 0);
        var original = fixture.player.hand(Hand.MAIN_HAND);
        var replacement = ITEMS.stack("minecraft:bucket", 1);
        fixture.replaceHandDuringBlock = replacement;
        fixture.blockResult = SimInteraction.PASS;
        fixture.on(Hand.MAIN_HAND);
        assertSame(original, fixture.usedStack);
        assertSame(replacement, fixture.contextStack);
        assertSame(replacement, fixture.player.hand(Hand.MAIN_HAND));
    }

    private static final class Fixture {
        final List<String> calls = new ArrayList<>();
        final int state = DATA.registry().block("minecraft:stone").defaultState();
        final SimPlayer player;
        final SimCooldowns cooldowns = new SimCooldowns(0, Map.of());
        boolean withinBorder = true, consume;
        SimItemStack replaceHandDuringBlock, replaceHandDuringItem, usedStack, contextStack;
        SimInteraction blockResult = SimInteraction.TRY_WITH_EMPTY_HAND, emptyResult = SimInteraction.PASS, itemResult = SimInteraction.SUCCESS;
        final SimLevel level;
        final StackActions stacks = new StackActions() {
            public SimInteraction useOn(SimItemStack original, UseContext context) { calls.add("item-on"); return item(original, context); }
            public SimInteraction use(SimItemStack original, UseContext context) { calls.add("item"); return item(original, context); }
        };
        InteractionDriver driver;
        Fixture(SimPlayer.GameMode mode, boolean secondary, int mainCount, int offCount) {
            player = new SimPlayer(new SimPlayer.State(new Vec3(.5, 64, .5), 0, 0, secondary,
                mode == SimPlayer.GameMode.CREATIVE, mode != SimPlayer.GameMode.ADVENTURE, false, mode),
                ITEMS.stack("minecraft:stone", mainCount), ITEMS.stack("minecraft:stone", offCount));
            var behavior = new BlockBehavior() {
                public SimInteraction useItemOn(int state, UseContext context) {
                    calls.add("block");
                    if (replaceHandDuringBlock != null) player.hand(context.hand(), replaceHandDuringBlock);
                    return blockResult;
                }
                public SimInteraction useWithoutItem(int state, UseContext context) { calls.add("empty"); return emptyResult; }
            };
            level = new SimLevel(new SimWorldView() {
                public int stateAt(BlockPos pos) { return state; }
                public boolean isLoaded(BlockPos pos) { return true; }
                public boolean isSectionEmpty(BlockPos pos) { return false; }
                public int minY() { return -64; }
                public int height() { return 384; }
                public boolean isWithinBorder(BlockPos pos) { return withinBorder; }
                public boolean creakingActiveAt(BlockPos pos) { return false; }
                public boolean waterEvaporatesAt(BlockPos pos) { return false; }
                public BlockEntityData blockEntityAt(BlockPos pos) { return null; }
            }, DATA.registry(), ignored -> behavior);
            driver = new InteractionDriver(Set.copyOf(DATA.registry().block(state).features()), stacks, cooldowns);
        }
        SimInteraction on(Hand hand) { return driver.useItemOn(level, player, hand, HIT); }
        SimInteraction item(SimItemStack original, UseContext context) {
            usedStack = original; contextStack = context.stack();
            if (consume) original.shrink(1);
            if (replaceHandDuringItem != null) player.hand(context.hand(), replaceHandDuringItem);
            return itemResult;
        }
    }
}
