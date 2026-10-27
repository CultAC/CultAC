package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.BlockSpeedFactors;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.JavaLivingMoveFluids;
import ac.cult.cultac.utils.nmsutil.RootVehicleInterpolationCollision;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;

/**
 * Entity#move for the movers a block entity's tick uses on the local player: MoverType.PISTON from
 * PistonMovingBlockEntity#moveEntityByPiston, with its Entity#applyEffectsFromBlocks, and MoverType.SHULKER_BOX from
 * ShulkerBoxBlockEntity#moveCollidedEntities; and MoverType.SHULKER from a Shulker entity's
 * Shulker#onPeekAmountChange.
 */
final class JavaBlockEntityMove {
    private JavaBlockEntityMove() {}

    enum Mover {
        SELF,
        PISTON,
        SHULKER_BOX,
        SHULKER
    }

    /**
     * What one Entity#move left: {@code moved} when it ran past Entity#limitPistonMovement's early return, so it set
     * onGround and the collision flags; {@code movement} the collided movement and {@code delta} the delta collision
     * started from.
     */
    record Moved(
            JavaPassState state,
            boolean moved,
            Vec3 delta,
            Vec3 movement,
            boolean recordedMovement,
            Double boatLastYd,
            ClientFluidHeights fluidHeights) {
        Moved(JavaPassState state, boolean moved, Vec3 delta, Vec3 movement, boolean recordedMovement,
                Double boatLastYd) {
            this(state, moved, delta, movement, recordedMovement, boatLastYd, null);
        }

        Moved(JavaPassState state, boolean moved, Vec3 delta, Vec3 movement) {
            this(state, moved, delta, movement, false, null, null);
        }

        /** Whether collision changed the move on {@code axis}: 0 is X, 1 is Y and 2 is Z. */
        boolean clipped(int axis) {
            return moved && component(delta, axis) != component(movement, axis);
        }

        private static double component(Vec3 vector, int axis) {
            return axis == 0 ? vector.x : axis == 1 ? vector.y : vector.z;
        }
    }

    /**
     * The tick's constants: the player, the prediction context whose actor and attributes it uses, the pose
     * Player#updatePlayerPose left, the shift key (Player#isSuppressingBounce and Entity#isSteppingCarefully), the
     * position the tick started at (Entity#oldPosition) and Level#getGameTime. A {@code detached} remote actor's moves
     * are only computed, never applied to the tracked entity.
     */
    record Environment(
            CultPlayer player,
            SimulationContext context,
            Pose pose,
            float scale,
            boolean sneaking,
            Vec3 tickStart,
            long gameTime,
            PacketEntity remoteActor,
            JavaMovementPatch movementPatch,
            boolean detached) {
        Environment(
                CultPlayer player,
                SimulationContext context,
                Pose pose,
                float scale,
                boolean sneaking,
                Vec3 tickStart,
                long gameTime,
                PacketEntity remoteActor,
                JavaMovementPatch movementPatch) {
            this(player, context, pose, scale, sneaking, tickStart, gameTime, remoteActor, movementPatch, false);
        }

        PacketEntity actor() {
            if (remoteActor != null) return remoteActor;
            return context.getVehicle() == null ? player.compensatedEntities.getSelf() : context.getVehicle();
        }

        SimpleCollisionBox box(Vec3 position) {
            PacketEntity actor = actor();
            if (actor != player.compensatedEntities.getSelf())
                return GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, actor);
            return GetBoundingBox.getBoundingBoxFromPosAndSize(
                    position.x, position.y, position.z, pose.width * scale, pose.height * scale);
        }

        ClientVersion version() {
            return context == null ? player.getClientVersion() : context.getVersion();
        }

