package ac.cult.blocksim;

import ac.cult.blocksim.behavior.ConsumableBehavior;
import ac.cult.blocksim.data.*;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientItemActionsTest {
    private static final DataTables DATA;
    private static final ItemRegistry ITEMS;
    private static final BlockSimulator SIM;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    private static final MiningSpeed.State MINING = new MiningSpeed.State(true, false, 0, 1, .2, -1, -1, -1);
    static {
        try {
            DATA = DataTables.load("26.3"); ITEMS = new ItemRegistry(DATA);
            SIM = BlockSimulator.create(DATA, ITEMS, InteractionRegistries.load("26.3"), (context, state, shape) -> true,
                new ConsumableBehavior(context -> { throw new AssertionError("No consumable is being used"); }));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static int state(String key) { return DATA.registry().block("minecraft:" + key).defaultState(); }
    private static SimPlayer player(String item, int count, boolean creative) {
        return new SimPlayer(new SimPlayer.State(new Vec3(.5, 65, .5), 0, 0, true, creative, true, true,
            creative ? SimPlayer.GameMode.CREATIVE : SimPlayer.GameMode.SURVIVAL), ITEMS.stack("minecraft:" + item, count), ITEMS.empty());
    }

    private static SimPlayer tridentFacts(SimPlayer player, boolean wet, float mainStrength, float offStrength) {
        return new SimPlayer(player.state(), player.inventory(), player.activeUse(), null, null, null,
                new SimPlayer.TridentUse(wet, mainStrength, offStrength));
    }

    @Test void tridentChargingKeepsInventoryAndRefusesTheLastDurabilityPoint() {
        for (boolean creative : new boolean[]{false, true}) for (int damage : new int[]{0, 248, 249, 250}) {
            var owner = tridentFacts(player("trident", 1, creative), true, 1.5F, 0.0F);
            owner.hand(Hand.MAIN_HAND).damage(damage);
            var result = useAir(owner);
            assertEquals(damage < 249 ? InteractionKind.CONSUME : InteractionKind.FAIL, result.interaction().kind());
            assertEquals(damage < 249, result.player().activeUse() != null);
            if (damage < 249) assertEquals(72000, result.player().activeUse().remainingTicks());
            assertTrue(result.writes().isEmpty());
            assertTrue(owner.hand(Hand.MAIN_HAND).matches(result.player().hand(Hand.MAIN_HAND)));
            assertNull(owner.activeUse());
        }
        var owner = tridentFacts(player("trident", 1, false), true, 1.5F, 0.0F);
        owner.hand(Hand.MAIN_HAND).damage(250);
        owner.hand(Hand.MAIN_HAND).setComponent("minecraft:unbreakable", new NbtValue.Compound(Map.of()));
        assertEquals(InteractionKind.CONSUME, useAir(owner).interaction().kind());
    }

    @Test void tridentChargingReadsTheUsedHandsFoldedEffectAndWetState() {
        for (boolean wet : new boolean[]{false, true}) for (float strength : new float[]{0.0F, -2.0F, 1.5F}) {
            var owner = tridentFacts(player("trident", 1, false), wet, strength, strength);
            owner.hand(Hand.OFF_HAND, ITEMS.stack("minecraft:trident", 1));
            for (Hand hand : Hand.values()) {
                var result = SIM.simulate(new SimAction.Use(hand), new SimInput(new World(state("air")), owner,
                        new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty())));
                assertEquals(strength > 0.0F && !wet ? InteractionKind.FAIL : InteractionKind.CONSUME, result.interaction().kind());
                if (result.player().activeUse() != null) assertEquals(hand, result.player().activeUse().hand());
                assertNull(owner.activeUse());
            }
        }
        var owner = tridentFacts(player("trident", 1, false), false, 0.0F, 1.5F);
        owner.hand(Hand.OFF_HAND, ITEMS.stack("minecraft:trident", 1));
        assertEquals(InteractionKind.CONSUME, useAir(owner).interaction().kind());
        var offhand = SIM.simulate(new SimAction.Use(Hand.OFF_HAND), new SimInput(new World(state("air")), owner,
                new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty())));
        assertEquals(InteractionKind.FAIL, offhand.interaction().kind());
    }

    @Test void receivedNumericCodecBooleansControlEquipmentFoodAndCreativeBreaking() {
        // Registry NBT supplies numeric values for Codec.BOOL; nonzero values mean true.
        for (double value : new double[]{0, 1, -1, .5}) {
            boolean enabled = value != 0;
            var owner = player("diamond_helmet", 1, false);
            var stack = owner.hand(Hand.MAIN_HAND);
            var equipment = stack.components().get("minecraft:equippable").getAsJsonObject();
            equipment.addProperty("swappable", value);
            stack.components(stack.components().with("minecraft:equippable", equipment));
            var result = useAir(new World(state("stone")), owner);
            assertEquals(enabled ? InteractionKind.SUCCESS : InteractionKind.PASS, result.interaction().kind());
            assertEquals(enabled ? "minecraft:diamond_helmet" : "minecraft:air", result.player().inventory().get(39).itemKey());
            assertEquals("minecraft:diamond_helmet", owner.hand(Hand.MAIN_HAND).itemKey());

            owner = player("stone", 1, false);
            stack = owner.hand(Hand.MAIN_HAND);
            var food = JsonParser.parseString("{\"nutrition\":1,\"saturation\":0}").getAsJsonObject();
            food.addProperty("can_always_eat", value);
            stack.components(stack.components().with("minecraft:food", food)
                    .with("minecraft:consumable", JsonParser.parseString("{\"consume_seconds\":0.05}")));
            result = useAir(new World(state("stone")), owner);
            assertEquals(enabled ? InteractionKind.CONSUME : InteractionKind.FAIL, result.interaction().kind());
            assertEquals(enabled, result.player().activeUse() != null);
            assertNull(owner.activeUse());

            owner = player("diamond_pickaxe", 1, true);
            stack = owner.hand(Hand.MAIN_HAND);
            var tool = stack.components().get("minecraft:tool").getAsJsonObject();
            tool.addProperty("can_destroy_blocks_in_creative", value);
            stack.components(stack.components().with("minecraft:tool", tool));
            result = SIM.simulate(new SimAction.DestroyBlock(POS), new SimInput(new World(state("stone")), owner,
                    new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty())));
            assertEquals(enabled, result.accepted());
            assertEquals(enabled, result.writes().stream().anyMatch(write -> write.pos().equals(POS) && write.newState() == state("air")));

            owner = player("diamond_pickaxe", 1, false);
            stack = owner.hand(Hand.MAIN_HAND);
            tool = JsonParser.parseString("{\"rules\":[{\"blocks\":\"minecraft:stone\",\"speed\":9}]}").getAsJsonObject();
            tool.getAsJsonArray("rules").get(0).getAsJsonObject().addProperty("correct_for_drops", value);
            stack.components(stack.components().with("minecraft:tool", tool));
            var world = new World(state("stone"));
            result = SIM.simulate(new SimAction.StartBreak(POS, Direction.UP), new SimInput(world, owner,
                    new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty())));
            int ticks = 0;
            while (result.breaking().destroying() && ticks < 20) {
                result = SIM.simulate(new SimAction.ContinueBreak(POS, Direction.UP), new SimInput(world, result.player(),
                        result.cooldowns(), MINING, result.breaking()));
                ticks++;
            }
            assertEquals(enabled ? 5 : 17, ticks);
            assertFalse(result.breaking().destroying());
            assertTrue(result.writes().stream().anyMatch(write -> write.pos().equals(POS) && write.newState() == state("air")));
        }
    }
    @Test void receivedNoiseTransformersChooseClientBlocksAndKeepTheirSnapshot() {
        for (String key : new String[]{"minecraft:flower_flower_forest", "minecraft:flower_meadow"}) {
            var provider = InteractionRegistries.defaults().resolve("block_state_provider", new com.google.gson.JsonPrimitive(key)).getAsJsonObject();
            provider.addProperty("scale", .005F);
            var rules = JsonParser.parseString("[{\"block_state_provider\":\"test:noise\",\"update_from_neighbors\":false}]");
            var received = InteractionRegistries.defaults().withRegistry("block_state_provider", Map.of("test:noise", provider))
                    .withRegistry("block_transformer", Map.of("test:transform", rules));
            var simulator = BlockSimulator.create(DATA, ITEMS, received, (context, state, shape) -> true,
                    new ConsumableBehavior(context -> { throw new AssertionError("Not a consumable"); }));
            provider.add("states", JsonParser.parseString("[\"minecraft:gold_block\"]"));
            rules.getAsJsonArray().get(0).getAsJsonObject().addProperty("block_state_provider", "test:missing");
            var owner = player("stick", 3, false);
            var stack = owner.hand(Hand.MAIN_HAND);
            stack.components(stack.components().with("minecraft:block_transformer", new com.google.gson.JsonPrimitive("test:transform")));
            var world = new World(state("stone"));
            world.states.put(POS.relative(Direction.DOWN), state("dirt"));
            var result = simulator.simulate(new SimAction.UseOn(Hand.MAIN_HAND,
                    new BlockHit(POS, Direction.UP, new Vec3(.5, 64.5, .5), false)), new SimInput(world, owner,
                    new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty())));
            assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
            assertTrue(result.writes().stream().anyMatch(write -> write.pos().equals(POS)
                    && write.newState() == state(key.endsWith("forest") ? "allium" : "dandelion")));
            assertEquals(2, result.player().hand(Hand.MAIN_HAND).count());
            assertEquals(3, owner.hand(Hand.MAIN_HAND).count());
            assertEquals(state("stone"), world.stateAt(POS));
        }
    }
    @Test void adventurePredicatesRequireEveryReceivedMatcherAndPreserveTypedNbt() {
        var state = new NbtValue.Compound(Map.of("axis", new NbtValue.Text("y")));
        var predicate = new NbtValue.Compound(Map.of("state", state));
        var value = new NbtValue.Compound(Map.of("minecraft:can_place_on", new NbtValue.Sequence(java.util.List.of(predicate))));
        var matchers = new NbtValue.Sequence(java.util.List.of(
                new NbtValue.Compound(Map.of("name", new NbtValue.Text("axis"), "value", new NbtValue.Text("x"))),
                new NbtValue.Compound(Map.of("name", new NbtValue.Text("axis"), "value", new NbtValue.Text("y")))));
        var layout = new NbtValue.Compound(Map.of("minecraft:can_place_on", new NbtValue.Compound(Map.of(
                "states", new NbtValue.Compound(Map.of("0", matchers))))));
        var stack = ITEMS.stack("minecraft:stone", 1, ComponentPatch.fromNbt(value, layout));
        var matcher = new AdventurePredicates(DATA);
        var world = new World(state("oak_log"));
        var level = new SimLevel(world, DATA.registry(), new ac.cult.blocksim.behavior.BehaviorRegistry(DATA));
        assertFalse(matcher.test(stack, "minecraft:can_place_on", level, POS));

        var expected = new NbtValue.Compound(Map.of("test", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte)7)));
        var nbtPatch = new NbtValue.Compound(Map.of("minecraft:can_place_on", new NbtValue.Sequence(java.util.List.of(
                new NbtValue.Compound(Map.of("nbt", expected))))));
        stack = ITEMS.stack("minecraft:stone", 1, ComponentPatch.fromNbt(nbtPatch));
        world = new World(state("chest"));
        world.entities.put(POS, new BlockEntityData("minecraft:chest", Components.EMPTY, expected));
        level = new SimLevel(world, DATA.registry(), new ac.cult.blocksim.behavior.BehaviorRegistry(DATA));
        assertTrue(matcher.test(stack, "minecraft:can_place_on", level, POS));
        var authored = ITEMS.stack("minecraft:stone", 1, ComponentPatch.fromJson(JsonParser.parseString(
                "{\"minecraft:can_place_on\":{\"nbt\":{\"test\":7}}}").getAsJsonObject()));
        assertTrue(matcher.test(authored, "minecraft:can_place_on", level, POS));
        assertTrue(stack.sameItemSameComponents(authored));
        world.entities.put(POS, new BlockEntityData("minecraft:chest", Components.EMPTY,
                new NbtValue.Compound(Map.of("test", new NbtValue.Numeric(NbtValue.Kind.INT, 7)))));
        assertFalse(matcher.test(stack, "minecraft:can_place_on", level, POS));
    }

    private static final class World implements SimWorldView {
        private final Map<BlockPos, Integer> states = new HashMap<>();
        private int brightness = 15;
        private boolean entityHit;
        private boolean occupied, colliding, peaceful;
        private boolean experimentalMinecarts;
        private Box minecartQuery;
        private final Map<BlockPos, BlockEntityData> entities = new HashMap<>();
        World(int state) { states.put(POS, state); }
        public int stateAt(BlockPos pos) { return states.getOrDefault(pos, state("air")); }
        public boolean isLoaded(BlockPos pos) { return true; }
        public boolean isSectionEmpty(BlockPos pos) { return false; }
        public int minY() { return -64; }
        public int height() { return 384; }
        public boolean isWithinBorder(BlockPos pos) { return true; }
        public String dimensionKey() { return "minecraft:overworld"; }
        public boolean creakingActiveAt(BlockPos pos) { return false; }
        public boolean waterEvaporatesAt(BlockPos pos) { return false; }
        public int rawBrightnessAt(BlockPos pos) { return brightness; }
        public BlockEntityData blockEntityAt(BlockPos pos) { return entities.get(pos); }
        public Vec3 borderHit(Vec3 from, Vec3 to) { return null; }
        public boolean hasPickableEntityHit(Vec3 from, Vec3 to, SimPlayer player) { return entityHit; }
        public int itemFrameOutputAt(BlockPos pos, Direction direction) { return Integer.MIN_VALUE; }
        public int detectorRailOutputAt(BlockPos pos) { return 0; }
        public boolean hasSittingCatAt(BlockPos pos) { return false; }
        public boolean canSpawn(ac.cult.blocksim.entity.EntityTypes.Type type) { return type.canSpawn(DATA.enabledFeatures(), peaceful); }
        public boolean hasFeature(String key) { return experimentalMinecarts && key.equals("minecraft:minecart_improvements"); }
        public boolean hasMinecartIn(Box box) { minecartQuery = box; return occupied; }
        public boolean hasEntityIn(Box box) { return occupied; }
        public boolean hasEntityCollision(Box box, boolean boatSource) { return colliding; }
        public boolean hasPickableEntityAtEye(SimPlayer player) { return entityHit; }
        public boolean hasBorderCollision(Box box, Vec3 source) { return false; }
        public java.util.List<ac.cult.blocksim.entity.PaintingSize> placeablePaintings() { return paintingVariants; }
        public ac.cult.blocksim.entity.PaintingSize paintingSize(com.google.gson.JsonElement holder) { return new ac.cult.blocksim.entity.PaintingSize(
            holder.getAsJsonObject().get("width").getAsInt(), holder.getAsJsonObject().get("height").getAsInt()); }
        public boolean hasHangingEntityOverlap(Box box, Direction direction, String type, boolean allowSameType) { return occupied; }
        private java.util.List<ac.cult.blocksim.entity.PaintingSize> paintingVariants = java.util.List.of(new ac.cult.blocksim.entity.PaintingSize(1, 1));
    }
    private static SimResult use(World world, SimPlayer player, Direction face, HolderSets.Overlay tags) {
        return SIM.simulate(new SimAction.UseOn(Hand.MAIN_HAND, new BlockHit(POS, face, new Vec3(.5, 65, .5), false)),
            new SimInput(world, player, new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty()), tags));
    }
    private static SimResult use(World world, SimPlayer player) { return use(world, player, Direction.UP, HolderSets.Overlay.EMPTY); }
    @Test void mushroomPlacementChecksLightUnlessTheSupportOverridesIt() {
        for (String item : new String[]{"brown_mushroom", "red_mushroom"}) {
            var owner = player(item, 2, false);
            var world = new World(state("stone"));
            world.brightness = 12;
            var placed = use(world, owner);
            assertTrue(placed.interaction().consumesAction());
            assertEquals(state(item), placed.writes().getFirst().newState());
            assertEquals(1, placed.player().hand(Hand.MAIN_HAND).count());
            for (int light : new int[]{13, 15}) {
                world.brightness = light;
                var rejected = use(world, owner);
                assertFalse(rejected.interaction().consumesAction());
                assertTrue(rejected.writes().isEmpty());
                assertEquals(2, rejected.player().hand(Hand.MAIN_HAND).count());
            }
            world.states.put(POS, state("mycelium"));
            assertTrue(use(world, owner).interaction().consumesAction());
            world.states.put(POS, state("air"));
            world.brightness = 0;
            assertFalse(use(world, owner).interaction().consumesAction());
        }
    }

    private static SimResult useAir(SimPlayer player) {
        return useAir(new World(state("air")), player);
    }
    private static SimResult useAir(World world, SimPlayer player) {
        return SIM.simulate(new SimAction.Use(Hand.MAIN_HAND), new SimInput(world, player, new SimCooldowns(0, Map.of()),
            MINING, BreakSession.State.initial(ITEMS.empty())));
    }

    @Test void equipmentReadsReceivedEnchantmentEffectsIncludingAnOmittedEffectsMap() {
        var entries = new java.util.LinkedHashMap<String, com.google.gson.JsonElement>();
        entries.put("test:ordinary", JsonParser.parseString("{}"));
        entries.put("test:binding", JsonParser.parseString("{\"effects\":{\"minecraft:prevent_armor_change\":{}}}"));
        var received = InteractionRegistries.defaults().withRegistry("enchantment", entries);
        // Mutating the packet decoder's JSON after the boundary cannot change this snapshot.
        entries.get("test:binding").getAsJsonObject().remove("effects");
        var simulator = BlockSimulator.create(DATA, ITEMS, received, (context, state, shape) -> true,
                new ConsumableBehavior(context -> { throw new AssertionError("Not a consumable"); }));
        for (boolean creative : new boolean[]{false, true}) for (String enchantment : new String[]{"test:ordinary", "test:binding"})
                for (int level : new int[]{0, 1, 255}) {
            var owner = player("diamond_helmet", 1, creative);
            var slots = new java.util.ArrayList<>(owner.inventory().slots());
            var worn = ITEMS.stack("minecraft:iron_helmet", 1);
            var levels = new com.google.gson.JsonObject(); levels.addProperty(enchantment, level);
            worn.components(worn.components().with("minecraft:enchantments", levels));
            slots.set(39, worn);
            owner = new SimPlayer(owner.state(), new SimInventory(slots, 0, creative, ITEMS.empty()), null);
            var result = simulator.simulate(new SimAction.Use(Hand.MAIN_HAND), new SimInput(new World(state("air")), owner,
                    new SimCooldowns(0, Map.of()), MINING, BreakSession.State.initial(ITEMS.empty())));
            boolean locked = !creative && enchantment.equals("test:binding");
            assertEquals(locked ? InteractionKind.FAIL : InteractionKind.SUCCESS, result.interaction().kind());
            assertEquals(locked ? "minecraft:iron_helmet" : "minecraft:diamond_helmet", result.player().inventory().get(39).itemKey());
            assertEquals(locked ? "minecraft:diamond_helmet" : "minecraft:iron_helmet", result.player().hand(Hand.MAIN_HAND).itemKey());
            assertEquals("minecraft:iron_helmet", owner.inventory().get(39).itemKey());
            assertTrue(result.writes().isEmpty());
        }
    }

    @Test void cushionsNeedAnExposedSupportSurfaceAndDoNotPredictServerConsumption() {
        var owner = player("white_cushion", 3, false);
        var world = new World(state("stone"));
        var result = use(world, owner);
        assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
        assertEquals(3, result.player().hand(Hand.MAIN_HAND).count());
        assertTrue(result.writes().isEmpty());
        assertEquals(InteractionKind.FAIL, use(world, owner, Direction.DOWN, HolderSets.Overlay.EMPTY).interaction().kind());
        world.states.put(POS.relative(Direction.UP), state("stone"));
        assertEquals(InteractionKind.FAIL, use(world, owner).interaction().kind());
        world.states.clear();
        assertEquals(InteractionKind.FAIL, use(world, owner).interaction().kind());
    }

    @Test void boatsRejectEyeObstructionAndCollisionWithoutClientSideConsumption() {
        var owner = player("oak_boat", 2, false);
        owner = new SimPlayer(owner.state(), owner.inventory(), null, new SimPlayer.Sight(new Vec3(.5, 64.5, -2), 5));
        var world = new World(state("water"));
        var result = useAir(world, owner);
        assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
        assertEquals(2, result.player().hand(Hand.MAIN_HAND).count());
        assertTrue(result.writes().isEmpty());
        world.entityHit = true;
        assertEquals(InteractionKind.PASS, useAir(world, owner).interaction().kind());
        world.entityHit = false;
        world.colliding = true;
        assertEquals(InteractionKind.FAIL, useAir(world, owner).interaction().kind());
        world.states.clear();
        assertEquals(InteractionKind.PASS, useAir(world, owner).interaction().kind());
    }

    @Test void minecartsUseReceivedExperimentalRulesForOverlapAndInitialRailHeight() {
        var owner = player("minecart", 2, false);
        var world = new World(DATA.registry().with(state("rail"), "shape", "ascending_east"));
        world.occupied = true;
        assertEquals(1, use(world, owner).player().hand(Hand.MAIN_HAND).count());
        assertNull(world.minecartQuery);
        world.experimentalMinecarts = true;
        var blocked = use(world, owner);
        assertEquals(InteractionKind.FAIL, blocked.interaction().kind());
        assertEquals(2, blocked.player().hand(Hand.MAIN_HAND).count());
        assertEquals(64.6, world.minecartQuery.minY(), 1.0E-12);
        world.occupied = false;
        var placed = use(world, owner);
        assertEquals(InteractionKind.SUCCESS, placed.interaction().kind());
        assertEquals(1, placed.player().hand(Hand.MAIN_HAND).count());
        assertTrue(placed.writes().isEmpty());
    }

    @Test void hangingItemsRequireSupportAndConsumeOnlyAfterSuccessfulPlacement() {
        var world = new World(state("stone"));
        for (var face : Direction.values()) {
            var placed = use(world, player("item_frame", 2, false), face, HolderSets.Overlay.EMPTY);
            assertEquals(InteractionKind.SUCCESS, placed.interaction().kind());
            assertEquals(1, placed.player().hand(Hand.MAIN_HAND).count());
            assertTrue(placed.writes().isEmpty());
        }
        world.occupied = true;
        var blocked = use(world, player("glow_item_frame", 2, false));
        assertEquals(InteractionKind.CONSUME, blocked.interaction().kind());
        assertEquals(2, blocked.player().hand(Hand.MAIN_HAND).count());
        world.occupied = false;
        world.states.clear();
        assertEquals(InteractionKind.CONSUME, use(world, player("item_frame", 2, false)).interaction().kind());
        world.states.put(POS, state("repeater"));
        assertEquals(InteractionKind.SUCCESS, use(world, player("item_frame", 2, false), Direction.EAST, HolderSets.Overlay.EMPTY).interaction().kind());
        assertEquals(InteractionKind.CONSUME, use(world, player("item_frame", 2, false)).interaction().kind());
    }

    @Test void suppliedPaintingVariantStillNeedsAPlaceableCandidateAndItsOwnSupport() {
        var world = new World(state("stone"));
        var owner = player("painting", 2, false);
        var placed = use(world, owner, Direction.EAST, HolderSets.Overlay.EMPTY);
        assertEquals(InteractionKind.SUCCESS, placed.interaction().kind());
        assertEquals(1, placed.player().hand(Hand.MAIN_HAND).count());
        assertTrue(placed.writes().isEmpty());
        assertEquals(InteractionKind.FAIL, use(world, owner).interaction().kind());
        var stack = owner.hand(Hand.MAIN_HAND);
        stack.components(stack.components().with("minecraft:painting_variant", JsonParser.parseString("{\"width\":4,\"height\":4}")));
        var tooLarge = use(world, owner, Direction.EAST, HolderSets.Overlay.EMPTY);
        assertEquals(InteractionKind.CONSUME, tooLarge.interaction().kind());
        assertEquals(2, tooLarge.player().hand(Hand.MAIN_HAND).count());
        for (int y = 60; y < 70; y++) for (int z = -5; z <= 5; z++) world.states.put(new BlockPos(0, y, z), state("stone"));
        assertEquals(InteractionKind.SUCCESS, use(world, owner, Direction.EAST, HolderSets.Overlay.EMPTY).interaction().kind());
        world.paintingVariants = java.util.List.of();
        assertEquals(InteractionKind.CONSUME, use(world, owner, Direction.EAST, HolderSets.Overlay.EMPTY).interaction().kind());
    }

    @Test void enderEyesAcknowledgeFramesAndStartAirUseWithoutPredictingTheServerEye() {
        var owner = player("ender_eye", 3, false);
        var frame = new World(DATA.registry().with(state("end_portal_frame"), "eye", "false"));
        var clicked = use(frame, owner);
        assertEquals(InteractionKind.SUCCESS, clicked.interaction().kind());
        assertTrue(clicked.writes().isEmpty());
        assertEquals(3, clicked.player().hand(Hand.MAIN_HAND).count());
        frame.states.put(POS, DATA.registry().with(state("end_portal_frame"), "eye", "true"));
        assertEquals(InteractionKind.PASS, use(frame, owner).interaction().kind());
        owner = new SimPlayer(owner.state(), owner.inventory(), null, new SimPlayer.Sight(new Vec3(.5, 64.5, -2), 5));
        assertEquals(InteractionKind.PASS, useAir(frame, owner).interaction().kind());
        var thrown = useAir(owner);
        assertEquals(SimInteraction.Swing.SERVER_ONLY, thrown.interaction().swing());
        assertEquals(0, thrown.player().activeUse().remainingTicks());
        assertEquals(3, thrown.player().hand(Hand.MAIN_HAND).count());
        assertTrue(thrown.writes().isEmpty());
    }

    @Test void mapBannerTagsAndGlidingControlClientAcknowledgements() {
        var owner = player("filled_map", 1, false);
        assertEquals(InteractionKind.SUCCESS, use(new World(state("white_banner")), owner).interaction().kind());
        var changedTags = new HolderSets.Overlay(Map.of("block:minecraft:banners", Set.of("minecraft:stone")));
        assertEquals(InteractionKind.PASS, use(new World(state("white_banner")), owner, Direction.UP, changedTags).interaction().kind());
        assertEquals(InteractionKind.SUCCESS, use(new World(state("stone")), owner, Direction.UP, changedTags).interaction().kind());
        assertEquals(InteractionKind.PASS, use(new World(state("oak_fence")), player("lead", 3, false)).interaction().kind());
        for (boolean gliding : new boolean[]{false, true}) {
            var firework = player("firework_rocket", 3, false);
            firework = new SimPlayer(firework.state(), firework.inventory(), null, null, null, new SimPlayer.Movement(gliding));
            var clicked = use(new World(state("stone")), firework);
            var air = useAir(firework);
            assertEquals(gliding ? InteractionKind.PASS : InteractionKind.SUCCESS, clicked.interaction().kind());
            assertEquals(gliding ? InteractionKind.SUCCESS : InteractionKind.PASS, air.interaction().kind());
            assertEquals(3, clicked.player().hand(Hand.MAIN_HAND).count());
            assertEquals(3, air.player().hand(Hand.MAIN_HAND).count());
        }
    }

    @Test void spawnEggsRespectDifficultyAndEntityPlacementConsumesLocally() {
        var world = new World(state("stone"));
        assertEquals(InteractionKind.SUCCESS, use(world, player("creeper_spawn_egg", 3, false)).interaction().kind());
        world.peaceful = true;
        assertEquals(InteractionKind.FAIL, use(world, player("creeper_spawn_egg", 3, false)).interaction().kind());
        assertEquals(InteractionKind.SUCCESS, use(world, player("cow_spawn_egg", 3, false)).interaction().kind());
        for (boolean creative : new boolean[]{false, true}) {
            var stand = use(world, player("armor_stand", 3, creative));
            assertEquals(InteractionKind.SUCCESS, stand.interaction().kind());
            assertEquals(creative ? 3 : 2, stand.player().hand(Hand.MAIN_HAND).count());
            world.occupied = true;
            assertEquals(InteractionKind.FAIL, use(world, player("armor_stand", 3, creative)).interaction().kind());
            world.occupied = false;
        }
        var crystalWorld = new World(state("obsidian"));
        var crystal = use(crystalWorld, player("end_crystal", 3, false));
        assertEquals(InteractionKind.SUCCESS, crystal.interaction().kind());
        assertEquals(2, crystal.player().hand(Hand.MAIN_HAND).count());
        assertTrue(crystal.writes().isEmpty());
        crystalWorld.occupied = true;
        assertEquals(InteractionKind.FAIL, use(crystalWorld, player("end_crystal", 3, false)).interaction().kind());
    }

    @Test void bottlesFillFromReceivedWaterAndKeepCreativeInventoryRules() {
        for (boolean creative : new boolean[]{false, true}) for (int count : new int[]{1, 3}) {
            var owner = player("glass_bottle", count, creative);
            owner = new SimPlayer(owner.state(), owner.inventory(), null, new SimPlayer.Sight(new Vec3(.5, 64.5, -2), 5));
            var result = useAir(new World(state("water")), owner);
            assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
            var held = result.player().hand(Hand.MAIN_HAND);
            assertEquals(!creative && count == 1 ? "minecraft:potion" : "minecraft:glass_bottle", held.itemKey());
            assertEquals(creative ? count : count == 1 ? 1 : count - 1, held.count());
            if (creative || count > 1) assertEquals("minecraft:potion", result.player().inventory().get(1).itemKey());
            assertTrue(result.writes().isEmpty());
        }
        var owner = player("glass_bottle", 1, false);
        owner = new SimPlayer(owner.state(), owner.inventory(), null, new SimPlayer.Sight(new Vec3(.5, 64.5, -2), 5));
        assertEquals(InteractionKind.PASS, useAir(new World(state("lava")), owner).interaction().kind());
    }

    @Test void thrownItemsConsumeOnlyInSurvivalAndKeepComponentCooldowns() {
        for (boolean creative : new boolean[]{false, true}) for (String item : new String[]{"egg", "ender_pearl", "snowball", "experience_bottle", "wind_charge", "splash_potion", "lingering_potion"}) {
            var result = useAir(player(item, 3, creative));
            assertEquals(InteractionKind.SUCCESS, result.interaction().kind(), item);
            assertEquals(creative ? 3 : 2, result.player().hand(Hand.MAIN_HAND).count(), item);
            assertNull(result.player().activeUse(), item);
        }
        var pearl = useAir(player("ender_pearl", 3, false));
        assertTrue(pearl.cooldowns().isOnCooldown(pearl.player().hand(Hand.MAIN_HAND)));
    }

    @Test void rangedWeaponsRequireAmmunitionAndCrossbowFireworksMustBeHeld() {
        for (String weapon : new String[]{"bow", "crossbow"}) {
            assertEquals(InteractionKind.FAIL, useAir(player(weapon, 1, false)).interaction().kind());
            assertEquals(72000, useAir(player(weapon, 1, true)).player().activeUse().remainingTicks());
            var owner = player(weapon, 1, false);
            owner.inventory().set(9, ITEMS.stack("minecraft:arrow", 3));
            assertEquals(72000, useAir(owner).player().activeUse().remainingTicks());
            owner.inventory().set(9, ITEMS.stack("minecraft:firework_rocket", 3));
            assertEquals(InteractionKind.FAIL, useAir(owner).interaction().kind());
            owner.hand(Hand.OFF_HAND, ITEMS.stack("minecraft:firework_rocket", 1));
            assertEquals(weapon.equals("crossbow") ? InteractionKind.CONSUME : InteractionKind.FAIL, useAir(owner).interaction().kind());
        }
        var charged = player("crossbow", 1, false);
        charged.hand(Hand.MAIN_HAND).components(charged.hand(Hand.MAIN_HAND).components().with("minecraft:charged_projectiles", JsonParser.parseString("[{\"id\":\"minecraft:arrow\"}]")));
        var fired = useAir(charged);
        assertEquals(InteractionKind.CONSUME, fired.interaction().kind());
        assertNull(fired.player().activeUse());
        assertEquals(charged.hand(Hand.MAIN_HAND).components(), fired.player().hand(Hand.MAIN_HAND).components());
    }

    @Test void bundleAndSpyglassStartUseAndEmptyKnowledgeBooksStillConsumeOnFailure() {
        assertEquals(200, useAir(player("bundle", 1, false)).player().activeUse().remainingTicks());
        assertEquals(1200, useAir(player("spyglass", 1, false)).player().activeUse().remainingTicks());
        var book = useAir(player("knowledge_book", 3, false));
        assertEquals(InteractionKind.FAIL, book.interaction().kind());
        assertEquals(2, book.player().hand(Hand.MAIN_HAND).count());
        assertEquals(InteractionKind.SUCCESS, useAir(player("written_book", 1, false)).interaction().kind());
        assertEquals(InteractionKind.PASS, useAir(player("carrot_on_a_stick", 1, false)).interaction().kind());
    }

    @Test void instrumentsReadTheirHolderDurationAndAddTheLocalCooldown() {
        var owner = player("goat_horn", 1, false);
        owner.hand(Hand.MAIN_HAND).components(owner.hand(Hand.MAIN_HAND).components()
            .with("minecraft:instrument", JsonParser.parseString("\"minecraft:ponder_goat_horn\"")));
        var result = useAir(owner);
        assertEquals(InteractionKind.CONSUME, result.interaction().kind());
        assertEquals(140, result.player().activeUse().remainingTicks());
        assertTrue(result.cooldowns().isOnCooldown(result.player().hand(Hand.MAIN_HAND)));
        owner.hand(Hand.MAIN_HAND).components(owner.hand(Hand.MAIN_HAND).components().with("minecraft:instrument", null));
        assertEquals(InteractionKind.FAIL, useAir(owner).interaction().kind());
    }

    @Test void boneMealAcknowledgesClientTargetsWithoutPredictingServerGrowthOrConsumption() {
        for (String plant : new String[]{"wheat", "beetroots", "bamboo_sapling", "big_dripleaf", "cave_vines", "cocoa", "short_dry_grass"}) {
            var owner = player("bone_meal", 3, false);
            var result = use(new World(state(plant)), owner);
            assertEquals(InteractionKind.SUCCESS, result.interaction().kind(), plant);
            assertTrue(result.writes().isEmpty(), plant);
            assertEquals(3, result.player().hand(Hand.MAIN_HAND).count(), plant);
        }
        for (String plant : new String[]{"oak_sapling", "azalea", "red_mushroom"})
            assertEquals(InteractionKind.PASS, use(new World(state(plant)), player("bone_meal", 1, false)).interaction().kind(), plant);
        int grown = DATA.registry().with(state("wheat"), "age", "7");
        assertEquals(InteractionKind.PASS, use(new World(grown), player("bone_meal", 1, false)).interaction().kind());
        int torchflower = DATA.registry().with(state("torchflower_crop"), "age", "1");
        assertEquals(InteractionKind.SUCCESS, use(new World(torchflower), player("bone_meal", 1, false)).interaction().kind());
    }

    @Test void boneMealRespectsConnectedHeadsLightAndWaterSource() {
        var owner = player("bone_meal", 2, false);
        var kelp = new World(state("kelp_plant"));
        assertEquals(InteractionKind.PASS, use(kelp, owner).interaction().kind());
        kelp.states.put(POS.relative(Direction.UP), state("kelp"));
        kelp.states.put(POS.relative(Direction.UP, 2), state("water"));
        assertEquals(InteractionKind.SUCCESS, use(kelp, owner).interaction().kind());

        int lower = DATA.registry().with(state("pitcher_crop"), "half", "lower");
        var pitcher = new World(lower);
        pitcher.brightness = 7;
        assertEquals(InteractionKind.PASS, use(pitcher, owner).interaction().kind());
        pitcher.brightness = 8;
        assertEquals(InteractionKind.SUCCESS, use(pitcher, owner).interaction().kind());
        pitcher.states.put(POS, DATA.registry().with(lower, "age", "2"));
        pitcher.states.put(POS.relative(Direction.UP), state("stone"));
        assertEquals(InteractionKind.PASS, use(pitcher, owner).interaction().kind());

        var shore = new World(state("stone"));
        shore.states.put(POS.relative(Direction.UP), state("water"));
        assertEquals(InteractionKind.SUCCESS, use(shore, owner).interaction().kind());
        shore.states.put(POS.relative(Direction.UP), DATA.registry().with(state("water"), "level", "1"));
        assertEquals(InteractionKind.PASS, use(shore, owner).interaction().kind());
    }

    @Test void shearsStopPlantGrowthWithoutPredictingServerDurability() {
        int kelp = DATA.registry().with(state("kelp"), "age", "3");
        var owner = player("shears", 1, false);
        var result = use(new World(kelp), owner);
        assertNull(result.decline()); assertTrue(result.interaction().consumesAction());
        assertEquals(DATA.registry().with(kelp, "age", "25"), result.writes().getFirst().newState());
        assertEquals(owner.hand(Hand.MAIN_HAND).patch(), result.player().hand(Hand.MAIN_HAND).patch());
        assertTrue(use(new World(DATA.registry().with(kelp, "age", "25")), owner).writes().isEmpty());
    }

    @Test void compassReplacesOrSplitsInSurvivalAndKeepsTheCreativeHand() {
        for (boolean creative : new boolean[]{false, true}) for (int count : new int[]{1, 3}) {
            var owner = player("compass", count, creative);
            var result = use(new World(state("lodestone")), owner);
            assertNull(result.decline()); assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
            assertTrue(result.writes().isEmpty()); assertEquals(count, owner.hand(Hand.MAIN_HAND).count());
            var held = result.player().hand(Hand.MAIN_HAND);
            var tracked = !creative && count == 1 ? held : result.player().inventory().slots().stream()
                .filter(stack -> stack.components().has("minecraft:lodestone_tracker")).findFirst().orElseThrow();
            assertEquals(!creative && count == 1 ? 1 : creative ? count : count - 1, held.count());
            assertEquals(!creative && count == 1, held.components().has("minecraft:lodestone_tracker"));
            var target = tracked.components().get("minecraft:lodestone_tracker").getAsJsonObject().getAsJsonObject("target");
            assertEquals("minecraft:overworld", target.get("dimension").getAsString());
            assertEquals(JsonParser.parseString("[0,64,0]"), target.get("pos"));
            var nbt = (NbtValue.Compound) tracked.components().encodedNbt("minecraft:lodestone_tracker");
            var encodedTarget = (NbtValue.Compound) nbt.values().get("target");
            assertEquals(NbtValue.Kind.INT_ARRAY, ((NbtValue.PrimitiveArray) encodedTarget.values().get("pos")).kind());
        }
    }

    @Test void waterBottleMakesMudAndReturnsItsBottleButCustomEffectsAndDownFaceDoNot() {
        var owner = player("potion", 1, false);
        owner.hand(Hand.MAIN_HAND).components(owner.hand(Hand.MAIN_HAND).components()
            .with("minecraft:potion_contents", JsonParser.parseString("{\"potion\":\"minecraft:water\"}")));
        var result = use(new World(state("dirt")), owner);
        assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
        assertEquals(state("mud"), result.writes().getFirst().newState());
        assertEquals("minecraft:glass_bottle", result.player().hand(Hand.MAIN_HAND).itemKey());
        assertTrue(use(new World(state("dirt")), owner, Direction.DOWN, HolderSets.Overlay.EMPTY).writes().isEmpty());
        owner.hand(Hand.MAIN_HAND).components(owner.hand(Hand.MAIN_HAND).components().with("minecraft:potion_contents",
            JsonParser.parseString("{\"potion\":\"minecraft:water\",\"custom_effects\":[{\"id\":\"minecraft:speed\"}]}")));
        assertTrue(use(new World(state("dirt")), owner).writes().isEmpty());
    }

    @Test void receivedTagsReplaceDefaultsWithoutAffectingOtherConnections() {
        var owner = player("potion", 1, false);
        owner.hand(Hand.MAIN_HAND).components(owner.hand(Hand.MAIN_HAND).components()
            .with("minecraft:potion_contents", JsonParser.parseString("{\"potion\":\"minecraft:water\"}")));
        var changed = HolderSets.Overlay.differingFrom(DATA, Map.of("block:minecraft:convertible_to_mud", Set.of("minecraft:stone")));
        assertTrue(use(new World(state("dirt")), owner, Direction.UP, changed).writes().isEmpty());
        assertEquals(state("mud"), use(new World(state("stone")), owner, Direction.UP, changed).writes().getFirst().newState());
        assertEquals(state("mud"), use(new World(state("dirt")), owner).writes().getFirst().newState());
        var cleared = new HolderSets.Overlay(Map.of("block:minecraft:convertible_to_mud", Set.of()));
        assertTrue(use(new World(state("dirt")), owner, Direction.UP, cleared).writes().isEmpty());
        assertTrue(use(new World(state("stone")), owner).writes().isEmpty());
    }

    @Test void debugStickAcknowledgesUseButDoesNotPredictTheServerPropertyCycle() {
        var result = use(new World(state("oak_stairs")), player("debug_stick", 1, false));
        assertEquals(InteractionKind.SUCCESS, result.interaction().kind()); assertTrue(result.writes().isEmpty());
    }

    @Test void brushUsesCollisionRayAndDoesNotStartBehindAnEntityOrOnANoncollidingOutline() {
        var base = player("brush", 1, false);
        var owner = new SimPlayer(base.state(), base.inventory(), null, new SimPlayer.Sight(new Vec3(.5, 65.6, -2), 4.5),
            new EntityCollisionContext(65, false, 0, false, false));
        var world = new World(state("stone"));
        world.states.put(new BlockPos(0, 65, 0), state("stone"));
        var result = use(world, owner);
        assertEquals(InteractionKind.CONSUME, result.interaction().kind());
        assertEquals(200, result.player().activeUse().remainingTicks());
        assertTrue(result.writes().isEmpty());
        world.entityHit = true;
        assertNull(use(world, owner).player().activeUse());
        world.entityHit = false;
        world.states.put(new BlockPos(0, 65, 0), state("short_grass"));
        assertNull(use(world, owner).player().activeUse());
        assertEquals(InteractionKind.CONSUME, use(world, owner).interaction().kind());
    }

    @Test void comparatorToggleUpdatesPowerAndEntityOutputImmediately() {
        int comparator = DATA.registry().with(state("comparator"), "facing", "north");
        var empty = ITEMS.empty();
        var owner = new SimPlayer(new SimPlayer.State(new Vec3(.5, 65, .5), 0, 0, false, false, true, false, SimPlayer.GameMode.SURVIVAL), empty, empty);
        var world = new World(comparator);
        world.entities.put(POS, BlockEntityPrototypes.create(DATA.registry().block(comparator), comparator));
        world.states.put(POS.relative(Direction.NORTH), state("redstone_block"));
        var result = use(world, owner);
        assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
        assertEquals("subtract", DATA.registry().value(result.writes().getFirst().newState(), "mode"));
        assertEquals("true", DATA.registry().value(result.writes().getLast().newState(), "powered"));
        assertEquals(15, result.blockEntities().get(POS).data().integer("OutputSignal", -1));
        world.states.put(POS.relative(Direction.EAST), state("redstone_block"));
        result = use(world, owner);
        assertEquals(0, result.blockEntities().get(POS).data().integer("OutputSignal", -1));
        assertEquals("false", DATA.registry().value(result.writes().getLast().newState(), "powered"));
        world.states.put(POS.relative(Direction.NORTH), DATA.registry().with(state("cake"), "bites", "4"));
        world.states.remove(POS.relative(Direction.EAST));
        assertEquals(6, use(world, owner).blockEntities().get(POS).data().integer("OutputSignal", -1));
        var restricted = new SimPlayer(new SimPlayer.State(new Vec3(.5, 65, .5), 0, 0, false, false, false, false, SimPlayer.GameMode.ADVENTURE), empty, empty);
        assertTrue(use(world, restricted).writes().isEmpty());
    }

    @Test void comparatorToggleReadsSparseLecternPageInputs() {
        int comparator = DATA.registry().with(state("comparator"), "facing", "north");
        var empty = ITEMS.empty();
        var owner = new SimPlayer(new SimPlayer.State(new Vec3(.5, 65, .5), 0, 0, false, false, true, false, SimPlayer.GameMode.SURVIVAL), empty, empty);
        var world = new World(comparator);
        world.entities.put(POS, BlockEntityPrototypes.create(DATA.registry().block(comparator), comparator));
        var lecternPos = POS.relative(Direction.NORTH);
        world.states.put(lecternPos, DATA.registry().with(state("lectern"), "has_book", "true"));
        for (int[] input : new int[][]{{3, 0, 1, 1}, {3, 1, 1, 8}, {3, 2, 1, 15}, {0, -1, 1, 15}, {0, -1, 0, 14}}) {
            var fields = Components.EMPTY.with("page_count", new com.google.gson.JsonPrimitive(input[0]))
                    .with("Page", new com.google.gson.JsonPrimitive(input[1]))
                    .with("has_book_content", new com.google.gson.JsonPrimitive(input[2]));
            world.entities.put(lecternPos, new BlockEntityData("minecraft:lectern", fields));
            assertEquals(input[3], use(world, owner).blockEntities().get(POS).data().integer("OutputSignal", -1));
        }
    }
}
