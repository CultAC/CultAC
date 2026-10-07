package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BedrockSolidBlockSampler {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private final BedrockFluidBlockSampler fluidBlocks;

    BedrockSolidBlockSampler(BedrockFluidBlockSampler fluidBlocks) {
        this.fluidBlocks = fluidBlocks;
    }

    BedrockSolidBlockSample sample(
            CultPlayer player, SimpleCollisionBox query, BedrockCollisionOverrideCatalog geometry) {
        SampleBounds bounds = SampleBounds.from(player, query);
        SampleAccumulator sampled = new SampleAccumulator(player, geometry);
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    sampled.add(x, y, z);
                }
            }
        }
        return sampled.build();
    }

    private static PlacedBlockCollision javaCollisionBlock(
            CultPlayer player,
            int blockState,
            BlockPosition position,
            String javaState,
            BedrockCollisionWorldBuilder worldBuilder) {
        List<SimpleCollisionBox> collisionBoxes = new ArrayList<>();
        ClientBlockShapes.movement(player, blockState, position.x(), position.y(), position.z())
                .downCast(collisionBoxes);
        List<WorldCollisionBox> boxes = new ArrayList<>(collisionBoxes.size());
        for (SimpleCollisionBox box : collisionBoxes) {
            if (box.maxX > box.minX && box.maxY > box.minY && box.maxZ > box.minZ) {
                boxes.add(new WorldCollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
            }
        }
        return PlacedBlockCollision.sampled(
                position,
                javaState,
                blockState,
                javaState,
                worldBuilder.bedrockState(blockState),
                boxes,
                BedrockCollisionWorldBuilder.contactBehaviors(blockState));
    }

    record BedrockSolidBlockSample(
            List<BlockPosition> powderSnowBlocks,
            BlockCollisionWorld staticGeneratedWorld,
            BlockCollisionWorld actorIndependentWorld,
            Map<BlockPosition, Integer> dynamicGeneratedBlockStates,
            List<PlacedBlockCollision> javaCollisionBlocks,
            List<PlacedBlockCollision> fluidCollisionBlocks) {
        BedrockSolidBlockSample {
            powderSnowBlocks = powderSnowBlocks == null ? List.of() : List.copyOf(powderSnowBlocks);
            staticGeneratedWorld = staticGeneratedWorld == null ? BlockCollisionWorld.EMPTY : staticGeneratedWorld;
            actorIndependentWorld = actorIndependentWorld == null ? BlockCollisionWorld.EMPTY : actorIndependentWorld;
            dynamicGeneratedBlockStates = immutableMap(dynamicGeneratedBlockStates);
            javaCollisionBlocks = javaCollisionBlocks == null ? List.of() : List.copyOf(javaCollisionBlocks);
            fluidCollisionBlocks = fluidCollisionBlocks == null ? List.of() : List.copyOf(fluidCollisionBlocks);
        }

        private static Map<BlockPosition, Integer> immutableMap(Map<BlockPosition, Integer> input) {
            if (input == null || input.isEmpty()) {
                return Map.of();
            }
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(input));
        }
    }

    private final class SampleAccumulator {
        private final CultPlayer player;
        private final BedrockCollisionOverrideCatalog geometry;
        private final BedrockCollisionWorldBuilder worldBuilder;
        private final List<BlockPosition> powderSnowBlocks = new ArrayList<>();
        private final Map<BlockPosition, Integer> staticGeneratedBlockStates = new LinkedHashMap<>();
        private final Map<BlockPosition, Integer> dynamicGeneratedBlockStates = new LinkedHashMap<>();
        private final List<PlacedBlockCollision> javaCollisionBlocks = new ArrayList<>();
        private final List<PlacedBlockCollision> fluidCollisionBlocks = new ArrayList<>();

        private SampleAccumulator(CultPlayer player, BedrockCollisionOverrideCatalog geometry) {
            this.player = player;
            this.geometry = geometry;
            this.worldBuilder = new BedrockCollisionWorldBuilder(geometry);
        }

        private void add(int x, int y, int z) {
            int blockState = player.compensatedWorld.getBlockStateIdAt(x, y, z);
            StateFacts facts = REGISTRY.facts(blockState);
            boolean air = facts.has(StateFacts.AIR);
            if (air && facts.fluid().equals("minecraft:empty")) {
                return;
            }
            BlockPosition position = new BlockPosition(x, y, z);
            String javaState = BedrockCollisionWorldBuilder.javaIdentifier(blockState);
            PlacedBlockCollision fluidBlock = fluidBlocks.fluidBlock(player, x, y, z, blockState, javaState);
            if (fluidBlock != null) {
                fluidCollisionBlocks.add(fluidBlock);
            }
            if (BlockIds.is(blockState, BlockIds.POWDER_SNOW)) {
                powderSnowBlocks.add(position);
            }
            if (air || fluidBlocks.isFluid(blockState)) {
                return;
            }
            if (!BedrockCollisionWorldBuilder.hasBedrockBehavior(blockState, geometry)) {
                javaCollisionBlocks.add(javaCollisionBlock(player, blockState, position, javaState, worldBuilder));
            } else if (BedrockCollisionWorldBuilder.dynamicMovement(blockState)) {
                dynamicGeneratedBlockStates.put(position, blockState);
            } else {
                staticGeneratedBlockStates.put(position, blockState);
            }
        }

        private BedrockSolidBlockSample build() {
            BlockCollisionWorld staticGeneratedWorld = staticGeneratedBlockStates.isEmpty()
                    ? BlockCollisionWorld.EMPTY
                    : worldBuilder.build(staticGeneratedBlockStates);
            List<PlacedBlockCollision> actorIndependentBlocks = new ArrayList<>(
                    staticGeneratedWorld.blocks().size() + javaCollisionBlocks.size() + fluidCollisionBlocks.size());
            actorIndependentBlocks.addAll(staticGeneratedWorld.blocks());
            actorIndependentBlocks.addAll(javaCollisionBlocks);
            actorIndependentBlocks.addAll(fluidCollisionBlocks);
            return new BedrockSolidBlockSample(
                    powderSnowBlocks,
                    staticGeneratedWorld,
                    actorIndependentBlocks.isEmpty()
                            ? BlockCollisionWorld.EMPTY
                            : new BlockCollisionWorld(actorIndependentBlocks),
                    dynamicGeneratedBlockStates,
                    javaCollisionBlocks,
                    fluidCollisionBlocks);
        }
    }

    private record SampleBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        private static SampleBounds from(CultPlayer player, SimpleCollisionBox query) {
            // The caller has already expanded this box for the complete tick displacement.
            // Integer rounding is sufficient here; another block shell cannot reach the sweep.
            return new SampleBounds(
                    (int) Math.floor(query.minX - SimpleCollisionBox.COLLISION_EPSILON),
                    (int) Math.floor(query.maxX + SimpleCollisionBox.COLLISION_EPSILON),
                    Math.max(player.compensatedWorld.getMinHeight(), (int)
                            Math.floor(query.minY - SimpleCollisionBox.COLLISION_EPSILON)),
                    Math.min(player.compensatedWorld.getMaxHeight() - 1, (int)
                            Math.floor(query.maxY + SimpleCollisionBox.COLLISION_EPSILON)),
                    (int) Math.floor(query.minZ - SimpleCollisionBox.COLLISION_EPSILON),
                    (int) Math.floor(query.maxZ + SimpleCollisionBox.COLLISION_EPSILON));
        }
    }
}
