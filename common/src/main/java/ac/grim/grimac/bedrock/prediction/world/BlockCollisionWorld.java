package ac.grim.grimac.bedrock.prediction.world;

import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BlockCollisionWorld {
    public static final BlockCollisionWorld EMPTY = new BlockCollisionWorld(List.of());

    private final List<PlacedBlockCollision> blocks;
    private final Map<BlockPosition, PlacedBlockCollision> blocksByPosition;
    private final Map<BlockPosition, PlacedBlockCollision> liquidBlocksByPosition;
    private final List<PlacedBlockCollision> waterBlocks;
    private final List<PlacedBlockCollision> lavaBlocks;
    private final List<WorldCollisionBox> collisionBoxes;
    private final List<BlockCollision> collisions;

    public BlockCollisionWorld(List<PlacedBlockCollision> blocks) {
        this.blocks = List.copyOf(blocks);
        WorldIndex index = WorldIndex.from(this.blocks);
        this.blocksByPosition = index.blocksByPosition();
        this.liquidBlocksByPosition = index.liquidBlocksByPosition();
        this.waterBlocks = index.waterBlocks();
        this.lavaBlocks = index.lavaBlocks();
        this.collisionBoxes = index.collisionBoxes();
        this.collisions = index.collisions();
    }

    public List<PlacedBlockCollision> blocks() {
        return blocks;
    }

    public List<BlockCollision> collisions() {
        return collisions;
    }

    public Map<BlockPosition, PlacedBlockCollision> blocksByPosition() {
        return blocksByPosition;
    }

    public Map<BlockPosition, PlacedBlockCollision> liquidBlocksByPosition() {
        return liquidBlocksByPosition;
    }

    public List<PlacedBlockCollision> waterBlocks() {
        return waterBlocks;
    }

    public List<PlacedBlockCollision> lavaBlocks() {
        return lavaBlocks;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public List<WorldCollisionBox> collisionBoxes() {
        return collisionBoxes;
    }

    public Optional<PlacedBlockCollision> blockAt(BlockPosition position) {
        return Optional.ofNullable(blocksByPosition.get(position));
    }

    private static LiquidKind liquidKind(String identifier) {
        return switch (identifier) {
            case "minecraft:water", "minecraft:flowing_water", "minecraft:bubble_column" -> LiquidKind.WATER;
            case "minecraft:lava", "minecraft:flowing_lava" -> LiquidKind.LAVA;
            default -> LiquidKind.NONE;
        };
    }

    private static WorldCollisionBox bedrockBox(WorldCollisionBox box) {
        return new WorldCollisionBox(
                f(box.minX()),
                f(box.minY()),
                f(box.minZ()),
                f(box.maxX()),
                f(box.maxY()),
                f(box.maxZ()));
    }

    private static double f(double value) {
        return (double) (float) value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof BlockCollisionWorld world && blocks.equals(world.blocks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blocks);
    }

    @Override
    public String toString() {
        return "BlockCollisionWorld[blocks=" + blocks + ']';
    }

    private enum LiquidKind {
        NONE,
        WATER,
        LAVA
    }

    private record WorldIndex(
            Map<BlockPosition, PlacedBlockCollision> blocksByPosition,
            Map<BlockPosition, PlacedBlockCollision> liquidBlocksByPosition,
            List<PlacedBlockCollision> waterBlocks,
            List<PlacedBlockCollision> lavaBlocks,
            List<WorldCollisionBox> collisionBoxes,
            List<BlockCollision> collisions
    ) {
        private static WorldIndex from(List<PlacedBlockCollision> blocks) {
            if (blocks.isEmpty()) {
                return new WorldIndex(Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of());
            }
            Map<BlockPosition, PlacedBlockCollision> byPosition = HashMap.newHashMap(blocks.size());
            Map<BlockPosition, PlacedBlockCollision> liquidsByPosition = HashMap.newHashMap(blocks.size());
            List<PlacedBlockCollision> water = new ArrayList<>();
            List<PlacedBlockCollision> lava = new ArrayList<>();
            List<WorldCollisionBox> boxes = new ArrayList<>();
            List<BlockCollision> collisions = new ArrayList<>();
            for (PlacedBlockCollision block : blocks) {
                byPosition.putIfAbsent(block.position(), block);
                LiquidKind kind = liquidKind(block.bedrockIdentifier());
                if (kind == LiquidKind.WATER) {
                    liquidsByPosition.put(block.position(), block);
                    water.add(block);
                } else if (kind == LiquidKind.LAVA) {
                    liquidsByPosition.put(block.position(), block);
                    lava.add(block);
                }
                for (WorldCollisionBox box : block.collisionBoxes()) {
                    boxes.add(box);
                    collisions.add(new BlockCollision(block, bedrockBox(box)));
                }
            }
            return new WorldIndex(
                    Collections.unmodifiableMap(byPosition),
                    Collections.unmodifiableMap(liquidsByPosition),
                    List.copyOf(water),
                    List.copyOf(lava),
                    List.copyOf(boxes),
                    List.copyOf(collisions));
        }
    }
}
