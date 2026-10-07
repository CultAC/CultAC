package ac.cult.blocksim.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Versioned global state IDs with vanilla's mixed-radix property neighbor table. */
public final class BlockRegistry {
    private final List<BlockDefinition> blocks;
    private final StateFacts[] facts;
    private final BlockDefinition[] byState;
    private final int[] blockIndices;
    private final Map<String, BlockDefinition> byKey;
    private final Map<BlockDefinition, Map<String, BlockDefinition.Property>> properties;
    private final Map<BlockDefinition.Property, Map<String, Integer>> valueIndices;
    private final int maxValidChunkCoordinate;
    private final StateShapes shapes;

    public BlockRegistry(List<BlockDefinition> blocks, List<StateFacts> facts, int maxValidChunkCoordinate) {
        if (maxValidChunkCoordinate <= 0) throw new IllegalArgumentException("Invalid chunk-coordinate bound");
        this.maxValidChunkCoordinate = maxValidChunkCoordinate;
        this.blocks = List.copyOf(blocks);
        this.facts = facts.toArray(StateFacts[]::new);
        this.byState = new BlockDefinition[facts.size()];
        this.blockIndices = new int[facts.size()];
        int blockIndex = 0;
        var keys = new HashMap<String, BlockDefinition>();
        var propertyTables = new java.util.IdentityHashMap<BlockDefinition, Map<String, BlockDefinition.Property>>();
        var indices = new java.util.IdentityHashMap<BlockDefinition.Property, Map<String, Integer>>();
        for (BlockDefinition block : blocks) {
            if (keys.put(block.key(), block) != null) throw new IllegalArgumentException("Duplicate " + block.key());
            long product = 1;
            var propertyTable = new HashMap<String, BlockDefinition.Property>();
            for (var property : block.properties()) {
                product *= property.values().size();
                if (propertyTable.put(property.name(), property) != null) throw new IllegalArgumentException("Duplicate property " + property.name());
                var values = new HashMap<String, Integer>();
                for (int i = 0; i < property.values().size(); i++) {
                    if (values.put(property.values().get(i), i) != null) throw new IllegalArgumentException("Duplicate property value " + property.name());
                }
                indices.put(property, Map.copyOf(values));
            }
            propertyTables.put(block, Map.copyOf(propertyTable));
            if (product != block.stateCount()) throw new IllegalArgumentException("Invalid property product for " + block.key());
            if (block.defaultState() < block.firstState() || block.defaultState() >= block.firstState() + block.stateCount()) {
                throw new IllegalArgumentException("Invalid default state for " + block.key());
            }
            for (int id = block.firstState(); id < block.firstState() + block.stateCount(); id++) {
                if (byState[id] != null) throw new IllegalArgumentException("Overlapping state " + id);
                byState[id] = block;
                blockIndices[id] = blockIndex;
            }
            blockIndex++;
        }
        for (int id = 0; id < byState.length; id++) {
            if (byState[id] == null) throw new IllegalArgumentException("Missing state " + id);
        }
        byKey = Map.copyOf(keys);
        properties = java.util.Collections.unmodifiableMap(propertyTables);
        valueIndices = java.util.Collections.unmodifiableMap(indices);
        shapes = new StateShapes(this.facts);
    }

    public List<BlockDefinition> blocks() { return blocks; }
    public int stateCount() { return facts.length; }
    public int maxValidChunkCoordinate() { return maxValidChunkCoordinate; }
    public StateFacts facts(int state) { return facts[state]; }
    public StateShapes shapes() { return shapes; }
    public BlockDefinition block(int state) { return byState[state]; }
    public int blockIndex(int state) { return blockIndices[state]; }
    public BlockDefinition block(String key) {
        var block = byKey.get(key);
        if (block == null) throw new IllegalArgumentException("Unknown block " + key);
        return block;
    }
    public boolean sameBlock(int first, int second) { return byState[first] == byState[second]; }

    /** Block-state command/debug representation, in the generated property order. */
    public String serialize(int state) {
        var block = block(state);
        if (block.properties().isEmpty()) return block.key();
        var text = new java.util.StringJoiner(",", block.key() + "[", "]");
        for (var property : block.properties()) {
            int index = (state - block.firstState()) / property.stride() % property.values().size();
            text.add(property.name() + "=" + property.values().get(index));
        }
        return text.toString();
    }

    /** StateHolder's diagnostic representation, using the same generated property order. */
    public String debugString(int state) {
        String text = serialize(state);
        int properties = text.indexOf('[');
        return properties < 0 ? "Block{" + text + "}"
                : "Block{" + text.substring(0, properties) + "}" + text.substring(properties);
    }

    public int withPropertiesOf(BlockDefinition target, int source) {
        return withPropertiesOf(target.defaultState(), source);
    }
    public int withPropertiesOf(int result, int source) {
        for (var property : block(source).properties()) {
            if (hasSameProperty(source, result, property.name())) {
                result = with(result, property.name(), value(source, property.name()));
            }
        }
        return result;
    }

    public String value(int state, String propertyName) {
        var block = block(state);
        var property = property(block, propertyName);
        return property.values().get((state - block.firstState()) / property.stride() % property.values().size());
    }

    public int with(int state, String propertyName, String value) {
        var block = block(state);
        var property = property(block, propertyName);
        int oldIndex = (state - block.firstState()) / property.stride() % property.values().size();
        Integer index = valueIndices.get(property).get(value);
        if (index == null) throw new IllegalArgumentException("Unknown " + propertyName + " value " + value);
        return state + (index - oldIndex) * property.stride();
    }

    public boolean hasProperty(int state, String propertyName) { return properties.get(block(state)).containsKey(propertyName); }

    /** Pinned StateHolder matches the canonical property instance. */
    public boolean hasSameProperty(int state, int templateState, String name) {
        String identity = block(state).bindings().get("property." + name + ".identity");
        return identity != null && identity.equals(block(templateState).bindings().get("property." + name + ".identity"));
    }

    /** Native Property.getValue ignores invalid component values instead of failing placement. */
    public int withIfValid(int state, String propertyName, String value) {
        String parsed = parsedPropertyValue(state, propertyName, value);
        return parsed == null ? state : with(state, propertyName, parsed);
    }

    public String parsedPropertyValue(int state, String name, String value) {
        var property = properties.get(block(state)).get(name);
        if (property == null) return null;
        if (block(state).bindings().get("property." + name + ".type").endsWith(":java.lang.Integer")) return integerPropertyValue(property, value);
        return valueIndices.get(property).containsKey(value) ? value : null;
    }

    private String integerPropertyValue(BlockDefinition.Property property, String name) {
        try {
            String value = Integer.toString(Integer.parseInt(name));
            return valueIndices.get(property).containsKey(value) ? value : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    /** Comparable values used by StatePropertiesPredicate's ranged matcher. */
    public int comparePropertyValues(int state, String name, String left, String right) {
        String type = block(state).bindings().get("property." + name + ".type");
        if (type.endsWith(":java.lang.Integer")) return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
        if (type.endsWith(":java.lang.Boolean")) return Boolean.compare(Boolean.parseBoolean(left), Boolean.parseBoolean(right));
        var bindings = block(state).bindings();
        return Integer.compare(Integer.parseInt(bindings.get("property." + name + ".ordinal." + left)),
            Integer.parseInt(bindings.get("property." + name + ".ordinal." + right)));
    }

    private BlockDefinition.Property property(BlockDefinition block, String name) {
        var property = properties.get(block).get(name);
        if (property == null) throw new IllegalArgumentException("Block " + block.key() + " has no property " + name);
        return property;
    }
}
