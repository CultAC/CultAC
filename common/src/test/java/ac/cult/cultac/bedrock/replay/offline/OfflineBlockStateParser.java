package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.blocksim.data.DataTables;
import java.io.IOException;

public final class OfflineBlockStateParser {
    private OfflineBlockStateParser() {}

    public static int parse(String serialized) throws IOException {
        var registry = DataTables.defaults().registry();
        int propertiesStart = serialized.indexOf('[');
        String id = propertiesStart < 0 ? serialized : serialized.substring(0, propertiesStart);
        if (!id.contains(":")) id = "minecraft:" + id;
        try {
            int state = registry.block(id).defaultState();
            if (propertiesStart < 0) return state;
            String properties = serialized.substring(propertiesStart + 1, serialized.length() - 1);
            if (properties.isBlank()) return state;
            for (String assignment : properties.split(",")) {
                int equals = assignment.indexOf('=');
                if (equals <= 0) continue;
                String name = assignment.substring(0, equals);
                String value = registry.parsedPropertyValue(state, name, assignment.substring(equals + 1));
                if (value == null) throw new IOException("invalid block state property " + assignment);
                state = registry.with(state, name, value);
            }
            return state;
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid offline replay state: " + serialized, failure);
        }
    }
}
