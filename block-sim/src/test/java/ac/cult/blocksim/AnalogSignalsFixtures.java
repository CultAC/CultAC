package ac.cult.blocksim;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.AnalogProofRegistries;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.SimLevel;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/** Shared inputs for the AnalogSignals parent-oracle parity and allocation tests. */
final class AnalogSignalsFixtures {
    static final DataTables DATA;
    static {
        try { DATA = DataTables.load("26.3"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static final ItemRegistry ITEMS = new ItemRegistry(DATA);
    static final InteractionRegistries REGISTRIES = InteractionRegistries.defaults();
    static final List<StateFacts> FACTS = AnalogProofRegistries.facts(DATA.registry());
    static final BlockPos POS = new BlockPos(0, 64, 0);

    private AnalogSignalsFixtures() {}

    /** Constructing BehaviorRegistry builds a production AnalogSignals; the allocation test must not trigger it. */
    static final class Behaviors {
        static final BehaviorRegistry VALUE = new BehaviorRegistry(DATA);
    }

    static int state(String block) { return DATA.registry().block("minecraft:" + block).defaultState(); }
    static int with(int state, String property, String value) { return DATA.registry().with(state, property, value); }

    static DataTables wrap(BlockRegistry registry) {
        return new DataTables(DATA.version(), DATA.vanillaSha256(), DATA.reportSha256(), DATA.enabledFeatures(), registry, DATA.items(), DATA.tags());
    }
    static DataTables equalCopy() { return wrap(AnalogProofRegistries.equalCopy(DATA.registry(), FACTS)); }

    /**
     * Renumbered copies of the named default blocks. An override replaces classHierarchy; a key in
     * {@code missing} drops it.
     */
    static DataTables tables(List<String> keys, Map<String, String> overrides, Set<String> missing) {
        var source = DATA.registry();
        var blocks = new ArrayList<BlockDefinition>();
        var facts = new ArrayList<StateFacts>();
        for (String key : keys) {
            var block = source.block(key);
            var bindings = new HashMap<>(block.bindings());
            if (overrides.containsKey(key)) bindings.put("classHierarchy", overrides.get(key));
            if (missing.contains(key)) bindings.remove("classHierarchy");
            int first = facts.size();
            blocks.add(new BlockDefinition(block.key(), block.vanillaClass(), first, block.stateCount(),
                first + block.defaultState() - block.firstState(), block.properties(), bindings, block.features()));
            for (int s = block.firstState(); s < block.firstState() + block.stateCount(); s++) facts.add(source.facts(s));
        }
        return wrap(new BlockRegistry(blocks, facts, source.maxValidChunkCoordinate()));
    }

    /** Same item data, with stone's received default max stack size replaced. */
    static ItemRegistry itemsWithStoneStack(int maxStackSize) {
        Components stone = ITEMS.defaults("minecraft:stone").with("minecraft:max_stack_size", new JsonPrimitive(maxStackSize));
        return new ItemRegistry(DATA, key -> key.equals("minecraft:stone") ? stone : null);
    }

    /** Default interaction registries with one received jukebox song's comparator output replaced. */
    static InteractionRegistries songOutput(String song, int output) {
        var entries = new HashMap<>(REGISTRIES.entries("jukebox_song"));
        var entry = REGISTRIES.resolve("jukebox_song", new JsonPrimitive(song)).getAsJsonObject();
        entry.addProperty("comparator_output", output);
        entries.put(song, entry);
        return REGISTRIES.withRegistry("jukebox_song", entries);
    }

    static BlockEntityData entity(String type, String json) { return new BlockEntityData(type, Components.parse(json)); }

    static SimLevel level(World world) { return new SimLevel(world, DATA.registry(), Behaviors.VALUE); }
    /** Levels whose analog reads use only the registry and world; any behavior query fails. */
    static SimLevel bareLevel(World world, BlockRegistry registry) {
        return new SimLevel(world, registry, state -> { throw new AssertionError("Unexpected behavior query for state " + state); });
    }

    /** "v:value" or "e:exception class[:message]"; NPE messages name source variables and are omitted. */
    static String outcome(IntSupplier output) {
        try { return "v:" + output.getAsInt(); }
        catch (RuntimeException e) { return "e:" + e.getClass().getName() + (e instanceof NullPointerException ? "" : ":" + e.getMessage()); }
    }

    static final class World implements StubWorldView {
        final Map<BlockPos, Integer> states = new HashMap<>();
        final Map<BlockPos, BlockEntityData> entities = new HashMap<>();
        final Set<BlockPos> cats = new HashSet<>();
        final int air;
        int detectorRailOutput = 7;
        World() { this(state("air")); }
        World(int air) { this.air = air; }
        public int stateAt(BlockPos pos) { return states.getOrDefault(pos, air); }
        public boolean isLoaded(BlockPos pos) { return true; }
        public boolean isSectionEmpty(BlockPos pos) { return false; }
        public int minY() { return -64; }
        public int height() { return 384; }
        public boolean isWithinBorder(BlockPos pos) { return true; }
        public boolean creakingActiveAt(BlockPos pos) { return false; }
        public boolean waterEvaporatesAt(BlockPos pos) { return false; }
        public BlockEntityData blockEntityAt(BlockPos pos) { return entities.get(pos); }
        @Override public int detectorRailOutputAt(BlockPos pos) { return detectorRailOutput; }
        @Override public boolean hasSittingCatAt(BlockPos pos) { return cats.contains(pos); }
    }
}
