package ac.cult.blocksim.data;

import java.util.Arrays;

/** Prebound property layouts. Reads and writes use the model state's mixed-radix stride. */
public final class StateProperty {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private final String name;
    private final Layout[] layouts;

    public StateProperty(String name, int identity) {
        this.name = name;
        layouts = new Layout[REGISTRY.blocks().size()];
        String binding = "property." + name, expected = Integer.toString(identity);
        for (int index = 0; index < layouts.length; index++) {
            var block = REGISTRY.blocks().get(index);
            if (!expected.equals(block.bindings().get(binding + ".identity"))) continue;
            var property = block.properties().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
            String type = block.bindings().get(binding + ".type");
            int[] values = new int[property.values().size()];
            for (int i = 0; i < values.length; i++) {
                String value = property.values().get(i);
                values[i] = type.endsWith(":java.lang.Boolean") ? Boolean.parseBoolean(value) ? 1 : 0
                        : type.endsWith(":java.lang.Integer") ? Integer.parseInt(value)
                        : Integer.parseInt(block.bindings().get(binding + ".ordinal." + value));
            }
            layouts[index] = new Layout(block.firstState(), property.stride(), values, type.endsWith(":java.lang.Integer"));
        }
    }

    public boolean has(int state) { return layouts[REGISTRY.blockIndex(state)] != null; }
    public int value(int state) {
        Layout layout = layout(state);
        return layout.values[(state - layout.firstState) / layout.stride % layout.values.length];
    }
    public boolean booleanValue(int state) { return value(state) != 0; }
    public int with(int state, boolean value) { return with(state, value ? 1 : 0); }
    public int with(int state, int value) {
        Layout layout = layout(state);
        long index = (long) value - layout.minimum;
        int replacement = layout.integer ? value <= layout.maximum ? value - layout.minimum : -1
                : index >= 0 && index < layout.indices.length ? layout.indices[(int) index] : -1;
        if (replacement < 0) throw new IllegalArgumentException("Invalid " + name + " value " + value);
        // IntegerProperty uses int subtraction, including overflow before the neighbor-array access.
        if (replacement >= layout.values.length) throw new ArrayIndexOutOfBoundsException(
                "Index " + replacement + " out of bounds for length " + layout.values.length);
        int previous = (state - layout.firstState) / layout.stride % layout.values.length;
        return state + (replacement - previous) * layout.stride;
    }

    private Layout layout(int state) {
        Layout layout = layouts[REGISTRY.blockIndex(state)];
        if (layout == null) throw new IllegalArgumentException("Block " + REGISTRY.block(state).key() + " has no " + name);
        return layout;
    }

    private static final class Layout {
        private final int firstState, stride, minimum, maximum;
        private final boolean integer;
        private final int[] values, indices;
        private Layout(int firstState, int stride, int[] values, boolean integer) {
            this.firstState = firstState;
            this.stride = stride;
            this.values = values;
            this.integer = integer;
            minimum = Arrays.stream(values).min().orElseThrow();
            maximum = Arrays.stream(values).max().orElseThrow();
            indices = new int[Math.toIntExact((long) maximum - minimum + 1)];
            Arrays.fill(indices, -1);
            for (int i = 0; i < values.length; i++) indices[values[i] - minimum] = i;
        }
    }
}
