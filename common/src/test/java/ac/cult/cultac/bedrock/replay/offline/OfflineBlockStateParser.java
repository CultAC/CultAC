package ac.cult.cultac.bedrock.replay.offline;

import java.io.IOException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

final class OfflineBlockStateParser {
    private OfflineBlockStateParser() {
    }

    static BlockState parse(String serialized) throws IOException {
        int propertiesStart = serialized.indexOf('[');
        String id = propertiesStart < 0 ? serialized : serialized.substring(0, propertiesStart);
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        if (block == null) {
            throw new IOException("unknown block in offline replay state: " + id);
        }
        BlockState state = block.defaultBlockState();
        if (propertiesStart < 0) {
            return state;
        }
        String properties = serialized.substring(propertiesStart + 1, serialized.length() - 1);
        if (properties.isBlank()) {
            return state;
        }
        for (String assignment : properties.split(",")) {
            int equals = assignment.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = assignment.substring(0, equals);
            String value = assignment.substring(equals + 1);
            state = setProperty(state, name, value);
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setProperty(BlockState state, String name, String value) throws IOException {
        for (Property property : state.getProperties()) {
            if (!property.getName().equals(name)) {
                continue;
            }
            Object parsedValue = property.getValue(value).orElse(null);
            if (!(parsedValue instanceof Comparable parsed)) {
                throw new IOException("invalid block state property " + name + "=" + value);
            }
            return (BlockState) state.setValue(property, parsed);
        }
        throw new IOException("unknown block state property " + name + " for " + state);
    }
}
