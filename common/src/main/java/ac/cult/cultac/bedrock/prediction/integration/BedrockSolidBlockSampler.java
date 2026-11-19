package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockGeyserMovement;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BedrockSolidBlockSampler {
    /** The order the client gathers block cells in: X outermost, then Z, then Y. */
    static final Comparator<BlockPosition> GATHER_ORDER = Comparator.comparingInt(BlockPosition::x)
            .thenComparingInt(BlockPosition::z)
            .thenComparingInt(BlockPosition::y);

    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private final BedrockFluidBlockSampler fluidBlocks;

    BedrockSolidBlockSampler(BedrockFluidBlockSampler fluidBlocks) {
        this.fluidBlocks = fluidBlocks;
    }

    /**
     * The blocks the sweep collides with, cell by cell in {@link #GATHER_ORDER}, each cell's solid block before its
     * fluid. A moving block a cell outside the sampled cells takes its place in that order.
     */
    BlockCollisionWorld sample(
            CultPlayer player,
            SimpleCollisionBox query,
            BedrockCollisionOverrideCatalog geometry,
            BedrockCollisionShapeQuery shapeQuery) {
        SampleBounds bounds = SampleBounds.from(player, query);
        Set<BlockPosition> movingBlocks = movingBlocksBeside(player, bounds);
        SampleBounds cells = movingBlocks.isEmpty() ? bounds : bounds.grown();
        SampleAccumulator sampled = new SampleAccumulator(player, geometry, shapeQuery);
        for (int x = cells.minX(); x <= cells.maxX(); x++) {
            for (int z = cells.minZ(); z <= cells.maxZ(); z++) {
                for (int y = cells.minY(); y <= cells.maxY(); y++) {
                    if (bounds.contains(x, y, z) || movingBlocks.contains(new BlockPosition(x, y, z))) {
                        sampled.add(x, y, z);
                    }
                }
            }
        }
        int nearbyBlocks = sampled.blocks.size();
        addGeyserColumns(player, bounds, movingBlocks, sampled);
        if (sampled.blocks.size() != nearbyBlocks) {
            sampled.blocks.sort(Comparator.comparing(PlacedBlockCollision::position, GATHER_ORDER));
        }
        return sampled.blocks.isEmpty() ? BlockCollisionWorld.EMPTY : new BlockCollisionWorld(sampled.blocks);
    }

    /** A plume can reach well below the movement sample. Keep its source, water and intervening shapes. */
    private static void addGeyserColumns(
            CultPlayer player, SampleBounds bounds, Set<BlockPosition> movingBlocks, SampleAccumulator sampled) {
        var sources = player.compensatedWorld
                .getGeysers()
                .getTickersInOrder(
                        bounds.minX(),
                        Math.max(
                                player.compensatedWorld.getMinHeight(),
                                bounds.minY() - BedrockGeyserMovement.MAX_COLUMN_HEIGHT),
                        bounds.minZ(),
                        bounds.maxX(),
                        bounds.maxY(),
                        bounds.maxZ());
        if (sources.isEmpty()) return;
        Set<BlockPosition> added = new HashSet<>();
        for (var source : sources) {
            int state = player.compensatedWorld.getBlockStateIdAt(source);
            int phase = BlockProps.POTENT_SULFUR_STATE.value(state);
            if (phase != 3 && phase != 4) continue;
            int top = Math.min(player.compensatedWorld.getMaxHeight() - 1, Math.max(bounds.maxY(), source.getY() + 5));
            for (int y = source.getY(); y <= top; y++) {
                var cell = new BlockPosition(source.getX(), y, source.getZ());
                if (!bounds.contains(cell.x(), y, cell.z()) && !movingBlocks.contains(cell) && added.add(cell)) {
                    sampled.add(cell.x(), y, cell.z());
                }
            }
        }
    }

    /** The moving blocks outside {@code bounds} and within a cell of them: one collides up to a cell from its own. */
    private static Set<BlockPosition> movingBlocksBeside(CultPlayer player, SampleBounds bounds) {
        if (player.bedrockState == null) {
            return Set.of();
        }
        Set<BlockPosition> movingBlocks = new HashSet<>();
        player.bedrockState.pistonBlocks.forEachMovingBlock(position -> {
            if (bounds.besides(position.getX(), position.getY(), position.getZ())
                    && BlockIds.is(player.compensatedWorld.getBlockStateIdAt(position), BlockIds.MOVING_PISTON)) {
                movingBlocks.add(new BlockPosition(position.getX(), position.getY(), position.getZ()));
            }
        });
        return movingBlocks;
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
                BedrockCollisionWorldBuilder.withStandingShape(
                        position, blockState, boxes, worldBuilder.bedrockState(blockState)),
                boxes,
                BedrockCollisionWorldBuilder.contactBehaviors(blockState));
    }

    private final class SampleAccumulator {
        private final CultPlayer player;
        private final BedrockCollisionOverrideCatalog geometry;
        private final BedrockCollisionShapeQuery shapeQuery;
        private final BedrockCollisionWorldBuilder worldBuilder;
        private final List<PlacedBlockCollision> blocks = new ArrayList<>();

        private SampleAccumulator(
                CultPlayer player, BedrockCollisionOverrideCatalog geometry, BedrockCollisionShapeQuery shapeQuery) {
            this.player = player;
            this.geometry = geometry;
            this.shapeQuery = shapeQuery;
            this.worldBuilder = new BedrockCollisionWorldBuilder(
                    geometry,
                    player.bedrockState == null
                            ? BedrockBlockShapeContext.NONE
                            : player.bedrockState.blockShapeContext(player));
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
            if (!air && !fluidBlocks.isFluid(blockState)) {
                blocks.add(
                        BedrockCollisionWorldBuilder.hasBedrockBehavior(blockState, geometry)
                                ? worldBuilder.block(position, blockState, shapeQuery)
                                : javaCollisionBlock(player, blockState, position, javaState, worldBuilder));
            }
            PlacedBlockCollision fluidBlock = fluidBlocks.fluidBlock(player, x, y, z, blockState, javaState);
            if (fluidBlock != null) {
                blocks.add(fluidBlock);
            }
        }
    }

    private record SampleBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        private boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        private SampleBounds grown() {
            return new SampleBounds(minX - 1, maxX + 1, minY - 1, maxY + 1, minZ - 1, maxZ + 1);
        }

        /** Whether a cell lies outside these bounds and within one cell of them. */
        private boolean besides(int x, int y, int z) {
            return !contains(x, y, z)
                    && x >= minX - 1
                    && x <= maxX + 1
                    && y >= minY - 1
                    && y <= maxY + 1
                    && z >= minZ - 1
                    && z <= maxZ + 1;
        }

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