        boolean authoritative() {
            return remoteActor == null;
        }
    }

    /** Entity#move and the reached actor's virtual move override. */
    static Moved move(Environment env, JavaPassState state, Mover mover, Vec3 delta) {
        PacketEntity actor = env.actor();
        if (actor != null && actor.type == EntityTypeIds.SHULKER) {
            // Shulker#getDeltaMovement is always ZERO and its setter does nothing, including slime's callback.
            state = state.withVelocity(Vec3.ZERO);
            if (mover == Mover.SHULKER_BOX && actor.noAI) return new Moved(state, false, delta, Vec3.ZERO);
        }
        Moved moved = moveEntity(env, state, mover, delta, !env.detached());
        // Ordinary AbstractMinecart#move runs its no-argument stationary block pass even when Entity#move
        // returned early. PistonMovingBlockEntity runs its separate explicit block pass afterwards.
        if (actor != null && actor.isMinecart())
            return new Moved(
                    applyEffectsFromBlocks(env, moved.state(), moved.state().position(), true),
                    moved.moved(),
                    moved.delta(),
                    moved.movement(),
                    moved.recordedMovement(),
                    moved.boatLastYd());
        return moved;
    }

    /** The same resolver without committing a remote actor while its discrete outcomes are compared. */
    static Moved moveDetached(Environment env, JavaPassState state, Vec3 delta) {
        return moveEntity(env, state, Mover.SELF, delta, false);
    }

    private static Moved moveEntity(Environment env, JavaPassState state, Mover mover, Vec3 delta, boolean commit) {
        CultPlayer player = env.player();
        if (env.actor() == player.compensatedEntities.getSelf()
                && player.gamemode == ac.cult.cultac.protocol.value.GameMode.SPECTATOR) {
            return new Moved(new JavaPassState(state.position().add(delta), state.velocity(), state.onGround(),
                    state.support(), state.stuckSpeed(), state.fallDistance(), state.pistonDeltas(),
                    state.powderContact(), state.inWater(), state.inLava(), state.playerEyeFluidContact()),
                    false, delta, delta);
        }
        ClientPistonDeltas deltas = state.pistonDeltas();
        if (mover == Mover.PISTON) {
            ClientPistonDeltas.Limited limited = deltas.limit(delta, env.gameTime());
            deltas = limited.deltas();
            delta = limited.movement();
            if (delta.equals(Vec3.ZERO)) return new Moved(state.withPistonDeltas(deltas), false, delta, delta);
        }

        Vec3 velocity = state.velocity();
        Vec3 stuck = state.stuckSpeed();
        if (stuck != null && stuck.lengthSqr() > 1.0E-7) {
            // From 1.21.10 Entity#move leaves a piston's movement unscaled; it clears the multiplier either way.
            if (mover != Mover.PISTON || !JavaMovementPatch.unscaledPiston(env.version(), env.movementPatch()))
                delta = delta.multiply(stuck);
            stuck = null;
            velocity = Vec3.ZERO;
        }

        // Player#maybeBackOffFromEdge only shortens MoverType.SELF and PLAYER.
        PacketEntity actor = env.actor();
        var entityBoxes = RootVehicleInterpolationCollision.entityCollisionBoxes(
                player, actor, state.position(), entityQuery(env, state.position(), delta));
        if (actor.type == EntityTypeIds.FISHING_BOBBER)
            Collisions.addWorldBorder(player, env.box(state.position()), entityBoxes, false,
                    state.position().x, state.position().z);
        Vec3 movement = JavaForwardMove.collide(
                player,
                env.box(state.position()),
                delta,
                state.onGround(),
                stepHeight(env),
                JavaCollisionState.of(player, actor, state.fallDistance()),
                entityBoxes);
        double movementLength = movement.lengthSqr();
        Vec3 position = state.position();
        boolean recordedMovement = movementLength > 1.0E-7 || delta.lengthSqr() - movementLength < 1.0E-7;
        if (recordedMovement) {
            position = position.add(movement);
            // Shulker#setPos snaps a root to the center of its X/Z block and rounds Y to the nearest block.
            if (env.remoteActor() != null && env.remoteActor().type == EntityTypeIds.SHULKER)
                position = new Vec3(
                        CultMath.floor(position.x) + 0.5,
                        CultMath.floor(position.y + 0.5),
                        CultMath.floor(position.z) + 0.5);
        }
        if (commit && !env.authoritative() && recordedMovement)
            env.remoteActor().applyBlockEntityMovement(player, env.box(position), movement);

        boolean xCollision = !equal(delta.x, movement.x);
        boolean zCollision = !equal(delta.z, movement.z);
        boolean verticalCollision = delta.y != movement.y;
        boolean verticalCollisionBelow = verticalCollision && delta.y < 0.0;
        // Entity#isLocalInstanceAuthoritative: the local player's move always sets onGround.
        boolean updatesGround =
                env.version().isOlderThan(ClientVersion.V_1_21_4) || Math.abs(delta.y) > 0.0 || env.authoritative();
        boolean onGround = updatesGround ? verticalCollisionBelow : state.onGround();
        MainSupportingBlockData support = updatesGround
                ? player.checkManager
                        .getSimulationProcessor()
                        .mainSupportingBlockAfter(state.support(), env.box(position), movement, onGround, position)
                : state.support();

        boolean runsCallback = JavaFallDistance.runsFallDamageCallback(env.version(), env.authoritative(), actor);
        var fluids = runsCallback
                ? JavaLivingMoveFluids.apply(
                        player, env.version(), actor, env.box(position), velocity, state.inWater(), state.inLava(),
                        env.context() == null ? null : env.context().getRideTickVehicleBox(),
                        env.pose() == null ? 0 : env.pose().eyeHeight * env.scale())
                : new JavaLivingMoveFluids.Result(velocity, state.inWater(), state.inLava(), false, false);
        velocity = fluids.velocity();
        int eyeInWater = state.playerEyeFluidContact();
        if (actor == player.compensatedEntities.getSelf())
            eyeInWater = fluids.playerEyeFluidContact(
                    player, env.box(position), position, position.y + env.pose().eyeHeight * env.scale(), eyeInWater);
        double fallDistance = state.fallDistance();
        if (fluids.resetFallDistance()) fallDistance = 0;
        if (runsCallback) {
            boolean waterBelow = actor.isBoat()
                    && ClientFluidQueries.is(
                            player,
                            ClientFluidQueries.fluidAt(
                                    player.compensatedWorld,
                                    BlockPos.containing(position.x, position.y, position.z)
                                            .below()),
                            FluidTags.WATER);
            fallDistance = JavaFallDistance.storedDistance(
                    env.version(),
                    JavaFallDistance.afterMoveForActor(
                            env.version(),
                            fallDistance,
                            movement.y,
                            fluids.inWater(),
                            onGround,
                            actor.isBoat(),
                            actor.riding != null,
                            waterBelow,
                            actor.isStrider() && state.inLava()));
        }
        // AbstractBoat#checkFallDamage samples deltaMovement before collision restitution, even
        // when this move is horizontal or Entity#setPos's tiny-move guard did not run.
        Double boatLastYd = actor.isBoat() && runsCallback ? velocity.y : null;

        int effectBlock = BlockProperties.getOnPos(player, support, position);
        velocity = afterCollision(
                env,
                velocity,
                delta,
                movement,
                xCollision,
                zCollision,
                verticalCollision,
                verticalCollisionBelow,
                effectBlock);
        float speed = env.authoritative()
                ? BlockSpeedFactors.afterTravelAtPosition(player, env.context(), support, position)
                : BlockSpeedFactors.entityAtPosition(player, env.remoteActor(), support, position);
        velocity = velocity.multiply(speed, 1.0, speed);
        return new Moved(
                new JavaPassState(
                        position,
                        velocity,
                        onGround,
                        support,
                        stuck,
                        fallDistance,
                        deltas,
                        state.powderContact(),
                        fluids.inWater(),
                        fluids.inLava(),
                        eyeInWater),
                true,
                delta,
                movement,
                recordedMovement,
                boatLastYd,
                fluids.refreshed() && actor instanceof PacketEntityStrider && env.version().isNewerThanOrEquals(ClientVersion.V_26_1)
                        ? ClientFluidHeights.capture(player, env.box(position)) : null);
    }

    private static SimpleCollisionBox entityQuery(Environment env, Vec3 position, Vec3 delta) {
        SimpleCollisionBox query = env.box(position).expandToCoordinate(delta.x, delta.y, delta.z);
        // 26.3 Entity#collide widens this query before collecting the entity colliders shared with stepping.
        if (env.version().isNewerThanOrEquals(ClientVersion.V_26_3)) query.expandToCoordinate(0, stepHeight(env), 0);
        return query;
    }

    private static double stepHeight(Environment env) {
        return env.actor() == env.player().compensatedEntities.getSelf()
                ? stepHeight(env.actor())
                : env.authoritative() ? env.player().getMaxUpStep() : stepHeight(env.remoteActor());
    }

    /**
     * Entity#move's response to the move's collisions: through 26.1 it zeroes each collided horizontal axis and calls
     * Block#updateEntityMovementAfterFallOn on a vertical collision; from 26.2 Entity#restituteMovementAfterCollisions
     * runs after a vertical move that collided or a horizontal collision.
     */
    private static Vec3 afterCollision(
            Environment env,
            Vec3 velocity,
            Vec3 delta,
            Vec3 movement,
            boolean xCollision,
            boolean zCollision,
            boolean verticalCollision,
            boolean below,
            int effectBlock) {
        CultPlayer player = env.player();
        if (env.version().isOlderThan(ClientVersion.V_26_2)) {
            if (xCollision || zCollision)
                velocity = new Vec3(xCollision ? 0.0 : velocity.x, velocity.y, zCollision ? 0.0 : velocity.z);
            if (env.authoritative() && delta.y != movement.y) {
                double y = VelocityCandidates.afterVerticalCollision(
                        player,
                        env.actor(),
                        effectBlock,
                        env.sneaking(),
                        env.actor().isLivingEntity(),
                        below,
                        velocity.y,
                        movement.y);
                velocity = new Vec3(velocity.x, y, velocity.z);
            }
            return velocity;
        }
        // Projectile#getMoveSimulationType is SERVER_AND_CLIENT in 26.3. Its movement restitutes even though
        // the hook has no local controlling passenger; checkFallDamage above remains authority-only.
        boolean hook = env.actor().type == EntityTypeIds.FISHING_BOBBER
                && env.version().isNewerThanOrEquals(ClientVersion.V_26_3);
        if (!env.authoritative() && !hook) return velocity;
        boolean movedVertically = Math.abs(delta.y) > 0.0;
        if (!(movedVertically && verticalCollision || xCollision || zCollision)) return velocity;
        double restitution = env.sneaking() ? 0.0 : env.actor().bounciness;
        double x = xCollision ? -velocity.x * restitution : velocity.x;
        double z = zCollision ? -velocity.z * restitution : velocity.z;
        double y = verticalCollision
                ? VelocityCandidates.afterVerticalCollision(
                        player,
                        env.actor(),
                        effectBlock,
                        env.sneaking(),
                        env.actor().isLivingEntity(),
                        below,
                        velocity.y,
                        movement.y)
                : velocity.y;
        return new Vec3(x, y, z);
    }

    /**
     * PistonMovingBlockEntity#moveEntityByPiston's Entity#applyEffectsFromBlocks: from 1.21.5 over the piston move
     * {@code from} its start, through 1.21.4 the no-argument form over Entity#oldPosition to the position. A grounded
     * entity steps on Entity#getOnPosLegacy's block first; then the blocks the movement passes run their callbacks.
     */
    static JavaPassState applyEffectsFromBlocks(Environment env, JavaPassState state, Vec3 from) {
        return applyEffectsFromBlocks(env, state, from, false, null);
    }

    static JavaPassState applyTravelEffects(Environment env, JavaPassState state, Vec3 from, Vec3 delta) {
        return applyEffectsFromBlocks(env, state, from, false, delta);
    }

    private static JavaPassState applyEffectsFromBlocks(
            Environment env, JavaPassState state, Vec3 from, boolean stationary) {
        return applyEffectsFromBlocks(env, state, from, stationary, null);
    }

    private static JavaPassState applyEffectsFromBlocks(
            Environment env, JavaPassState state, Vec3 from, boolean stationary, Vec3 delta) {
        CultPlayer player = env.player();
        Vec3 velocity = state.velocity();
        if (state.onGround()
                && BlockIds.is(
                        BlockProperties.getOnPos(player, state.support(), state.position()), BlockIds.SLIME_BLOCK))
            velocity = slimeStepOn(velocity, env.sneaking());
        boolean oldNoArgument = env.version().isOlderThan(ClientVersion.V_1_21_5);
        boolean minecart = env.remoteActor() != null && env.remoteActor().isMinecart();
        Vec3 start =
                stationary || oldNoArgument && minecart ? state.position() : oldNoArgument ? env.tickStart() : from;
        JavaInsideBlockEffects.Scan scan = env.authoritative()
                ? JavaInsideBlockEffects.scan(player, env.context(), start, state.position(), delta, env.pose())
                : JavaInsideBlockEffects.scan(
                        player, env.remoteActor(), state.position(), env.box(start), env.box(state.position()), delta);
        JavaInsideBlockEffects.State effects = JavaInsideBlockEffects.resolveBlockEntityMove(
                player, env.context(), scan, state.fallDistance(), velocity, state.onGround(), env.movementPatch());
        return new JavaPassState(
                state.position(),
                effects.velocity(),
                state.onGround(),
                state.support(),
                effects.stuckSpeed() != null ? effects.stuckSpeed() : state.stuckSpeed(),
                effects.fallDistance(),
                state.pistonDeltas(),
                state.powderContact() || effects.powderContact(),
                state.inWater(),
                state.inLava(),
                state.playerEyeFluidContact());
    }

    /** Entity/LivingEntity defaults and the entity types overriding Attributes.STEP_HEIGHT. */
    private static double stepHeight(PacketEntity actor) {
        if (actor.stepHeightAttribute != null) return actor.stepHeightAttribute.floatValue();
        if (!actor.isLivingEntity() || EntityTypeUtil.isType(actor.type, "armor_stand")) return 0;
        if (EntityTypeUtil.isCamelFamily(actor.type)) return 1.5F;
        if (EntityTypeUtil.isType(actor.type, "creaking")) return 1.0625F;
        if (EntityTypeUtil.isHorseFamily(actor.type)) return 1.0F;
        return switch (EntityTypeUtil.getKey(actor.type).getPath()) {
            case "axolotl", "copper_golem", "drowned", "enderman", "frog", "iron_golem", "ravager", "turtle" -> 1.0F;
            default -> 0.6F;
        };
    }

    /** SlimeBlock#stepOn: below 0.1 vertical speed it slows the horizontal velocity unless stepping carefully. */
    private static Vec3 slimeStepOn(Vec3 velocity, boolean steppingCarefully) {
        double absDeltaY = Math.abs(velocity.y);
        if (absDeltaY >= 0.1 || steppingCarefully) return velocity;
        double scale = 0.4 + absDeltaY * 0.2;
        return velocity.multiply(scale, 1.0, scale);
    }

    // Mth#equal
    private static boolean equal(double a, double b) {
        return Math.abs(b - a) < 1.0E-5F;
    }
}
