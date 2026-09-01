package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class BedrockLiquidGeometry {
    private static final Vec3d WATER_AABB_SHRINK = new Vec3d(0.001D, 0.401D, 0.001D);
    private static final Vec3d LAVA_AABB_SHRINK = new Vec3d(0.1D, 0.4D, 0.1D);
    private static final double LIQUID_HEIGHT_UNIT = 1.0D / 9.0D;

    private BedrockLiquidGeometry() {
    }

    public static WorldCollisionBox playerBox(Vec3d physicalFeetPosition, PlayerDimensionsState dimensions) {
        double radius = dimensions.radius();
        return new WorldCollisionBox(
            physicalFeetPosition.x() - radius,
            physicalFeetPosition.y(),
            physicalFeetPosition.z() - radius,
            physicalFeetPosition.x() + radius,
            physicalFeetPosition.y() + dimensions.height(),
            physicalFeetPosition.z() + radius
        );
    }

    public static WorldCollisionBox playerLiquidBox(
        Vec3d physicalFeetPosition,
        PlayerDimensionsState dimensions,
        BedrockLiquidKind liquidKind
    ) {
        return liquidActorBox(playerBox(physicalFeetPosition, dimensions), liquidKind);
    }

    public static WorldCollisionBox liquidActorBox(WorldCollisionBox actorBox, BedrockLiquidKind liquidKind) {
        Vec3d shrink = switch (liquidKind) {
            case WATER -> WATER_AABB_SHRINK;
            case LAVA -> LAVA_AABB_SHRINK;
            case NONE -> Vec3d.ZERO;
        };
        return shrinkActorBox(actorBox, shrink);
    }

    public static boolean intersectsLiquid(
        BlockCollisionWorld blockWorld,
        WorldCollisionBox actorBox,
        BedrockLiquidKind liquidKind
    ) {
        if (blockWorld.isEmpty()) {
            return false;
        }
        for (PlacedBlockCollision block : liquidBlocks(blockWorld, liquidKind)) {
            if (intersectsBlock(block.position(), actorBox)) {
                return true;
            }
        }
        return false;
    }

    public static boolean intersectsLiquidSurface(
        BlockCollisionWorld blockWorld,
        WorldCollisionBox actorBox,
        BedrockLiquidKind liquidKind
    ) {
        if (blockWorld.isEmpty()) {
            return false;
        }
        for (PlacedBlockCollision block : liquidBlocks(blockWorld, liquidKind)) {
            BlockPosition position = block.position();
            if (position.x() + 1.0D <= actorBox.minX()
                || position.x() >= actorBox.maxX()
                || position.z() + 1.0D <= actorBox.minZ()
                || position.z() >= actorBox.maxZ()) {
                continue;
            }
            OptionalDouble surfaceY = liquidSurfaceY(block);
            if (surfaceY.isPresent()
                && block.position().y() < actorBox.maxY()
                && actorBox.minY() < surfaceY.getAsDouble()) {
                return true;
            }
        }
        return false;
    }

    public static boolean liquidPointInBlock(
        BlockCollisionWorld blockWorld,
        double x,
        double y,
        double z,
        BedrockLiquidKind liquidKind
    ) {
        BlockPosition position = blockPosition(x, y, z);
        return blockWorld.blockAt(position)
            .filter(block -> liquidKind(block) == liquidKind)
            .flatMap(BedrockLiquidGeometry::liquidBlockBox)
            // The vanilla liquid material test excludes a point exactly on
            // the liquid surface.
            .map(liquidBox -> y < liquidBox.maxY())
            .orElse(false);
    }

    public static boolean centerTopAndBottomNotInAir(
        BlockCollisionWorld blockWorld,
        Vec3d stateVectorPosition,
        PlayerDimensionsState dimensions
    ) {
        double halfHeight = dimensions.height() * 0.5D;
        BlockPosition top = blockPosition(
            stateVectorPosition.x(),
            stateVectorPosition.y() + halfHeight,
            stateVectorPosition.z()
        );
        BlockPosition bottom = blockPosition(
            stateVectorPosition.x(),
            stateVectorPosition.y() - halfHeight,
            stateVectorPosition.z()
        );
        return !isAir(blockWorld, top) && !isAir(blockWorld, bottom);
    }

    public static WorldCollisionBox fullBlockBox(BlockPosition position) {
        return new WorldCollisionBox(
            position.x(),
            position.y(),
            position.z(),
            position.x() + 1.0D,
            position.y() + 1.0D,
            position.z() + 1.0D
        );
    }

    public static Optional<WorldCollisionBox> liquidBlockBox(PlacedBlockCollision block) {
        if (liquidKind(block) == BedrockLiquidKind.NONE) {
            return Optional.empty();
        }
        OptionalDouble surfaceY = liquidSurfaceY(block);
        return surfaceY.isPresent()
            ? Optional.of(new WorldCollisionBox(
                block.position().x(),
                block.position().y(),
                block.position().z(),
                block.position().x() + 1.0D,
                surfaceY.getAsDouble(),
                block.position().z() + 1.0D))
            : Optional.of(fullBlockBox(block.position()));
    }

    public static BedrockLiquidKind liquidKind(PlacedBlockCollision block) {
        if (isWaterBlock(block)) {
            return BedrockLiquidKind.WATER;
        }
        if (isLavaBlock(block)) {
            return BedrockLiquidKind.LAVA;
        }
        return BedrockLiquidKind.NONE;
    }

    public static boolean isWaterBlock(PlacedBlockCollision block) {
        return "minecraft:water".equals(block.bedrockIdentifier())
            || "minecraft:flowing_water".equals(block.bedrockIdentifier())
            || "minecraft:bubble_column".equals(block.bedrockIdentifier());
    }

    public static boolean isLavaBlock(PlacedBlockCollision block) {
        return "minecraft:lava".equals(block.bedrockIdentifier())
            || "minecraft:flowing_lava".equals(block.bedrockIdentifier());
    }

    public static OptionalInt liquidDepth(PlacedBlockCollision block) {
        Object depth = block.bedrockState().get("liquid_depth");
        if (depth != null) {
            return OptionalInt.of(asIntState(depth, "liquid_depth"));
        }
        Optional<String> javaLevel = Optional.ofNullable(block.javaStateProperties().level());
        if (javaLevel.isPresent()) {
            return OptionalInt.of(Integer.parseInt(javaLevel.get()));
        }
        return "minecraft:water".equals(block.bedrockIdentifier())
            || "minecraft:lava".equals(block.bedrockIdentifier())
            ? OptionalInt.of(0)
            : OptionalInt.empty();
    }

    public static int normalizedLiquidDepth(int depth) {
        return depth > 7 ? 0 : depth;
    }

    private static OptionalDouble liquidSurfaceY(PlacedBlockCollision block) {
        OptionalInt depth = liquidDepth(block);
        if (depth.isEmpty()) {
            return OptionalDouble.empty();
        }
        int value = depth.getAsInt() < 8 ? depth.getAsInt() + 1 : 1;
        double heightFromDepth = value * LIQUID_HEIGHT_UNIT;
        return OptionalDouble.of(block.position().y() + 1.0D - (heightFromDepth - LIQUID_HEIGHT_UNIT));
    }

    private static BlockPosition blockPosition(double x, double y, double z) {
        return new BlockPosition((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private static boolean isAir(BlockCollisionWorld blockWorld, BlockPosition position) {
        return blockWorld.blockAt(position).isEmpty();
    }

    private static WorldCollisionBox shrinkActorBox(WorldCollisionBox actorBox, Vec3d shrink) {
        double centerX = (actorBox.minX() + actorBox.maxX()) * 0.5D;
        double centerY = (actorBox.minY() + actorBox.maxY()) * 0.5D;
        double centerZ = (actorBox.minZ() + actorBox.maxZ()) * 0.5D;
        return new WorldCollisionBox(
            Math.min(actorBox.minX() + shrink.x(), centerX),
            Math.min(actorBox.minY() + shrink.y(), centerY),
            Math.min(actorBox.minZ() + shrink.z(), centerZ),
            Math.max(actorBox.maxX() - shrink.x(), centerX),
            Math.max(actorBox.maxY() - shrink.y(), centerY),
            Math.max(actorBox.maxZ() - shrink.z(), centerZ)
        );
    }

    private static List<PlacedBlockCollision> liquidBlocks(
        BlockCollisionWorld blockWorld,
        BedrockLiquidKind kind
    ) {
        return switch (kind) {
            case WATER -> blockWorld.waterBlocks();
            case LAVA -> blockWorld.lavaBlocks();
            case NONE -> List.of();
        };
    }

    static boolean intersectsBlock(BlockPosition position, WorldCollisionBox box) {
        return position.x() + 1.0D > box.minX()
            && position.x() < box.maxX()
            && position.y() + 1.0D > box.minY()
            && position.y() < box.maxY()
            && position.z() + 1.0D > box.minZ()
            && position.z() < box.maxZ();
    }

    private static int asIntState(Object value, String stateName) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            return Integer.parseInt(text);
        }
        throw new IllegalArgumentException("Bedrock state " + stateName + " must be numeric");
    }
}
