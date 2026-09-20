package ac.cult.cultac.bedrock.prediction.world;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Bedrock block material properties, independent of a block's collision shape. */
public final class BedrockBlockMaterials {
    private static final String RESOURCE = "bedrock/cultac-bedrock-block-materials.json";
    private static volatile BedrockBlockMaterials bundled;
    private final Map<String, Integer> flagsByJavaIdentifier;

    private BedrockBlockMaterials(Map<String, Integer> flagsByJavaIdentifier) {
        this.flagsByJavaIdentifier = Map.copyOf(flagsByJavaIdentifier);
    }

    public static BedrockBlockMaterials load(InputStream input) throws IOException {
        if (input == null) throw new IOException("Missing Bedrock block materials");
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!"cultac-bedrock-block-materials/v1".equals(root.get("schema").getAsString())) {
                throw new IOException("Unsupported Bedrock block material schema");
            }
            Map<String, Integer> flags = new HashMap<>();
            for (var entry : root.getAsJsonObject("blocks").entrySet()) {
                int value = entry.getValue().getAsInt();
                if (value < 0 || value > 3) throw new IOException("Invalid material flags for " + entry.getKey());
                flags.put(entry.getKey(), value);
            }
            return new BedrockBlockMaterials(flags);
        }
    }

    public static BedrockBlockMaterials bundled() {
        BedrockBlockMaterials result = bundled;
        if (result != null) return result;
        synchronized (BedrockBlockMaterials.class) {
            if (bundled == null) {
                try {
                    bundled = load(BedrockBlockMaterials.class.getClassLoader().getResourceAsStream(RESOURCE));
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot load Bedrock block materials", exception);
                }
            }
            return bundled;
        }
    }

    public boolean blocksMotion(PlacedBlockCollision block) {
        return block != null && (flags(block) & 1) != 0;
    }

    public boolean isSolid(PlacedBlockCollision block) {
        return block != null && (flags(block) & 2) != 0;
    }

    private int flags(PlacedBlockCollision block) {
        String identifier = block.javaState();
        int properties = identifier.indexOf('[');
        if (properties >= 0) identifier = identifier.substring(0, properties);
        Integer flags = flagsByJavaIdentifier.get(identifier);
        if (flags == null) throw new IllegalArgumentException("Missing Bedrock material for " + identifier);
        return flags;
    }
}
