package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockBlockFriction;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;

/** Boat control runs before movement; gravity and buoyancy run after collision. */
public final class BedrockBoatMovement {
    private BedrockBoatMovement() { }

    public record Surface(float friction, boolean inAir, int submergedTicks) { }
    public record Step(BedrockBoatState state, Vec3d velocity, float yaw, float friction) { }

    public static Surface surface(BedrockMovementState state, BlockCollisionWorld world) {
        var boat = state.boat();
        Vec3d origin = origin(state, state.physicalFeetPosition());
        BlockPosition at = blockPosition(origin);
        if (canFloat(state, world, origin)) {
            return new Surface(0.9F, false, boat.properties().outOfControl() ? boat.submergedTicks() : 0);
        }
        if (submerged(state, world, at)) return new Surface(0.9F, false, boat.submergedTicks() + 1);
        var block = world.blockAt(at).orElse(null);
        var belowPosition = new BlockPosition(at.x(), at.y() - 1, at.z());
        var below = world.blockAt(belowPosition).orElse(null);
        var liquid = liquidBlock(world, at);
        if (liquid != null && BedrockLiquidGeometry.isWaterBlock(liquid)) return new Surface(0.9F, false, boat.submergedTicks());
        boolean ground = state.collisionFlags().onGround();
        if (liquid == null || liquid.bedrockIdentifier().equals("minecraft:air")
                || liquid.bedrockIdentifier().equals("minecraft:snow_layer")) {
            if (ground) return new Surface(friction(below), false, boat.submergedTicks());
            var liquidBelow = liquidBlock(world, belowPosition);
            if (liquidBelow != null && BedrockLiquidGeometry.isWaterBlock(liquidBelow)) return new Surface(0.9F, false, boat.submergedTicks());
        } else if (ground) {
            return new Surface(friction(block.collisionBoxes().isEmpty() ? below : block), false, boat.submergedTicks());
        }
        return new Surface(boat.properties().leashed() ? 0.91F : 1.0F, true, boat.submergedTicks());
    }

    private static float friction(PlacedBlockCollision block) {
        // Air is omitted from the sampled world, including beneath a grounded boat at an edge.
        return (float) (block == null ? BedrockBlockFriction.AIR : BedrockBlockFriction.from(block));
    }

    public static Step control(BedrockMovementState state, Vec3d velocity, Surface surface, Vec3d input) {
        var boat = state.boat();
        float side = (float) input.x(), forward = (float) input.z();
        var left = boat.left();
        var right = boat.right();
        if (boat.analogPaddles()) {
            float magnitude = (float) Math.sqrt(forward * forward + side * side);
            if (forward < 0) { magnitude *= -0.15F; side = -side; }
            left = left.analog(boat.paddleTick(), magnitude * (1.0F - Math.clamp(side, 0.0F, 1.0F)));
            right = right.analog(boat.paddleTick(), magnitude * (1.0F + Math.clamp(side, -1.0F, 0.0F)));
        } else {
            left = left.digital(boat.paddleTick(), side != 0);
            right = right.digital(boat.paddleTick(), forward != 0);
        }
        float friction = surface.friction();
        float x = (float) velocity.x() * friction, z = (float) velocity.z() * friction;
        float angular = boat.angularVelocity() * friction;
        float yaw = state.inputFrame().yaw();
        if (surface.submergedTicks() <= 24) {
            float leftForce = surface.inAir() ? 0 : left.strength() * 0.01375F;
            float rightForce = surface.inAir() ? 0 : right.strength() * 0.01375F;
            float torque = 3.0F * leftForce - 3.0F * rightForce;
            float force = leftForce + rightForce;
            if (x * x + z * z < 0.1F * 0.1F && torque != 0) {
                force *= friction;
                torque *= 1.6F;
            }
            angular = (angular + torque * 10.0F) * friction;
            yaw += angular;
            float angle = (90.0F - yaw) * BedrockMath.DEGREES_TO_RADIANS;
            x = (x + force * (float) Math.sin(angle)) * friction;
            z = (z + force * (float) Math.cos(angle)) * friction;
        }
        if (surface.submergedTicks() <= 24) {
            if (left.strength() == 0) left = new BedrockBoatState.Paddle(left.lastStroke(), -1, 0);
            if (right.strength() == 0) right = new BedrockBoatState.Paddle(right.lastStroke(), -1, 0);
        }
        var next = new BedrockBoatState(boat.actor(), boat.properties(), angular, surface.submergedTicks(),
                boat.paddleTick() + 1, left, right, boat.analogPaddles());
        return new Step(next, new Vec3d(x, velocity.y(), z), yaw, friction);
    }

