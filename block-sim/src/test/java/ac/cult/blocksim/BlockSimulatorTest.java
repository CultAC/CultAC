package ac.cult.blocksim;

import ac.cult.blocksim.behavior.*;
import ac.cult.blocksim.data.*;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Boundary contracts: predictions may be repeated without changing their inputs,
 * and rollback must retain cascade positions in client completion order. */
class BlockSimulatorTest {
    private static final DataTables DATA;
    private static final ItemRegistry ITEMS;
    private static final BlockSimulator SIM;
    private static final BlockPos GROUND = new BlockPos(0, 64, 0), TOP = GROUND.relative(Direction.UP);
    private static final MiningSpeed.State MINING = new MiningSpeed.State(true, false, 0, 1, .2, -1, -1, -1);
    static {
        try {
            DATA = DataTables.load("26.3"); ITEMS = new ItemRegistry(DATA);
            var templates = new ItemTemplates(ITEMS);
            ItemBehavior unused = new ItemBehavior() {
                public SimInteraction useOn(UseContext context) { return SimInteraction.PASS; }
                public SimInteraction use(UseContext context) { return SimInteraction.PASS; }
            };
            SIM = new BlockSimulator(DATA, new ItemBehaviorRegistry(DATA, unused, (context, state, shape) -> true,
                new ac.cult.blocksim.entity.BlockEntityComponentApplication(templates)), templates);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private int state(String key) { return DATA.registry().block("minecraft:" + key).defaultState(); }
    private SimPlayer player(String key) {
        return new SimPlayer(new SimPlayer.State(new Vec3(.5, 65, .5), 0, 0, false, false, true, false,
            SimPlayer.GameMode.SURVIVAL), ITEMS.stack("minecraft:" + key, 2), ITEMS.empty());
    }
    private SimWorldView world(boolean loadedNeighbors) {
        return world(loadedNeighbors, state("grass_block"));
    }
    private SimWorldView world(boolean loadedNeighbors, int root) {
        return new SimWorldView() {
            public int stateAt(BlockPos pos) { return pos.equals(GROUND) ? root : state("air"); }
            public boolean isLoaded(BlockPos pos) { return loadedNeighbors || pos.x() >= 0; }
            public boolean isSectionEmpty(BlockPos pos) { return pos.y() >> 4 != GROUND.y() >> 4; }
            public int minY() { return -64; }
            public int height() { return 384; }
            public boolean isWithinBorder(BlockPos pos) { return true; }
            public boolean creakingActiveAt(BlockPos pos) { return false; }
            public boolean waterEvaporatesAt(BlockPos pos) { return false; }
            public BlockEntityData blockEntityAt(BlockPos pos) { return null; }
        };
    }
    @Test void sharedFactoryUsesReceivedDefaultsForReplacementBucketsAndPottedItems() throws Exception {
        var marker = com.google.gson.JsonParser.parseString("\"Client registry default\"");
        var items = new ItemRegistry(DATA, key -> ITEMS.defaults(key).with("minecraft:custom_name", marker));
        var simulator = BlockSimulator.create(DATA, items, InteractionRegistries.load("26.3"), (context, state, shape) -> true,
            new ConsumableBehavior(context -> { throw new AssertionError("These actions do not consume food"); }));
        var action = new SimAction.UseOn(Hand.MAIN_HAND, new BlockHit(GROUND, Direction.UP, new Vec3(.5, 65, .5), false));
        var owner = player("powder_snow_bucket");
        var result = simulator.simulate(action, new SimInput(world(true), owner, new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(items.empty())));
        assertNull(result.decline()); assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
        assertEquals("minecraft:bucket", result.player().hand(Hand.MAIN_HAND).itemKey());
        assertEquals(marker, result.player().hand(Hand.MAIN_HAND).components().get("minecraft:custom_name"));
        owner = player("air");
        result = simulator.simulate(action, new SimInput(world(true, state("potted_poppy")), owner, new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(items.empty())));
        assertNull(result.decline()); assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
        assertEquals("minecraft:poppy", result.player().hand(Hand.MAIN_HAND).itemKey());
        assertEquals(marker, result.player().hand(Hand.MAIN_HAND).components().get("minecraft:custom_name"));
    }
    @Test void repeatedPlacementPreservesInputsAndCascadeRollbackOrder() {
        var player = player("snow_block");
        var cooldown = new com.google.gson.JsonObject(); cooldown.addProperty("seconds", 1);
        var stack = player.hand(Hand.MAIN_HAND);
        stack.components(stack.components().with("minecraft:use_cooldown", cooldown));
        var input = new SimInput(world(true), player, new SimCooldowns(20, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty()));
        var action = new SimAction.UseOn(Hand.MAIN_HAND, new BlockHit(GROUND, Direction.UP, new Vec3(.5, 65, .5), false));
        var first = SIM.simulate(action, input); var second = SIM.simulate(action, input);
        assertNull(first.decline()); assertEquals(InteractionKind.SUCCESS, first.interaction().kind());
        assertEquals(first.writes(), second.writes());
        assertEquals(java.util.List.of(TOP, GROUND), first.writes().stream().map(BlockWrite::pos).toList());
        assertEquals(java.util.List.of(GROUND, TOP), new ArrayList<>(first.retained().keySet()));
        assertEquals(2, stack.count()); assertEquals(1, first.player().hand(Hand.MAIN_HAND).count());
        assertFalse(input.cooldowns().isOnCooldown(stack)); assertTrue(first.cooldowns().isOnCooldown(stack));
    }
    @Test void breakingSnapshotKeepsSelectedStackAliasInsideTheFork() {
        var player = player("diamond_pickaxe"); var original = player.hand(Hand.MAIN_HAND);
        var breaking = new BreakSession.State(true, GROUND, Direction.UP, original, 0, 0, 0);
        var result = SIM.simulate(new SimAction.ContinueBreak(GROUND, Direction.NORTH),
            new SimInput(world(true), player, new SimCooldowns(0, Map.of()), MINING, breaking));
        assertNull(result.decline()); assertNotSame(original, result.breaking().item());
        assertSame(result.player().hand(Hand.MAIN_HAND), result.breaking().item());
        assertEquals(0, breaking.progress()); assertTrue(result.breaking().progress() > 0);
    }
    @Test void receivedFeatureReplacementControlsBlockAndItemUseWithoutChangingSharedDefaults() {
        var noFeatures = DATA.withEnabledFeatures(java.util.Set.of());
        var disabled = BlockSimulator.create(noFeatures, ITEMS, InteractionRegistries.defaults(), (context, state, shape) -> true,
            new ConsumableBehavior(context -> { throw new AssertionError("Not a consumable"); }));
        var action = new SimAction.UseOn(Hand.MAIN_HAND, new BlockHit(GROUND, Direction.UP, new Vec3(.5, 65, .5), false));
        var input = new SimInput(world(true), player("snow_block"), new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty()));
        var result = disabled.simulate(action, input);
        assertEquals(InteractionKind.FAIL, result.interaction().kind()); assertTrue(result.writes().isEmpty());
        assertEquals(2, result.player().hand(Hand.MAIN_HAND).count());
        assertEquals(InteractionKind.SUCCESS, SIM.simulate(action, input).interaction().kind());
        assertTrue(DATA.enabledFeatures().contains("minecraft:vanilla"));
    }
    @Test void ordinaryPlacementBedAndVegetationReturnClientWritesAndConsumption() {
        var simulator = BlockSimulator.create(DATA, ITEMS, InteractionRegistries.defaults(),
            (context, state, shape) -> true, new ConsumableBehavior(context -> {}));
        var action = new SimAction.UseOn(Hand.MAIN_HAND,
            new BlockHit(GROUND, Direction.UP, new Vec3(.5, 65, .5), false));
        var owner = player("dirt"); owner.hand(Hand.MAIN_HAND).count(3);
        var input = new SimInput(world(true, state("stone")), owner, new SimCooldowns(0, Map.of()),
            null, BreakSession.State.initial(ITEMS.empty()));
        var result = simulator.simulate(action, input);
        assertTrue(result.interaction().consumesAction());
        assertEquals(java.util.List.of(TOP), result.writes().stream().map(BlockWrite::pos).toList());
        assertEquals(state("dirt"), result.writes().getFirst().newState());
        assertEquals(2, result.player().hand(Hand.MAIN_HAND).count());
        assertEquals(3, owner.hand(Hand.MAIN_HAND).count());
        assertEquals(state("air"), input.world().stateAt(TOP));
        for (String item : java.util.List.of("white_bed", "sunflower")) {
            result = simulator.simulate(action, new SimInput(world(true), player(item),
                new SimCooldowns(0, Map.of()), null, BreakSession.State.initial(ITEMS.empty())));
            assertTrue(result.interaction().consumesAction(), item);
            assertEquals(2, result.writes().size(), item);
            assertEquals(TOP, result.writes().getFirst().pos());
            assertEquals(item.equals("white_bed") ? "foot" : "lower",
                DATA.registry().value(result.writes().getFirst().newState(), item.equals("white_bed") ? "part" : "half"));
            assertEquals(item.equals("white_bed") ? "head" : "upper",
                DATA.registry().value(result.writes().getLast().newState(), item.equals("white_bed") ? "part" : "half"));
            assertEquals(1, result.player().hand(Hand.MAIN_HAND).count());
        }
    }
    @Test void shovelComponentReplacementAndCakeReturnOnlyTheirClientChanges() {
        var simulator = BlockSimulator.create(DATA, ITEMS, InteractionRegistries.defaults(),
            (context, state, shape) -> true, new ConsumableBehavior(context -> {}));
        var action = new SimAction.UseOn(Hand.MAIN_HAND,
            new BlockHit(GROUND, Direction.UP, new Vec3(.5, 65, .5), false));
        for (int variant = 0; variant < 3; variant++) {
            var owner = player(variant == 2 ? "stick" : "iron_shovel");
            var stack = owner.hand(Hand.MAIN_HAND); stack.count(1);
            if (variant == 1) stack.removeComponent("minecraft:block_transformer");
            if (variant == 2) stack.components(stack.components().with("minecraft:block_transformer",
                new com.google.gson.JsonPrimitive("minecraft:shovel")));
            var result = simulator.simulate(action, new SimInput(world(true), owner,
                new SimCooldowns(0, Map.of()), null, BreakSession.State.initial(ITEMS.empty())));
            assertEquals(variant != 1, result.interaction().consumesAction());
            assertEquals(variant == 1 ? 0 : 1, result.writes().size());
            if (variant != 1) assertEquals(state("dirt_path"), result.writes().getFirst().newState());
        }
        var owner = new SimPlayer(new SimPlayer.State(new Vec3(.5, 65, .5), 0, 0, false, false, true, false,
            SimPlayer.GameMode.SURVIVAL, false, 12, 5), ITEMS.empty(), ITEMS.empty());
        var result = simulator.simulate(action, new SimInput(world(true, state("cake")), owner,
            new SimCooldowns(0, Map.of()), null, BreakSession.State.initial(ITEMS.empty())));
        assertTrue(result.interaction().consumesAction());
        assertEquals(14, result.player().foodLevel()); assertEquals(12, owner.foodLevel());
        assertEquals(DATA.registry().with(state("cake"), "bites", "1"), result.writes().getFirst().newState());
        assertTrue(result.player().hand(Hand.MAIN_HAND).isEmpty());
    }
    @Test void resolvedDestructionUsesTheSameBreakDriverWithoutInventingMiningProgress() {
        var owner = player("diamond_pickaxe");
        var breaking = BreakSession.State.initial(ITEMS.empty());
        var result = SIM.simulate(new SimAction.DestroyBlock(GROUND), new SimInput(world(true), owner,
            new SimCooldowns(0, Map.of()), null, breaking));
        assertTrue(result.accepted()); assertNull(result.interaction());
        assertTrue(result.writes().stream().anyMatch(write -> write.pos().equals(GROUND) && write.newState() == state("air")));
        assertTrue(result.breakActions().isEmpty());
        assertEquals(breaking.progress(), result.breaking().progress());
        var denied = new SimPlayer(new SimPlayer.State(owner.state().position(), 0, 0, false, false, false, false,
            SimPlayer.GameMode.ADVENTURE), owner.hand(Hand.MAIN_HAND), ITEMS.empty());
        result = SIM.simulate(new SimAction.DestroyBlock(GROUND), new SimInput(world(true), denied,
            new SimCooldowns(0, Map.of()), MINING, breaking));
        assertFalse(result.accepted()); assertTrue(result.writes().isEmpty());
    }
    @Test void unloadedCascadeReturnsNoPartialPrediction() {
        var player = player("snow_block");
        var input = new SimInput(world(false), player, new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty()));
        var result = SIM.simulate(new SimAction.UseOn(Hand.MAIN_HAND,
            new BlockHit(GROUND, Direction.UP, new Vec3(.5, 65, .5), false)), input);
        assertEquals(Decline.UNLOADED, result.decline()); assertFalse(result.accepted());
        assertTrue(result.writes().isEmpty()); assertTrue(result.retained().isEmpty());
        assertSame(player, result.player()); assertEquals(2, player.hand(Hand.MAIN_HAND).count());
    }
}
