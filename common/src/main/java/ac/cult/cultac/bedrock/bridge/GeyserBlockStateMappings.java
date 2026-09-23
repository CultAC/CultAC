package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.player.BedrockBlockLayers;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockServerStateMappings;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.geyser.network.GameProtocol;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.type.BlockMappings;

/** Inverts the session palette through the same server-to-Geyser protocol mappings as collision shapes. */
final class GeyserBlockStateMappings {
    private static final Map<BlockMappings, Map<Integer, BlockState>> PALETTES = new IdentityHashMap<>();

    private GeyserBlockStateMappings() { }

    static synchronized Map<Integer, BlockState> palette(BlockMappings mappings) {
        return PALETTES.computeIfAbsent(mappings, GeyserBlockStateMappings::create);
    }

    private static Map<Integer, BlockState> create(BlockMappings mappings) {
        int[] javaIds = BedrockServerStateMappings.create(SharedConstants.getProtocolVersion(),
                Block.BLOCK_STATE_REGISTRY.size(), GameProtocol.getJavaProtocolVersion(),
                mappings.getJavaToBedrockBlocks().length);
        return invert(mappings, javaIds);
    }

    static Map<Integer, BlockState> invert(BlockMappings mappings, int[] javaIds) {
        var states = new HashMap<Integer, BlockState>();
        for (int serverId = 0; serverId < javaIds.length; serverId++) {
            var definition = mappings.getBedrockBlock(javaIds[serverId]);
            BlockState state = BedrockBlockLayers.dry(Block.stateById(serverId));
            states.putIfAbsent(definition.getRuntimeId(), state);
            // Inventory holders deliberately send vanilla definitions even with custom overrides.
            states.putIfAbsent(mappings.getVanillaBedrockBlock(javaIds[serverId]).getRuntimeId(), state);
        }
        states.put(mappings.getBedrockAir().getRuntimeId(), Blocks.AIR.defaultBlockState());
        // Bedrock item frames replace an otherwise empty block; their Java entity is tracked separately.
        if (mappings.getItemFrames() != null) {
            mappings.getItemFrames().values().forEach(definition ->
                    states.putIfAbsent(definition.getRuntimeId(), Blocks.AIR.defaultBlockState()));
        }
        // SkullCache sends these directly; they are not in javaToBedrockBlocks.
        for (var skull : BlockRegistries.CUSTOM_SKULLS.get().values()) {
            for (int rotation = 0; rotation < 16; rotation++) {
                var definition = mappings.getCustomBlockStateDefinitions().get(skull.getFloorBlockState(rotation));
                if (definition != null) states.put(definition.getRuntimeId(), Blocks.PLAYER_HEAD.defaultBlockState()
                        .setValue(BlockStateProperties.ROTATION_16, rotation));
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                var definition = mappings.getCustomBlockStateDefinitions().get(skull.getWallBlockState(
                        switch (direction) {
                            case SOUTH -> 0;
                            case WEST -> 90;
                            case NORTH -> 180;
                            case EAST -> 270;
                            default -> throw new IllegalArgumentException("Non-horizontal skull facing");
                        }));
                if (definition != null) states.put(definition.getRuntimeId(), Blocks.PLAYER_WALL_HEAD.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, direction));
            }
        }
        return Map.copyOf(states);
    }

    static BlockState resolve(Map<Integer, BlockState> palette, BlockDefinition definition) {
        BlockState state = definition == null ? null : palette.get(definition.getRuntimeId());
        if (state == null) throw new IllegalStateException("Unmapped Geyser block definition: " + definition);
        return state;
    }
}
