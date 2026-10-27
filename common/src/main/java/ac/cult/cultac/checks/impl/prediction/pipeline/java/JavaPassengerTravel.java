package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.ClientKeyInput;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.Friction;
import ac.cult.cultac.utils.nmsutil.JavaPassengerFluids;
import ac.cult.cultac.utils.nmsutil.RootVehicleInterpolationCollision;

/** The passenger's ordinary travel between Entity#rideTick's zeroing and Entity#positionRider. */
public final class JavaPassengerTravel {
    private JavaPassengerTravel() {}

    public record Result(JavaPassState state, int jumpDelay) {}

    public static Result tick(
            CultPlayer player, SimulationContext context, JavaPassState prior, int jumpDelay,
            SimpleCollisionBox vehicleBox, Vec3 movementInput, boolean jumping) {
        var self = player.compensatedEntities.getSelf();
        Vec3 start = context.getStart();
        var fluids = JavaPassengerFluids.sample(player, context.getFromActualPose(), start, Vec3.ZERO,
                vehicleBox, start.y + context.getPose().eyeHeight * context.getScale());
        boolean water = fluids.water(), lava = fluids.lava();
        var keys = player.packetStateData.knownInput;
        // CheckManagerListener already reconciled aiStep's local flag. A passive passenger can stop
        // without sendIsSprintingIfNeeded, so never overwrite the independent sprint announcement here.
        boolean jump = jumping && self.actionAlive && !player.isInBed;
        Vec3 velocity = fluids.velocity();
        if (water && keys.shift() && !player.isFlying) velocity = velocity.add(0, -0.04F, 0);
        if (player.isFlying && player.cameraEntity.isSelf()) {
            int up = (keys.jump() ? 1 : 0) - (keys.shift() ? 1 : 0);
            if (up != 0) velocity = velocity.add(0, up * player.flySpeed * 3.0F, 0);
        }
        velocity = VelocityTransformer.applyMovementThreshold(new PredVector(velocity), context.getVersion(), true);
        boolean ground = context.getVersion().isOlderThan(ClientVersion.V_1_21_4) && prior.onGround();
        jumpDelay = Math.max(0, jumpDelay - 1);
        if (!jump || player.isFlying) jumpDelay = 0;
        else {
            double height = lava ? fluids.lavaHeight() : fluids.waterHeight();
            double threshold = context.getPose().eyeHeight * context.getScale() < .4D ? 0 : .4D;
            boolean wet = water && height > 0;
            if (wet && !(ground && height <= threshold) || lava && !(ground && height <= threshold)) {
                velocity = velocity.add(0, .04F, 0);
            } else if ((ground || wet && height <= threshold) && jumpDelay == 0) {
                float power = (float) self.jumpStrength * jumpFactor(player, prior, start)
                        + (effect(self, MovementEffect.JUMP_BOOST) == null
                                ? 0 : .1F * (effect(self, MovementEffect.JUMP_BOOST) + 1));
                if (power > 1.0E-5F) {
                    velocity = new Vec3(velocity.x, Math.max(power, velocity.y), velocity.z);
                    if (player.sprintState.flag()) {
                        float yaw = context.getXRot() * ((float) Math.PI / 180F);
                        velocity = velocity.add(-player.trigHandler.sin(yaw) * .2F, 0, player.trigHandler.cos(yaw) * .2F);
                    }
                }
                jumpDelay = 10;
            }
        }
        boolean fluidTravel = (water || lava) && !player.isFlying;
        float friction = ground ? BlockProperties.getFriction(player, prior.support(), start) : 1.0F;
        if (context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_2) && ground)
            friction = Friction.computeModifiedFriction(friction, (float) self.frictionModifier);
        float speed = inputSpeed(player, self, fluidTravel && water, fluidTravel && !water, ground, friction);
        velocity = velocity.add(ClientKeyInput.inputVector(context, movementInput, speed));
        boolean climbing = !fluidTravel && Collisions.onClimbable(player, start.x, start.y, start.z);
        if (climbing) {
            double y = Math.max(velocity.y, -.15F);
            int block = player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(start.x, start.y, start.z));
            if (y < 0 && keys.shift() && !BlockIds.is(block, BlockIds.SCAFFOLDING)) y = 0;
            velocity = new Vec3(Math.clamp(velocity.x, -.15F, .15F), y, Math.clamp(velocity.z, -.15F, .15F));
        }
        double distance = climbing || effect(self, MovementEffect.SLOW_FALLING) != null
                        || effect(self, MovementEffect.LEVITATION) != null
                ? 0 : prior.fallDistance();
        var before = new JavaPassState(start, velocity, ground, prior.support(), prior.stuckSpeed(), distance,
                prior.pistonDeltas(), false, water, lava, fluids.eye());
        var env = new JavaBlockEntityMove.Environment(player, context, context.getPose(), context.getScale(),
                keys.shift(), start, player.compensatedWorld.clientGameTime(), null, context.getMovementPatch());
        double gravity = gravity(self, velocity.y);
        var moved = JavaBlockEntityMove.move(env, before, JavaBlockEntityMove.Mover.SELF, velocity);
        JavaPassState state = moved.state();
        Vec3 movement = state.velocity();
        boolean horizontal = Math.abs(moved.delta().x - moved.movement().x) >= 1.0E-5F
                || Math.abs(moved.delta().z - moved.movement().z) >= 1.0E-5F;
        boolean endClimbable = Collisions.onClimbable(player, state.position().x, state.position().y, state.position().z);
        if ((fluidTravel && water && horizontal && endClimbable)
                || (!fluidTravel && (horizontal || jump)
                        && (endClimbable || JavaInsideBlockEffects.canClimbPowderSnow(player, context, state.position()))))
            movement = new Vec3(movement.x, .2D, movement.z);
        if (fluidTravel) {
            boolean falling = velocity.y <= 0;
            boolean shallow = (context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_1) && !water
                    ? JavaPassengerFluids.sample(player, env.box(state.position()), state.position(),
                            Vec3.ZERO, vehicleBox).lavaHeight()
                    : fluids.lavaHeight())
                    <= (context.getPose().eyeHeight * context.getScale() < .4D ? 0 : .4D);
            if (water) {
                float efficiency = (float) self.waterMovementEfficiency * (ground ? 1 : .5F);
                float drag = player.sprintState.flag() ? .9F : .8F;
                if (efficiency > 0) drag += (.54600006F - drag) * efficiency;
                if (effect(self, MovementEffect.DOLPHINS_GRACE) != null) drag = .96F;
                movement = movement.multiply(drag, .8F, drag);
                if (!player.sprintState.flag() && gravity != 0) movement = Friction.applyFluidGravity(gravity, falling, movement);
            } else {
                movement = shallow ? movement.multiply(.5D, .8F, .5D) : movement.scale(.5D);
                if (shallow && !player.sprintState.flag() && gravity != 0)
                    movement = Friction.applyFluidGravity(gravity, falling, movement);
                if (gravity != 0) movement = movement.add(0, -gravity / 4, 0);
            }
            if (horizontal && free(player, env, state, movement, start.y)) movement = new Vec3(movement.x, .3F, movement.z);
            // A passenger can itself carry a passenger; floatInLiquidWhileRidden reads that entity's own tag/depth.
            if (context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11)
                    && !self.passengers.isEmpty()
                    && (water || context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_3))
                    && ClientFluidQueries.clientTags(player).entities()
                            .getOrDefault("minecraft:can_float_while_ridden", java.util.List.of()).contains("minecraft:player")
                    && (context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                                    ? fluids.floatableHeight() : fluids.waterHeight())
                            > (context.getPose().eyeHeight * context.getScale() < .4D ? 0 : .4D))
                movement = movement.add(0, .04F, 0);
        } else {
            // travelInAir reads effective gravity from stored deltaMovement, before the climb return's 0.2 boost.
            double y = effect(self, MovementEffect.LEVITATION) != null
                    ? movement.y + (.05D * (effect(self, MovementEffect.LEVITATION) + 1) - movement.y) * .2D
                    : movement.y - gravity(self, state.velocity().y);
            float airDrag = context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_2)
                    ? Friction.computeModifiedFriction(.91F, (float) self.airDragModifier) : .91F;
            float verticalDrag = context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_2)
                    ? Friction.computeModifiedFriction(.98F, (float) self.airDragModifier) : .98F;
            movement = new Vec3(movement.x * (friction * airDrag), y * verticalDrag, movement.z * (friction * airDrag));
        }
        state = JavaBlockEntityMove.applyTravelEffects(env, state.withVelocity(movement), start,
                moved.recordedMovement() ? moved.delta() : null);
        // LivingEntity#rideTick resets fall distance AFTER effects and positionRider.
        return new Result(new JavaPassState(state.position(), state.velocity(), state.onGround(), state.support(),
                state.stuckSpeed(), 0, state.pistonDeltas(), state.powderContact(), state.inWater(), state.inLava(),
                state.playerEyeFluidContact()), jumpDelay);
    }

    /** Shulker entity moves before this passenger's tick; rideTick subsequently zeroes their velocity. */
    public static JavaPassState beforeTick(CultPlayer player, SimulationContext context, JavaPassState state) {
        var moves = player.compensatedEntities.shulkerPeekMoves(true);
        if (moves.isEmpty()) return state;
        return JavaBlockEntityPass.run(player, context,
                new ac.cult.cultac.utils.latency.ClientBlockEntityPass(moves.stream()
                        .map(m -> (ac.cult.cultac.utils.latency.ClientBlockEntityPass.Mover) m).toList(),
                        player.compensatedWorld.endedTickGameTime()), state,
                context.getPose(), player.packetStateData.knownInput.shift(), state.position()).state();
    }

    /** positionRider replaces only position; later entity/block-entity moves retain their complete result. */
    public static java.util.List<JavaPassState> afterTick(
            CultPlayer player, SimulationContext context, JavaPassState state, Vec3 seat,
            ac.cult.cultac.utils.enums.Pose pose, Vec3 tickStart) {
        state = new JavaPassState(seat, state.velocity(), state.onGround(), state.support(), state.stuckSpeed(),
                0, state.pistonDeltas(), state.powderContact(), state.inWater(), state.inLava(), state.playerEyeFluidContact());
        // Remote LivingEntity pushes and Shulker peek moves interleave in ClientLevel's entity-tick order.
        var peeks = player.compensatedEntities.shulkerPeekMoves(false);
        java.util.List<JavaPassState> states = java.util.List.of(state);
        long after = player.compensatedEntities.clientTickOrder(player.compensatedEntities.getSelf());
        for (var peek : peeks) {
            states = applyLateVelocities(player, context, states, pose, after, peek.tickOrder());
            states = applyPeek(player, context, states, peek, pose, tickStart);
            after = peek.tickOrder();
        }
        states = applyLateVelocities(player, context, states, pose, after, Long.MAX_VALUE);
        var pass = player.compensatedWorld.lastBlockEntityPass();
        if (pass.isEmpty()) return states;
        var next = new java.util.ArrayList<JavaPassState>();
        for (var current : states) {
            var moved = JavaBlockEntityPass.run(player, context, pass, current, pose,
                    player.packetStateData.knownInput.shift(), tickStart).state();
            if (!next.contains(moved)) next.add(moved);
        }
        return next;
    }

    private static java.util.List<JavaPassState> applyLateVelocities(
            CultPlayer player, SimulationContext context, java.util.List<JavaPassState> states,
            ac.cult.cultac.utils.enums.Pose pose, long after, long through) {
        var result = new java.util.ArrayList<JavaPassState>();
        for (var state : states) {
            java.util.List<JavaPassState> candidates = java.util.List.of(state);
            for (var tick : JavaEntityVelocityTicks.passengerTicks(player, context, state.position(), pose)) {
                if (tick.order() <= after || tick.order() > through) continue;
                var next = new java.util.ArrayList<JavaPassState>();
                for (var current : candidates) for (Vec3 impulse : tick.impulses()) {
                    var pushed = current.withVelocity(current.velocity().add(impulse));
                    if (!next.contains(pushed)) next.add(pushed);
                }
                candidates = next;
            }
            for (var candidate : candidates) if (!result.contains(candidate)) result.add(candidate);
        }
        return result;
    }

    private static java.util.List<JavaPassState> applyPeek(
            CultPlayer player, SimulationContext context, java.util.List<JavaPassState> states,
            ac.cult.cultac.utils.latency.ClientBlockEntityPass.ShulkerPeek peek,
            ac.cult.cultac.utils.enums.Pose pose, Vec3 tickStart) {
        var pass = new ac.cult.cultac.utils.latency.ClientBlockEntityPass(
                java.util.List.of(peek), player.compensatedWorld.endedTickGameTime());
        var next = new java.util.ArrayList<JavaPassState>();
        for (var state : states) next.add(JavaBlockEntityPass.run(player, context, pass, state, pose,
                player.packetStateData.knownInput.shift(), tickStart).state());
        return next;
    }

    private static float inputSpeed(
            CultPlayer player, PacketEntity self, boolean water, boolean lava, boolean ground, float friction) {
        float livingSpeed = (float) player.compensatedEntities.getPossiblePlayerMovementSpeeds(false,
                ac.cult.cultac.utils.data.ClientSprintState.Outcome.PRIOR)[0];
        if (water) {
            float efficiency = (float) self.waterMovementEfficiency * (ground ? 1 : .5F);
            return efficiency > 0 ? .02F + (livingSpeed - .02F) * efficiency : .02F;
        }
        if (lava) return .02F;
        if (ground) return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2) && friction <= .6F
                ? livingSpeed : livingSpeed * (.21600002F / (friction * friction * friction));
        return player.sprintState.flag() ? .025999999F : .02F;
    }

    private static boolean free(
            CultPlayer player, JavaBlockEntityMove.Environment env, JavaPassState state, Vec3 velocity, double oldY) {
        Vec3 offset = new Vec3(velocity.x, velocity.y + .6F - state.position().y + oldY, velocity.z);
        SimpleCollisionBox box = env.box(state.position()).offset(offset);
        if (!Collisions.isEmpty(player, box, state.position().y)) return false;
        for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++)
            for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++)
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++)
                    if (!ClientFluidQueries.modelFluid(player.compensatedWorld.getBlockStateIdAt(x, y, z)).isEmpty())
                        return false;
        for (var collider : RootVehicleInterpolationCollision.entityCollisionBoxes(player, env.actor(), state.position(), box))
            if (box.isIntersected(collider)) return false;
        return true;
    }

    private static float jumpFactor(CultPlayer player, JavaPassState state, Vec3 start) {
        int feet = player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(start.x, start.y, start.z));
        int below = BlockProperties.getOnPos(player, state.support(), start);
        return BlockIds.is(feet, BlockIds.HONEY_BLOCK) || BlockIds.is(below, BlockIds.HONEY_BLOCK) ? .5F : 1;
    }

    private static Integer effect(PacketEntity actor, MovementEffect effect) {
        return actor.potionsMap == null ? null : actor.potionsMap.get(effect);
    }

    private static double gravity(PacketEntity actor, double y) {
        if (!actor.hasGravity) return 0;
        return y <= 0 && effect(actor, MovementEffect.SLOW_FALLING) != null ? Math.min(actor.gravity, .01D) : actor.gravity;
    }
}
