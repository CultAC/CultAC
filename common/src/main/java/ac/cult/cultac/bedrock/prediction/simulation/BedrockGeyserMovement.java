package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockBlockMetadata;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;

/** Geysers change carried velocity after movement; their columns arm the following movement ticks. */
public final class BedrockGeyserMovement {
    public static final int MAX_COLUMN_HEIGHT = 24;
    private static final int EFFECT_TICKS = 100;
    private static final float[] CAPS = {0.4F, 0.5F, 0.6F, 0.7F};

    private BedrockGeyserMovement() {}

    static BedrockMovementState apply(BedrockMovementResult movement, BedrockMovementState state) {
        boolean actorTick = state.simulationTick() != movement.previousState().simulationTick();
        var context = movement.movementContext();
        var world = context.worldState().blockCollisionWorld();
        int remaining = state.geyserBoostTicks();
        if (remaining == 0 && world.geyserBlocks().isEmpty()) return state;
        var box = state.collisionBox(context.playerDimensionsState());
        if (actorTick && remaining != 0 && !context.movementAbilityFlying()) {
            var frame = world.coordinateFrame();
            int x = frame.originX() + floor(center(frame.localX(box.minX()), frame.localX(box.maxX())));
            int z = frame.originZ() + floor(center(frame.localZ(box.minZ()), frame.localZ(box.maxZ())));
            int y = floor((float) box.minY());
            for (int down = 0; down <= MAX_COLUMN_HEIGHT; down++) {
                var position = new BlockPosition(x, y - down, z);
                var block = world.blocksByPosition().get(position);
                if (passable(block)) continue;
                int depth = waterDepth(world, block);
                if (depth > 0 && box.minY() < position.y() + 1 + depth * 6) {
                    var velocity = state.velocity();
                    if ((float) velocity.y() < CAPS[depth - 1]) {
                        state = state.withVelocityAndCollisionFlags(
                                new Vec3d(velocity.x(), (float) velocity.y() + 0.2F, velocity.z()),
                                state.collisionFlags());
                    }
                }
                break;
            }
        }
        if (actorTick && remaining > 0) remaining--;
        // Column contact refreshes the effect even when flying or when the box center is outside the column.
        for (var block : world.geyserBlocks()) {
            int depth = waterDepth(world, block);
            if (depth == 0) continue;
            var p = block.position();
            var column = new WorldCollisionBox(p.x(), p.y(), p.z(), p.x() + 1, p.y() + depth * 6, p.z() + 1);
            if (box.intersects(world.coordinateFrame().roundBox(column))) {
                remaining = EFFECT_TICKS;
                break;
            }
        }
        return state.withGeyserBoostTicks(remaining);
    }

    private static float center(double min, double max) {
        return ((float) max - (float) min) * 0.5F + (float) min;
    }

    private static int floor(float value) {
        return (int) Math.floor(value);
    }

    private static int waterDepth(BlockCollisionWorld world, PlacedBlockCollision sulfur) {
        if (!"minecraft:potent_sulfur".equals(sulfur.bedrockIdentifier())) return 0;
        Object phase = sulfur.bedrockState().get("potent_sulfur_state");
        if (!"erupting".equals(phase) && !"continuous".equals(phase)) return 0;
        var base = sulfur.position();
        int depth = 0;
        for (int up = 1; up <= 4; up++) {
            var position = new BlockPosition(base.x(), base.y() + up, base.z());
            var block = world.blocksByPosition().get(position);
            var liquid = world.liquidBlocksByPosition().get(position);
            if (sourceWater(block) || sourceWater(liquid) && passable(block)) depth++;
            else break;
        }
        if (depth == 0) return 0;
        var outlet = new BlockPosition(base.x(), base.y() + depth + 1, base.z());
        return sourceLiquid(world.blocksByPosition().get(outlet))
                        || sourceLiquid(world.liquidBlocksByPosition().get(outlet))
                        || !passable(world.blocksByPosition().get(outlet))
                ? 0
                : depth;
    }

    private static boolean passable(PlacedBlockCollision block) {
        if (block == null) return true;
        Object independent = block.bedrockState().get(BedrockBlockMetadata.GEYSER_PASSABLE);
        return independent instanceof Boolean value
                ? value
                : block.collisionBoxes().isEmpty();
    }

    private static boolean water(PlacedBlockCollision block) {
        return block != null
                && ("minecraft:water".equals(block.bedrockIdentifier())
                        || "minecraft:flowing_water".equals(block.bedrockIdentifier()));
    }

    private static boolean sourceWater(PlacedBlockCollision block) {
        return water(block)
                && block.bedrockState().get("liquid_depth") instanceof Number depth
                && depth.intValue() == 0;
    }

    private static boolean sourceLiquid(PlacedBlockCollision block) {
        return block != null
                && (water(block)
                        || "minecraft:lava".equals(block.bedrockIdentifier())
                        || "minecraft:flowing_lava".equals(block.bedrockIdentifier()))
                && block.bedrockState().get("liquid_depth") instanceof Number depth
                && depth.intValue() == 0;
    }
}
