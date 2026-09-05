package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PowderSnowContactState;
import java.util.Objects;

public final class BedrockPowderSnowContactResolver {
    private static final double FEET_SURFACE_EPSILON = 1.0E-6D;
    private static final double INSIDE_BLOCK_MIN_EPSILON = 0.001D;

    private BedrockPowderSnowContactResolver() {
    }

    public static boolean inPowderSnow(BedrockMovementContext context, Vec3d physicalFeetPosition) {
        Objects.requireNonNull(context, "context");
        return fromBlockWorld(
            context.playerDimensionsState().width(),
            context.playerDimensionsState().height(),
            physicalFeetPosition,
            context.worldState().blockCollisionWorld(),
            context.equipmentState().leatherBoots()
        ).actorIntersection();
    }

    public static boolean surfaceSink(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        boolean previousSurfaceSink
    ) {
        Objects.requireNonNull(context, "context");
        if (context.equipmentState().leatherBoots()) {
            return false;
        }
        PowderSnowContactState contact = fromBlockWorld(
            context.playerDimensionsState().width(),
            context.playerDimensionsState().height(),
            physicalFeetPosition,
            context.worldState().blockCollisionWorld(),
            false
        );
        return contact.feetSurface() || (previousSurfaceSink && contact.actorIntersection());
    }

    public static PowderSnowContactState fromBlockWorld(
        double actorWidth,
        double actorHeight,
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld
    ) {
        return fromBlockWorld(actorWidth, actorHeight, physicalFeetPosition, blockWorld, false);
    }

    public static PowderSnowContactState fromBlockWorld(
        double actorWidth,
        double actorHeight,
        Vec3d physicalFeetPosition,
        BlockCollisionWorld blockWorld,
        boolean canStandOnSnow
    ) {
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(blockWorld, "blockWorld");
        if (blockWorld.isEmpty()) {
            return PowderSnowContactState.NONE;
        }
        WorldCollisionBox actorBox = actorBox(actorWidth, actorHeight, physicalFeetPosition);
        WorldCollisionBox insideBlockQueryBox = insideBlockQueryBox(actorBox);
        boolean intersects = false;
        boolean feetSurface = false;
        boolean rawAtFeetAscendable = false;
        int feetBlockX = (int) Math.floor(physicalFeetPosition.x());
        int feetBlockY = (int) Math.floor(physicalFeetPosition.y());
        int feetBlockZ = (int) Math.floor(physicalFeetPosition.z());
        for (PlacedBlockCollision block : blockWorld.blocks()) {
            if (!block.hasContactBehavior(BlockContactBehavior.POWDER_SNOW)) {
                continue;
            }

            if (canStandOnSnow
                    && block.position().x() == feetBlockX
                    && block.position().y() == feetBlockY
                    && block.position().z() == feetBlockZ) {
                rawAtFeetAscendable = true;
            }
            if (!intersects) {
                for (WorldCollisionBox insideBlockContactBox : block.insideBlockContactBoxes()) {
                    if (insideBlockContactBox.intersects(insideBlockQueryBox)) {
                        intersects = true;
                        break;
                    }
                }
            }
            for (WorldCollisionBox contactBox : block.contactBoxes()) {
                if (touchesFeetSurface(contactBox, physicalFeetPosition, actorWidth)) {
                    feetSurface = true;
                }
                if (intersects && feetSurface && rawAtFeetAscendable) {
                    return new PowderSnowContactState(true, true, true);
                }
            }
        }
        return new PowderSnowContactState(intersects, feetSurface, rawAtFeetAscendable);
    }

    private static WorldCollisionBox insideBlockQueryBox(WorldCollisionBox actorBox) {
        return new WorldCollisionBox(
            actorBox.minX() + INSIDE_BLOCK_MIN_EPSILON,
            actorBox.minY() + INSIDE_BLOCK_MIN_EPSILON,
            actorBox.minZ() + INSIDE_BLOCK_MIN_EPSILON,
            actorBox.maxX() - INSIDE_BLOCK_MIN_EPSILON,
            actorBox.maxY() - INSIDE_BLOCK_MIN_EPSILON,
            actorBox.maxZ() - INSIDE_BLOCK_MIN_EPSILON
        );
    }

    private static boolean touchesFeetSurface(
        WorldCollisionBox blockBox,
        Vec3d physicalFeetPosition,
        double actorWidth
    ) {
        double radius = actorWidth * 0.5D;
        return Math.abs(blockBox.maxY() - physicalFeetPosition.y()) <= FEET_SURFACE_EPSILON
            && blockBox.maxX() > physicalFeetPosition.x() - radius + FEET_SURFACE_EPSILON
            && blockBox.minX() < physicalFeetPosition.x() + radius - FEET_SURFACE_EPSILON
            && blockBox.maxZ() > physicalFeetPosition.z() - radius + FEET_SURFACE_EPSILON
            && blockBox.minZ() < physicalFeetPosition.z() + radius - FEET_SURFACE_EPSILON;
    }

    private static WorldCollisionBox actorBox(double actorWidth, double actorHeight, Vec3d physicalFeetPosition) {
        if (!Double.isFinite(actorWidth) || actorWidth < 0.0D) {
            throw new IllegalArgumentException("powder snow actor width must be finite and non-negative");
        }
        if (!Double.isFinite(actorHeight) || actorHeight < 0.0D) {
            throw new IllegalArgumentException("powder snow actor height must be finite and non-negative");
        }
        double radius = actorWidth * 0.5D;
        return new WorldCollisionBox(
            physicalFeetPosition.x() - radius,
            physicalFeetPosition.y(),
            physicalFeetPosition.z() - radius,
            physicalFeetPosition.x() + radius,
            physicalFeetPosition.y() + actorHeight,
            physicalFeetPosition.z() + radius
        );
    }
}