    public static Vec3d afterMove(BedrockMovementState state, BlockCollisionWorld world, Vec3d feet, Vec3d velocity) {
        var properties = state.boat().properties();
        float y = (float) velocity.y();
        if (properties.buoyant()) {
            if (properties.gravity()) y = (y - 0.04F) * 0.98F;
            Vec3d origin = origin(state, feet);
            boolean submerged = submerged(state, world, blockPosition(origin));
            if (canFloat(state, world, origin) || submerged) {
                float fraction = (float) origin.y() - (float) Math.floor((float) origin.y());
                float depth = submerged ? 1.0F : Math.clamp((properties.baseBuoyancy() - fraction) * 0.9F + 0.1F, 0.0F, 1.0F);
                if (depth != 0) y = Math.min((depth - 0.1F) * 0.15F, y * 0.7F + 0.05F);
            }
        }
        return new Vec3d(velocity.x(), y, velocity.z());
    }

    private static boolean canFloat(BedrockMovementState state, BlockCollisionWorld world, Vec3d origin) {
        BlockPosition at = blockPosition(origin);
        if (!validLiquid(state, world, at) || validLiquid(state, world, new BlockPosition(at.x(), at.y() + 1, at.z()))) return false;
        var block = liquidBlock(world, at);
        return origin.y() < BedrockLiquidGeometry.liquidPointSurfaceY(block);
    }

    private static boolean submerged(BedrockMovementState state, BlockCollisionWorld world, BlockPosition at) {
        return validLiquid(state, world, at) && validLiquid(state, world, new BlockPosition(at.x(), at.y() + 1, at.z()));
    }

    private static boolean validLiquid(BedrockMovementState state, BlockCollisionWorld world, BlockPosition at) {
        var block = liquidBlock(world, at);
        return state.boat().properties().buoyant() && block != null
                && state.boat().properties().liquids().contains(block.bedrockIdentifier());
    }

    private static PlacedBlockCollision liquidBlock(BlockCollisionWorld world, BlockPosition at) {
        // Waterlogged blocks keep their liquid separate from their collision block.
        var liquid = world.liquidBlocksByPosition().get(at);
        return liquid != null ? liquid : world.blocksByPosition().get(at);
    }

    private static Vec3d origin(BedrockMovementState state, Vec3d feet) {
        return new Vec3d(feet.x(), (float) (feet.y() + state.packetYOffset()), feet.z());
    }

    private static BlockPosition blockPosition(Vec3d point) {
        return new BlockPosition((int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z()));
    }

    public static Vec3d liquidVelocity(BedrockMovementState state, Vec3d velocity,
                                      ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext context) {
        var flow = BedrockFluidMovementSourceResolver.fromContext(context, state.physicalFeetPosition().y()).appliedDelta();
        return new Vec3d((float) velocity.x() + (float) flow.x(),
                (float) velocity.y() + (float) flow.y(), (float) velocity.z() + (float) flow.z());
    }

    static BedrockFrameState prepare(BedrockTravelInput input, BedrockMobJumpComponentState mobJump) {
        var current = input.previousState();
        var world = input.worldSnapshot().blockCollisionWorld();
        var step = control(current, liquidVelocity(current, input.startingVelocity(), input.worldSnapshot().movementContext()),
                surface(current, world),
                input.replayControl() == null ? Vec3d.ZERO : input.replayControl());
        var frame = current.withRotation(step.yaw(), input.inputFrame().pitch()).inputFrame();
        frame = new ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame(input.inputFrame().clientTick(),
                frame.yaw(), frame.pitch(), false, false, false);
        input = new BedrockTravelInput(current, frame, frame.intent(), input.worldSnapshot(),
                input.scaffoldingVerticalBranch(), input.startingVelocity(), input.options(), input.replayControl());
        var context = input.worldSnapshot().movementContext();
        var facts = new BedrockFrameFacts(context, current.boundingBoxMode(), current.playerDimensions(),
                new BedrockSwimmingMovement.SwimmingState(false, false, 0),
                BedrockClimbMovement.resolveSurface(ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact.NONE, false, false),
                context.inWater(), false, false, false, 0, input.worldSnapshot().initialBlockMovementSlowdownState(),
                input.worldSnapshot().honeySlideState(), new ac.cult.cultac.bedrock.prediction.world.StandingSurfaceState(java.util.Set.of()));
        return new BedrockFrameState(input, frame.intent(), facts, new BedrockGlideState(false, false, false, false),
                new BedrockTravelBranch(new BedrockTravelSelection(BedrockTravelType.NONE, Medium.AIR)), false,
                new BedrockTravelInputControl.InputControlState(1, false, 0, false, false),
                new BedrockRiptideMovement.Step(0, false, 0), mobJump, step.velocity(), BedrockMobJump.NONE,
                current.dolphinBoost(), false, current.cameraWater(), null, step);
    }
}
