package ac.cult.blocksim.data;

import java.util.List;
import java.util.Map;

public record BlockDefinition(String key, String vanillaClass, int firstState, int stateCount,
                              int defaultState, List<Property> properties,
                              Map<String, String> bindings, List<String> features) {
    public BlockDefinition {
        properties = List.copyOf(properties);
        bindings = Map.copyOf(bindings);
        features = List.copyOf(features);
    }

    public record Property(String name, int stride, List<String> values) {
        public Property { values = List.copyOf(values); }
        public int indexOf(String value) {
            int index = values.indexOf(value);
            if (index < 0) throw new IllegalArgumentException("Unknown " + name + " value " + value);
            return index;
        }
    }
}
