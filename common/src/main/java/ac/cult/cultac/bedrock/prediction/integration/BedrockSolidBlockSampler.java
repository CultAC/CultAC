package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

final class BedrockSolidBlockSampler {
    private final BedrockFluidBlockSampler fluidBlocks;

    BedrockSolidBlockSampler(BedrockFluidBlockSampler fluidBlocks) {
        this.fluidBlocks = fluidBlocks;
    }

    BedrockSolidBlockSample sample(
            CultPlayer player,
            SimpleCollisionBox query,
            BedrockCollisionOverrideCatalog geometry
    ) {
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
            BlockState blockState,
            BlockPosition position,
            String javaState,
            BedrockCollisionWorldBuilder worldBuilder
    ) {
        List<AABB> nativeBoxes = NativeBlockCollisionHelper.getCollisionShape(
                player,
                blockState,
                position.x(),
                position.y(),
                position.z()).toAabbs();
        List<WorldCollisionBox> boxes = new ArrayList<>(nativeBoxes.size());
        for (AABB box : nativeBoxes) {
            if (box.maxX > box.minX && box.maxY > box.minY && box.maxZ > box.minZ) {
                boxes.add(new WorldCollisionBox(
                        box.minX + position.x(),
                        box.minY + position.y(),
                        box.minZ + position.z(),
                        box.maxX + position.x(),
                        box.maxY + position.y(),
                        box.maxZ + position.z()));
            }
        }
        return PlacedBlockCollision.sampled(
                position,
                javaState,
                blockState,
                javaState,
                worldBuilder.bedrockState(blockState),
                boxes,
                BedrockCollisionWorldBuilder.contactBehaviors(
                        blockState));
    }

    record BedrockSolidBlockSample(
        List<BlockPosition> powderSnowBlocks,
        BlockCollisionWorld staticGeneratedWorld,
        BlockCollisionWorld actorIndependentWorld,
        Map<BlockPosition, BlockState> dynamicGeneratedBlockStates,
        List<PlacedBlockCollision> javaCollisionBlocks,
        List<PlacedBlockCollision> fluidCollisionBlocks
    ) {
        BedrockSolidBlockSample {
            powderSnowBlocks = powderSnowBlocks == null ? List.of() : List.copyOf(powderSnowBlocks);
            staticGeneratedWorld = staticGeneratedWorld == null ? BlockCollisionWorld.EMPTY : staticGeneratedWorld;
            actorIndependentWorld = actorIndependentWorld == null ? BlockCollisionWorld.EMPTY : actorIndependentWorld;
            dynamicGeneratedBlockStates = immutableMap(dynamicGeneratedBlockStates);
            javaCollisionBlocks = javaCollisionBlocks == null ? List.of() : List.copyOf(javaCollisionBlocks);
            fluidCollisionBlocks = fluidCollisionBlocks == null ? List.of() : List.copyOf(fluidCollisionBlocks);
        }

        private static Map<BlockPosition, BlockState> immutableMap(Map<BlockPosition, BlockState> input) {
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
        private final Map<BlockPosition, BlockState> staticGeneratedBlockStates = new LinkedHashMap<>();
        private final Map<BlockPosition, BlockState> dynamicGeneratedBlockStates = new LinkedHashMap<>();
        private final List<PlacedBlockCollision> javaCollisionBlocks = new ArrayList<>();
        private final List<PlacedBlockCollision> fluidCollisionBlocks = new ArrayList<>();

        private SampleAccumulator(CultPlayer player, BedrockCollisionOverrideCatalog geometry) {
            this.player = player;
            this.geometry = geometry;
            this.worldBuilder = new BedrockCollisionWorldBuilder(geometry);
        }

        private void add(int x, int y, int z) {
            BlockState blockState = player.compensatedWorld.getBlockStateAt(x, y, z);
            if (blockState.isAir() && blockState.getFluidState().isEmpty()) {
                return;
            }
            BlockPosition position = new BlockPosition(x, y, z);
            String javaState = BedrockCollisionWorldBuilder.javaIdentifier(blockState);
            PlacedBlockCollision fluidBlock = fluidBlocks.fluidBlock(player, x, y, z, blockState, javaState);
            if (fluidBlock != null) {
                fluidCollisionBlocks.add(fluidBlock);
            }
            if (blockState.getBlock() == net.minecraft.world.level.block.Blocks.POWDER_SNOW) {
                powderSnowBlocks.add(position);
            }
            if (blockState.isAir() || fluidBlocks.isFluid(blockState)) {
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
                    Math.max(player.compensatedWorld.getMinHeight(),
                            (int) Math.floor(query.minY - SimpleCollisionBox.COLLISION_EPSILON)),
                    Math.min(player.compensatedWorld.getMaxHeight() - 1,
                            (int) Math.floor(query.maxY + SimpleCollisionBox.COLLISION_EPSILON)),
                    (int) Math.floor(query.minZ - SimpleCollisionBox.COLLISION_EPSILON),
                    (int) Math.floor(query.maxZ + SimpleCollisionBox.COLLISION_EPSILON));
        }
    }
}
