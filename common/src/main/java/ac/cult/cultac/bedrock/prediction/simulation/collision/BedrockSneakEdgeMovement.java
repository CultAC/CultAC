package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockResolvedMove;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollision;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.ArrayList;
import java.util.List;

public final class BedrockSneakEdgeMovement {
    private static final float BACKOFF_STEP = 0.05F;
    private static final float SUPPORT_INSET = 0.025F;
    private static final float STEP_REACH_SCALE = 1.01F;
    private static final float STOPPED_AXIS_EPSILON = Math.ulp(1.0F);
    /** How many other ends of the edge a tick tries for the moving actors that hold the sneaking player up. */
    public static final int MAX_SHOWN_ELSEWHERE = 3;
    // Past a float step at any coordinate the edge can stand at, so the actor's edge lies on the side meant.
    private static final double EDGE_SIDE = 1.0E-4D;
    private static final int MAX_BACKOFF_STEPS = 8;

    private BedrockSneakEdgeMovement() {}

    public static BedrockMoveRequest applyBeforeCollision(
            BedrockMovementState current,
            boolean sneaking,
            PlayerDimensionsState dimensions,
            double maxUpStep,
            BedrockMoveRequest request,
            BlockCollisionWorld blockCollisionWorld) {
        // Nearby support alone does not enable edge protection while airborne.
        if (!sneaking || !current.collisionFlags().onGround()) {
            return request;
        }
        WorldCollisionBox supportBox =
                supportBox(current, dimensions, maxUpStep, blockCollisionWorld.coordinateFrame());
        Vec3d move = backOffFromEdge(
                request.move(),
                supportBox,
                BedrockCollisionSweep.collisionObstacles(blockCollisionWorld),
                blockCollisionWorld.coordinateFrame());
        Vec3d velocity = request.collisionInputVelocity();
        // Partial backoff retains momentum; fully stopped axes lose it before collision.
        Vec3d nextVelocity = new Vec3d(
                Math.abs(move.x()) <= STOPPED_AXIS_EPSILON ? 0.0D : velocity.x(),
                velocity.y(),
                Math.abs(move.z()) <= STOPPED_AXIS_EPSILON ? 0.0D : velocity.z());
        Vec3d feet = current.physicalFeetPosition();
        return new BedrockMoveRequest(
                new BedrockResolvedMove(request.lavaSwimUpApplied(), move, nextVelocity),
                new Vec3d(feet.x() + move.x(), request.requestedPosition().y(), feet.z() + move.z()));
    }

