package ac.cult.blocksim.data;

import java.util.Map;

public record ItemDefinition(int id, String key, String vanillaClass, String block,
                             Map<String, String> bindings, String defaultComponentsJson) {
    public ItemDefinition { bindings = Map.copyOf(bindings); }
}
