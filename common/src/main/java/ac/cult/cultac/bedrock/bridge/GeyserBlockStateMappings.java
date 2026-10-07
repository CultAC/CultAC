package ac.cult.cultac.bedrock.bridge;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.player.BedrockBlockLayers;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockServerStateMappings;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.value.Direction;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.type.BlockMappings;

/** Inverts the session palette through the same server-to-Geyser protocol mappings as collision shapes. */
final class GeyserBlockStateMappings {
    private static final Map<BlockMappings, Map<Integer, Integer>> PALETTES = new IdentityHashMap<>();

    private GeyserBlockStateMappings() {}

    static synchronized Map<Integer, Integer> palette(BlockMappings mappings) {
        return PALETTES.computeIfAbsent(mappings, GeyserBlockStateMappings::create);
    }

    private static Map<Integer, Integer> create(BlockMappings mappings) {
        int server = ProtocolVersion.V26_3.protocol();
        int geyser = GeyserBlockMappingsAccess.javaProtocolVersion();
        if (server != geyser && !ViaVersionUtil.isAvailable()) {
            // A proxy models Geyser's Java view through the forward table, as for any older client,
            // so each Bedrock block resolves to the state the compensated world holds for it.
            var states = ModelBlockStates.load(ProtocolVersion.of(geyser), ProtocolVersion.of(server));
            if (states.sourceCount() != mappings.getJavaToBedrockBlocks().length) {
                throw new IllegalStateException("Geyser's Java block states do not match protocol " + geyser);
            }
            return invert(mappings, states.sourceCount(), javaId -> javaId, states::toModel);
        }
        int[] javaIds = BedrockServerStateMappings.create(
                server,
                DataTables.defaults().registry().stateCount(),
                geyser,
                mappings.getJavaToBedrockBlocks().length);
        return invert(mappings, javaIds);
    }

    static Map<Integer, Integer> invert(BlockMappings mappings, int[] javaIds) {
        return invert(mappings, javaIds.length, serverId -> javaIds[serverId], serverId -> serverId);
    }

    private static Map<Integer, Integer> invert(
            BlockMappings mappings, int count, IntUnaryOperator javaIdOf, IntUnaryOperator serverIdOf) {
        var states = new HashMap<Integer, Integer>();
        for (int index = 0; index < count; index++) {
            int javaId = javaIdOf.applyAsInt(index);
            var definition = mappings.getBedrockBlock(javaId);
            int stateId = serverIdOf.applyAsInt(index);
            // Preserve the original registry lookup fallback for IDs outside the model.
            if (stateId < 0 || stateId >= DataTables.defaults().registry().stateCount())
                stateId = BlockIds.AIR.defaultState();
            int state = BedrockBlockLayers.dry(stateId);
            states.putIfAbsent(definition.getRuntimeId(), state);
            // Inventory holders deliberately send vanilla definitions even with custom overrides.
            states.putIfAbsent(mappings.getVanillaBedrockBlock(javaId).getRuntimeId(), state);
        }
        states.put(mappings.getBedrockAir().getRuntimeId(), BlockIds.AIR.defaultState());
        // Bedrock item frames replace an otherwise empty block; their Java entity is tracked separately.
        if (mappings.getItemFrames() != null) {
            mappings.getItemFrames()
                    .values()
                    .forEach(definition -> states.putIfAbsent(definition.getRuntimeId(), BlockIds.AIR.defaultState()));
        }
        // SkullCache sends these directly; they are not in javaToBedrockBlocks.
        var customStates = GeyserBlockMappingsAccess.customBlockStates(mappings);
        for (var skull : BlockRegistries.CUSTOM_SKULLS.get().values()) {
            for (int rotation = 0; rotation < 16; rotation++) {
                var definition = customStates.get(skull.getFloorBlockState(rotation));
                if (definition != null)
                    states.put(
                            definition.getRuntimeId(),
                            BlockProps.ROTATION_16.with(BlockIds.PLAYER_HEAD.defaultState(), rotation));
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                var definition = customStates.get(skull.getWallBlockState(
                        switch (direction) {
                            case SOUTH -> 0;
                            case WEST -> 90;
                            case NORTH -> 180;
                            case EAST -> 270;
                            default -> throw new IllegalArgumentException("Non-horizontal skull facing");
                        }));
                if (definition != null)
                    states.put(
                            definition.getRuntimeId(),
                            BlockProps.HORIZONTAL_FACING.with(
                                    BlockIds.PLAYER_WALL_HEAD.defaultState(), direction.get3DDataValue()));
            }
        }
        return Map.copyOf(states);
    }

    static int resolve(Map<Integer, Integer> palette, BlockDefinition definition) {
        Integer state = definition == null ? null : palette.get(definition.getRuntimeId());
        if (state == null) throw new IllegalStateException("Unmapped Geyser block definition: " + definition);
        return state;
    }
}