    /**
     * A moving actor under a sneaking player may stand off Cult's box for it by its buffer, so the edge the client
     * holds the player back from may lie elsewhere: the move may be held back sooner, later or not at all. These are
     * the nearest places along the move's axes, at most {@link #MAX_SHOWN_ELSEWHERE}, where one such actor shown
     * there holds the move back differently from every one before it, nearest first.
     */
    public static List<Shown> shownElsewhere(
            BedrockMovementState current,
            boolean sneaking,
            PlayerDimensionsState dimensions,
            double maxUpStep,
            BedrockMoveRequest request,
            BlockCollisionWorld world) {
        if (!sneaking || !current.collisionFlags().onGround()) return List.of();
        List<PlacedBlockCollision> blocks = world.blocks();
        List<BlockCollision> obstacles = BedrockCollisionSweep.collisionObstacles(world);
        BedrockCoordinateFrame frame = world.coordinateFrame();
        WorldCollisionBox support = supportBox(current, dimensions, maxUpStep, frame);
        Vec3d move = request.move();
        List<Shown> places = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            PlacedBlockCollision block = blocks.get(index);
            float buffer = BedrockDepenetrationLimit.buffer(block);
            if (buffer <= 0.0F) continue;
            WorldCollisionBox box = box(obstacles, block);
            if (box == null || box.minY() >= support.maxY() || box.maxY() <= support.minY()) continue;
            for (float held : backOffs((float) move.x())) {
                place(places, index, buffer, support.maxX() + held - box.minX(), true);
                place(places, index, buffer, support.minX() + held - box.maxX(), true);
            }
            for (float held : backOffs((float) move.z())) {
                place(places, index, buffer, support.maxZ() + held - box.minZ(), false);
                place(places, index, buffer, support.minZ() + held - box.maxZ(), false);
            }
        }
        if (places.isEmpty()) return List.of();
        places.sort((a, b) -> Double.compare(Math.abs(a.x() + a.z()), Math.abs(b.x() + b.z())));
        List<Vec3d> ends = new ArrayList<>();
        ends.add(backOffFromEdge(move, support, obstacles, frame));
        List<Shown> shown = new ArrayList<>(MAX_SHOWN_ELSEWHERE);
        for (Shown place : places) {
            Vec3d end = backOffFromEdge(move, support, moved(obstacles, blocks.get(place.block()), place), frame);
            if (ends.contains(end)) continue;
            ends.add(end);
            shown.add(place);
            if (shown.size() == MAX_SHOWN_ELSEWHERE) break;
        }
        return shown;
    }

    /** {@code world} with the actor of {@code place} shown there, for the whole move. */
    public static BlockCollisionWorld shown(BlockCollisionWorld world, Shown place) {
        List<PlacedBlockCollision> blocks = new ArrayList<>(world.blocks());
        PlacedBlockCollision block = blocks.get(place.block());
        WorldCollisionBox box = box(BedrockCollisionSweep.collisionObstacles(world), block);
        blocks.set(
                place.block(),
                BedrockDepenetrationLimit.ofSolidActor(block)
                        .solidActor(box.move(place.x(), 0.0D, place.z()), BedrockDepenetrationLimit.buffer(block)));
        return new BlockCollisionWorld(blocks, world.coordinateFrame());
    }

    /** The actor at index {@code block} of the world's blocks, shown {@code x} or {@code z} off Cult's box for it. */
    public record Shown(int block, double x, double z) {}

    private static void place(List<Shown> places, int block, float buffer, double meets, boolean alongX) {
        // Either side of where the actor's edge meets the held-back box.
        for (double side : new double[] {-EDGE_SIDE, EDGE_SIDE}) {
            double offset = meets + side;
            if (Math.abs(offset) <= buffer)
                places.add(new Shown(block, alongX ? offset : 0.0D, alongX ? 0.0D : offset));
        }
    }

    /** The moves along one axis the edge can hold {@code move} back to, itself first. */
    private static float[] backOffs(float move) {
        float[] held = new float[MAX_BACKOFF_STEPS];
        int count = 0;
        float step = Math.signum(move) * BACKOFF_STEP;
        held[count++] = move;
        while (move != 0.0F && count < MAX_BACKOFF_STEPS) {
            move = approachZero(move, step);
            held[count++] = move;
        }
        return java.util.Arrays.copyOf(held, count);
    }

    private static WorldCollisionBox box(List<BlockCollision> obstacles, PlacedBlockCollision block) {
        for (BlockCollision obstacle : obstacles) {
            if (obstacle.block().isPresent() && obstacle.block().get() == block) return obstacle.box();
        }
        return null;
    }

    private static List<BlockCollision> moved(List<BlockCollision> obstacles, PlacedBlockCollision block, Shown place) {
        List<BlockCollision> moved = new ArrayList<>(obstacles);
        for (int index = 0; index < moved.size(); index++) {
            BlockCollision obstacle = moved.get(index);
            if (obstacle.block().isPresent() && obstacle.block().get() == block) {
                moved.set(index, new BlockCollision(obstacle.block(), obstacle.box().move(place.x(), 0.0D, place.z())));
            }
        }
        return moved;
    }

    private static Vec3d backOffFromEdge(
            Vec3d move, WorldCollisionBox supportBox, List<BlockCollision> obstacles, BedrockCoordinateFrame frame) {
        float backedOffX = (float) move.x();
        float backedOffZ = (float) move.z();
        float stepX = Math.signum(backedOffX) * BACKOFF_STEP;
        float stepZ = Math.signum(backedOffZ) * BACKOFF_STEP;

        while (backedOffX != 0.0D && canFallAt(supportBox, backedOffX, 0.0F, obstacles, frame)) {
            backedOffX = approachZero(backedOffX, stepX);
        }
        while (backedOffZ != 0.0D && canFallAt(supportBox, 0.0F, backedOffZ, obstacles, frame)) {
            backedOffZ = approachZero(backedOffZ, stepZ);
        }
        while ((backedOffX != 0.0D || backedOffZ != 0.0D)
                && canFallAt(supportBox, backedOffX, backedOffZ, obstacles, frame)) {
            if (Math.abs(backedOffX) <= BACKOFF_STEP) {
                backedOffX = 0.0F;
            } else {
                backedOffX -= stepX;
            }
            if (Math.abs(backedOffZ) <= BACKOFF_STEP) {
                backedOffZ = 0.0F;
            } else {
                backedOffZ -= stepZ;
            }
        }
        return new Vec3d(backedOffX, move.y(), backedOffZ);
    }

    private static boolean canFallAt(
            WorldCollisionBox supportBox,
            float deltaX,
            float deltaZ,
            List<BlockCollision> obstacles,
            BedrockCoordinateFrame frame) {
        WorldCollisionBox stepDownBox = BedrockCollisionSweep.moveBedrock(supportBox, deltaX, 0.0D, deltaZ, frame);
        for (BlockCollision obstacle : obstacles) {
            if (stepDownBox.intersects(obstacle.box())) {
                return false;
            }
        }
        return true;
    }

    private static WorldCollisionBox supportBox(
            BedrockMovementState current,
            PlayerDimensionsState dimensions,
            double maxUpStep,
            BedrockCoordinateFrame frame) {
        WorldCollisionBox box = current.collisionBox(dimensions);
        double minX = frame.roundX(box.minX() + SUPPORT_INSET);
        double maxX = frame.roundX(box.maxX() - SUPPORT_INSET);
        double minZ = frame.roundZ(box.minZ() + SUPPORT_INSET);
        double maxZ = frame.roundZ(box.maxZ() - SUPPORT_INSET);
        // A narrow actor's inset collapses to its center instead of inverting.
        if (minX > maxX) {
            minX = maxX = frame.originX() + (double) ((frame.localX(box.minX()) + frame.localX(box.maxX())) * 0.5F);
        }
        if (minZ > maxZ) {
            minZ = maxZ = frame.originZ() + (double) ((frame.localZ(box.minZ()) + frame.localZ(box.maxZ())) * 0.5F);
        }
        float stepDown = (float) maxUpStep * STEP_REACH_SCALE;
        return new WorldCollisionBox(
                minX, (float) (box.minY() - stepDown), minZ, maxX, (float) (box.maxY() - stepDown), maxZ);
    }

    private static float approachZero(float value, float step) {
        if (Math.abs(value) <= BACKOFF_STEP) {
            return 0.0F;
        }
        return value - step;
    }
}
