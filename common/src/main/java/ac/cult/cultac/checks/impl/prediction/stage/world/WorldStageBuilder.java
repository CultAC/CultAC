package ac.cult.cultac.checks.impl.prediction.stage.world;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaSpinAttack;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPush;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox;
import ac.cult.cultac.utils.data.*;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityArmorStand;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.FluidTypeFlowing;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.RiptideUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import lombok.AllArgsConstructor;
import lombok.Data;

public class WorldStageBuilder {
    // Temporary Bedrock push-out approximation, inset on every face.
    // prevents exploits due to incomplete phase check
    // bedrock client sends pos as (minX + maxX) / 2 and doesn't rebase bounding box on position like java client
    // causing floating point inaccuracies. Yes, this causes falses, but I'm not sure how to fix bedrock's
    // floating point inprecision is any decent way... perhaps looking at the many bounding boxes that can send
    // the position and picking the one at the end of the tick that doesn't intersect with any new block collisions?
    private static final double BEDROCK_PUSH_OUT_INSET = 0.002D;
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();
    private static final Direction[] HORIZONTAL_ESCAPES = {
        Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH
    };
    private static final List<Direction.Axis> Y_Z_X = List.of(Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X);
    private static final List<Direction.Axis> Y_X_Z = List.of(Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z);
    /** Never written: StuckEdgeData has no mutating caller. */
    private static final StuckEdgeData NOT_ON_EDGE = new StuckEdgeData(false, false, false, false, false);

    private final SupportScan supportScan = new SupportScan();
    private int collidingTickingBeforePlayer;
    private int pushingTickingAfterPlayer;
    private int ridersTickingAfterPlayer;

    /**
     * Whether the moving entity can be on a climbable: the player itself, or a ridden pig, strider or nautilus, which
     * keep LivingEntity#onClimbable. AbstractHorse and HappyGhast override it to return false.
     */
    private static boolean canClimb(PacketEntity vehicle) {
        return vehicle == null
                || vehicle instanceof PacketEntityStrider
                || vehicle instanceof PacketEntityNautilus
                || vehicle.type == EntityTypeIds.PIG;
    }

    /** The climbable state at one Entity#move endpoint, before any later block-entity shove. */
    public static DesyncStatus climbingAtPosition(CultPlayer player, SimulationContext context, Vec3 position) {
        if (!canClimb(context.getVehicle())) return DesyncStatus.FALSE;
        DesyncStatus climbing =
                DesyncStatus.fromBoolean(Collisions.onClimbable(player, position.x, position.y, position.z));
        int state = player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(position.x, position.y, position.z));
        if (player.isBedrockMovement()
                && BlockIds.is(state, BlockIds.POWDER_SNOW)
                && player.getInventory().getBoots().getItem() == ac.cult.cultac.utils.inventory.ItemTypes.LEATHER_BOOTS)
            return DesyncStatus.UNKNOWN;
        return climbing;
    }

    public WorldData generateWorldData(
            CultPlayer player,
            SimulationContext simulationContext,
            PredictionResult lastPrediction,
            DesyncStatus lastOnGround) {
        Vec3 to = simulationContext.getEnd();
        Vec3 from = simulationContext.getStart();
        double deltaX = to.x - from.x;
        double deltaZ = to.z - from.z;

        FluidState fluidState;
        FluidState maxFluidState = null;
        if (player.isBedrockMovement()) {
            // Bedrock bounds its pose between the smallest and tallest player boxes.
            fluidState = getFluidState(player, simulationContext.getFromMinimumExtent());
            maxFluidState = simulationContext.getVehicle() != null
                    ? fluidState
                    : getFluidState(player, simulationContext.getFromMaximumExtent());
        } else {
            // Entity#baseTick's fluid update samples Entity#getBoundingBox at the movement start: the pose
            // Player#updatePlayerPose left last tick at the player's scale, or the vehicle's own box.
            fluidState = getFluidState(player, simulationContext.getFromActualPose());
        }
        boolean isInWater = fluidState.isInWater() && (maxFluidState == null || maxFluidState.isInWater());
        boolean isInLava = fluidState.isInLava() && (maxFluidState == null || maxFluidState.isInLava());
        boolean isInFlowingFluid =
                fluidState.isInFlowingFluid() && (maxFluidState == null || maxFluidState.isInFlowingFluid());
        WorldData.BedrockFluidBounds bedrockFluidBounds = maxFluidState == null
                ? null
                : new WorldData.BedrockFluidBounds(
                        fluidState.isInWater() || maxFluidState.isInWater(),
                        fluidState.isInLava() || maxFluidState.isInLava(),
                        fluidState.isInFlowingFluid() || maxFluidState.isInFlowingFluid());

        boolean canFluidHop = false;

        if (lastPrediction != null) {
            WorldData oldWorldData = lastPrediction.getSimulationContext().getWorldData();
            boolean wasInWaterOrLava = oldWorldData.mayBeInLiquid();
            boolean didCollide = lastPrediction.getCollideAxisData().couldCollideHorizontally();
            canFluidHop = wasInWaterOrLava
                    && didCollide
                    && (simulationContext.getVehicle() == null
                            || !ac.cult.cultac.utils.nmsutil.EntityTypeUtil.isBoat(
                                    simulationContext.getVehicle().type));
        }

        // MCP-Reborn Entity#baseTick refreshes wasTouchingWater/fluid heights
        // before travel, and LivingEntity#floatInWaterWhileRidden later reads
        // that cached state inside the same tick. Keep the current tick's
        // pre-move deep-water state so the next tick can carry the exact +0.04.
        boolean riddenFloat = ClientFluidQueries.floatWhileRidden(
                player, simulationContext, fluidState.isInWater(), fluidState.getMaxWaterHeight());
        boolean canFloatWhileRidden = riddenFloat;
        boolean couldFloatWhileRidden = riddenFloat;

        // LivingEntity#travelFallFlying samples the starting block, before movement.
        DesyncStatus climbingAtStart = sampleClimbingAtStart(player, simulationContext);

        DesyncStatus climbing = climbingAtPosition(player, simulationContext, to);

        MainSupportingBlockData mainSupportingBlockData = findMainSupportingBlockPos(
                player,
                player.isBedrockMovement()
                        ? (lastPrediction == null
                                ? null
                                : lastPrediction
                                        .getSimulationContext()
                                        .getWorldData()
                                        .getMainSupportingBlockPos())
                        : simulationContext.getLastTickMainSupportingBlockData(),
                deltaX,
                deltaZ,
                simulationContext.getToMaxPose(),
                player.onGround);

        boolean isSuffocating = mightBeSuffocating(player, simulationContext);
        int numColliding = getNumEntitiesCollidingWith(player, simulationContext);
        SpinTarget spinTarget = spinTarget(player, simulationContext);
        CarriedPush carriedPush = player.checkManager.getSimulationProcessor().getCarriedPush();
        int numCarriedPushing = pushersFromLastTick(simulationContext, lastPrediction, !carriedPush.isZero());
        boolean pushable =
                player.isBedrockMovement() || !simulationContext.getEntities().selfCertainlyCarriesPassenger();
        int numPushing = (pushable ? collidingTickingBeforePlayer : 0) + numCarriedPushing;
        int onBlock = BlockProperties.getOnPos(player, mainSupportingBlockData, to);

        SimpleCollisionBox minExtent = simulationContext.getFromMinimumExtent();
        StuckEdgeData stuckEdgeData = couldBeOnStuckEdge(
                player, simulationContext.getTarget(), lastOnGround.determineOptimistically(), minExtent);
        // If the player isn't in water, then they aren't in a bubble column
        final BubbleColumnData bubble = computeBubbleColumns(player, to, simulationContext);

        // Java movement replays the client's block-entity passes themselves (JavaBlockEntityPass); the push
        // envelope is the Bedrock engine's PistonShulkerPush input.
        PistonPushes push = player.isBedrockMovement()
                ? player.compensatedWorld.tickPlayerInPistonPushingArea(simulationContext)
                : PistonPushes.NONE;
        StuckSpeedData stuckSpeedData = calculateStuckSpeed(player, simulationContext, push);

        // Entity#baseTick samples the eye at the movement start, at the pose Player#updatePlayerPose left last tick.
        int eyeContact = eyeFluidContact(player, simulationContext, player.pose);
        boolean eyeInWater = ClientFluidQueries.eyeContactInWater(player, eyeContact);
        // LocalPlayer#isUnderWater is the eye Player#updateIsUnderwater read before this tick's Entity#baseTick.
        boolean underwater = lastPrediction != null
                && lastPrediction.getSimulationContext().getWorldData().isEyeInWater();
        if (!player.isBedrockMovement() && simulationContext.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
            // The cache also has a construction boundary: an older prediction can predate a respawn.
            underwater = player.checkManager.getSimulationProcessor().isLastWorldEyeInWater();
            if (simulationContext.getVersion().isNewerThanOrEquals(ClientVersion.V_26_1)
                    && simulationContext.getProfileCarry() instanceof JavaPredictionCarry carry
                    && !carry.states().isEmpty()) {
                // The combined interaction can refresh eyes during travel and later piston callbacks.
                // Contexts select one carried state before building this world's swimming state.
                underwater = ClientFluidQueries.eyeContactInWater(
                        player, carry.states().getFirst().playerEyeFluidContact());
            }
        }
        // Entity#updateSwimming runs in Entity#baseTick, before LocalPlayer#aiStep updates the sprint and flying
        // flags; Player#updateSwimming clears it while flying, which only a flight kept since last tick proves.
        boolean waterAtFeet =
                player.isBedrockMovement() || simulationContext.getVersion().isOlderThan(ClientVersion.V_1_21_2)
                        ? player.compensatedWorld.getWaterFluidLevelAt(from.x, from.y, from.z) > 0
                        : ClientFluidQueries.is(
                                player,
                                ClientFluidQueries.fluidAt(
                                        player.compensatedWorld, BlockPos.containing(from.x, from.y, from.z)),
                                FluidTags.WATER);
        boolean swimming = simulationContext.getVehicle() == null
                && !(player.isFlying && player.swimState.flewLastTick())
                && player.sprintState.flagBeforeAiStep()
                && (player.swimState.swimming() && player.gamemode != GameMode.SPECTATOR
                        ? (bedrockFluidBounds == null ? isInWater : bedrockFluidBounds.water())
                        : underwater && waterAtFeet);
        // Portal contact uses the swept inside-block volume.
        player.intersectedWithNetherPortal = Collisions.hasState(
                player,
                simulationContext.getFromMinimumExtent().union(simulationContext.getToMinimumExtent()),
                block -> BlockIds.is(block, BlockIds.NETHER_PORTAL),
                (block, pos) -> true);

        DesyncStatus honeySlide = isSlidingDown(player, simulationContext);
        // MCP-Reborn Entity#checkSupportingBlock stores mainSupportingBlockPos
        // during the previous Entity#move. The next tick's
        // Entity#getBlockJumpFactor reads that stored support position through
        // getBlockPosBelowThatAffectsMyMovement(), so use this context's
        // previous-tick support data rather than the previous context's older
        // support snapshot.
        DesyncStatus onHoney = DesyncStatus.fromBoolean(
                BlockProperties.onHoneyBlock(player, simulationContext.getLastTickMainSupportingBlockData(), from));

        DesyncStatus desyncLastOnGround = lastOnGround;

        if (simulationContext.isRiptideReleaseCertain()) {
            // Entity#move of TridentItem#releaseUsing's upward lift leaves onGround false before LivingEntity#aiStep.
            desyncLastOnGround = DesyncStatus.FALSE;
        } else if (!push.getPush().isEmpty()) {
            desyncLastOnGround = DesyncStatus.UNKNOWN;
        }
        if (simulationContext.getVehicle() != null
                && !desyncLastOnGround.determineOptimistically()
                && simulationContext.getLastTickMainSupportingBlockData().isOnGround()) {
            // Entity#setOnGroundWithMovement updates mainSupportingBlockPos only
            // from an on-ground move. For mounted roots, packet-handler vehicle
            // teleports and echoes can leave Cult's boolean boundary stale while
            // the stored support snapshot still proves the next ridden travel
            // can read grounded friction.
            desyncLastOnGround = DesyncStatus.UNKNOWN;
        }


        // Negative jump boost breaks onGround optimization
        boolean hasNegativeJumpBoost =
                player.compensatedEntities.getPotionLevelForPlayer(MovementEffect.JUMP_BOOST) != null
                        && player.compensatedEntities.getPotionLevelForPlayer(MovementEffect.JUMP_BOOST) < 0;
        boolean canJump = desyncLastOnGround.determineOptimistically()
                && (!simulationContext.isOnGround()
                        || hasNegativeJumpBoost
                        || canGroundJumpInShallowFluid(player, simulationContext, fluidState, maxFluidState))
                && (simulationContext.getVehicle() == null
                        || simulationContext.getVehicle() instanceof PacketEntityHorse);

        WorldData world = new WorldData(
                desyncLastOnGround,
                isInWater,
                isInLava,
                isInFlowingFluid,
                bedrockFluidBounds,
                isSuffocating,
                canJump,
                canFluidHop,
                canFloatWhileRidden,
                couldFloatWhileRidden,
                climbingAtStart,
                climbing,
                numColliding,
                pushable ? pushingTickingAfterPlayer : 0,
                pushable ? ridersTickingAfterPlayer : 0,
                numPushing,
                numCarriedPushing,
                carriedPush,
                pushable,
                onBlock,
                stuckEdgeData,
                bubble,
                push,
                stuckSpeedData,
                honeySlide,
                onHoney,
                mainSupportingBlockData,
                pushesWhileLoadingLevel(player, simulationContext),
                lastOnGround,
                eyeInWater,
                underwater,
                swimming,
                simulationContext.getVehicle() != null && fluidState.isInWater(),
                spinTarget != SpinTarget.NONE,
                spinTarget == SpinTarget.PLAYER);
        world.setEyeFluidContact(eyeContact);
        world.setWaterHeight(fluidState.getMaxWaterHeight());
        world.setLavaHeight(fluidState.getMaxLavaHeight());
        return world;
    }

    // EntityFluidInteraction#update finds the eye only among the fluids inside Entity#getFluidInteractionBox.
    private static int eyeFluidContact(CultPlayer player, SimulationContext context, Pose pose) {
        Vec3 from = context.getStart();
        if (context.getVehicle() != null) {
            if (player.isBedrockMovement()) {
                return ClientFluidQueries.eyeFluidContact(
                        player, context.getFromMinimumExtent(), from, from.y + pose.eyeHeight * player.dimensionScale);
            }
            // Entity#rideTick runs the passenger's Entity#baseTick where the previous Entity#positionRider seated it;
            // on the first ride tick that is where Entity#startRiding found it, standing.
            Vec3 seat = player.compensatedEntities.lastRideTickSeat();
            from = seat != null ? seat : new Vec3(player.x, player.y, player.z);
            if (seat == null) pose = Pose.STANDING;
        }
        SimpleCollisionBox body = GetBoundingBox.getBoundingBoxFromPosAndSize(
                from.x, from.y, from.z, pose.width * player.dimensionScale, pose.height * player.dimensionScale);
        return ClientFluidQueries.eyeFluidContact(player, body, from, from.y + pose.eyeHeight * player.dimensionScale);
    }

    private DesyncStatus sampleClimbingAtStart(CultPlayer player, SimulationContext context) {
        if (!canClimb(context.getVehicle())) return DesyncStatus.FALSE;
        Vec3 position = context.getStart();
        DesyncStatus climbingAtStart = DesyncStatus.fromBoolean(
                Collisions.onClimbable(player, position.x, position.y, position.z, context.isGliding()));
        return climbingAtStart;
    }

    /** {@code maxFluidState} is Bedrock's tallest-box sample, null for Java's one exact sample. */
    private boolean canGroundJumpInShallowFluid(
            CultPlayer player, SimulationContext simulationContext, FluidState fluidState, FluidState maxFluidState) {
        if (!simulationContext.isOnGround()) {
            return false;
        }

        // MCP-Reborn LivingEntity#aiStep calls jumpFromGround, not jumpInLiquid,
        // when this.onGround() is true and the current fluid height is at or
        // below Entity#getFluidJumpThreshold() (0.4 for normal players).
        if (!player.isBedrockMovement()) {
            // LivingEntity#aiStep selects lava's height first, then compares it with getFluidJumpThreshold.
            double height = fluidState.isInLava() ? fluidState.getMaxLavaHeight() : fluidState.getMaxWaterHeight();
            double threshold = simulationContext.getPose().eyeHeight * simulationContext.getScale() < 0.4D ? 0.0D : 0.4D;
            return (fluidState.isInWater() || fluidState.isInLava()) && height <= threshold;
        }
        return fluidState.canGroundJumpFromFluid()
                || (maxFluidState != null
                        && simulationContext.getVehicle() == null
                        && maxFluidState.canGroundJumpFromFluid());
    }

    @Data
    @AllArgsConstructor
    private static class FluidState {
        boolean inWater;
        boolean inLava;
        boolean inFlowingFluid;
        double maxWaterHeight;
        double maxLavaHeight;

        private boolean canGroundJumpFromFluid() {
            return (inWater && maxWaterHeight > 0.0D && maxWaterHeight <= 0.4D)
                    || (inLava && maxLavaHeight > 0.0D && maxLavaHeight <= 0.4D);
        }
    }

    /** Entity's fluid update on {@code body}, the entity's bounding box, deflated by Mojang's 0.001. */
    private FluidState getFluidState(CultPlayer player, SimpleCollisionBox body) {
        return getFluidState(player, body.copy().expand(-0.001), body.minY, body);
    }

    private FluidState getFluidState(
            CultPlayer player, SimpleCollisionBox box, double entityBoundingBoxMinY, SimpleCollisionBox body) {
        FluidState state = new FluidState(false, false, false, 0.0D, 0.0D);
        if (!ClientFluidQueries.hasLoadedFluidNeighborhood(player, body)) return state;

        SimpleCollisionBox lavaCollision = box.copy();
        double depthMinY = ClientFluidQueries.usesLegacyFluidDepth(player) ? box.minY : entityBoundingBoxMinY;

        Collisions.StatePredicate sample = (block, pos) -> {
            boolean isInsideFluid = false;
            ClientFluidQueries.Sample fluid = ClientFluidQueries.sample(player, block, pos);
            double fluidTop = ClientFluidQueries.fluidSurfaceY(player, pos.getY(), fluid.height());
            if (fluid.water()) {
                boolean status = calculateStatusForFluid(box, fluidTop, pos);
                state.setInWater(status || state.isInWater());
                if (status) {
                    state.setMaxWaterHeight(Math.max(state.getMaxWaterHeight(), fluidTop - depthMinY));
                }
                isInsideFluid = status;
            }

            if (fluid.lava()) {
                // 1.14 doesn't care about actual lava collision box
                boolean status = calculateStatusForFluid(lavaCollision, fluidTop, pos);
                state.setInLava(status || state.isInLava());
                if (status) {
                    state.setMaxLavaHeight(Math.max(state.getMaxLavaHeight(), fluidTop - depthMinY));
                }
                isInsideFluid |= status;
            }

            // If the player isn't touching the fluid, then they aren't being pushed by the water
            if (!isInsideFluid) return false;

            Vector3dm flow = FluidTypeFlowing.getFlow(player, pos.getX(), pos.getY(), pos.getZ());
            if (flow.lengthSquared() > 0) {
                state.setInFlowingFluid(true);
            }
            return false;
        };
        if (player.isBedrockMovement()) {
            Collisions.hasState(player, box, sample);
        } else {
            // Entity#updateFluidHeightAndDoFluidPushing and EntityFluidInteraction#update use ceil(max) exclusive.
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
                for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
                    for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                        pos.set(x, y, z);
                        sample.test(player.compensatedWorld.getBlockStateIdAt(pos), pos);
                    }
                }
            }
        }

        return state;
    }

    private boolean mightBeSuffocating(CultPlayer player, SimulationContext context) {
        if (player.compensatedEntities.getSelf().inVehicle()) return false;
        Vec3 oldPos = context.getStart();
        if (player.isBedrockMovement()) return false;
        // TODO: Does 0.03 affect this and if so, how?
        float bbWidth = context.getPose().width * context.getScale();
        // LocalPlayer#suffocatesAt reads this actor's current body. A release lift or earlier mover already
        // changed the context's start, independently of the packet's player position.
        double bodyMaxY = oldPos.y + context.getPose().height * context.getScale();
        return this.moveToClosestSpace(player, oldPos.x - bbWidth * 0.35D, oldPos.y, oldPos.z + bbWidth * 0.35D, bodyMaxY)
                || this.moveToClosestSpace(player, oldPos.x - bbWidth * 0.35D, oldPos.y, oldPos.z - bbWidth * 0.35D, bodyMaxY)
                || this.moveToClosestSpace(player, oldPos.x + bbWidth * 0.35D, oldPos.y, oldPos.z - bbWidth * 0.35D, bodyMaxY)
                || this.moveToClosestSpace(player, oldPos.x + bbWidth * 0.35D, oldPos.y, oldPos.z + bbWidth * 0.35D, bodyMaxY);
    }

    public static boolean moveTowardsClosestSpaceBedrock(SimpleCollisionBox box, List<SimpleCollisionBox> shapes) {
        box = box.copy().expand(-BEDROCK_PUSH_OUT_INSET);
        List<SimpleCollisionBox> surrounding = new ArrayList<>();
        SimpleCollisionBox search = box.copy().expand(1.0, 0.0, 1.0);
        float centerX = 0.0F;
        float centerZ = 0.0F;
        int overlaps = 0;
        for (SimpleCollisionBox shape : shapes) {
            if (!search.isIntersected(shape)) continue;
            if (box.isIntersected(shape)) {
                centerX += (float) ((shape.minX + shape.maxX) * 0.5);
                centerZ += (float) ((shape.minZ + shape.maxZ) * 0.5);
                overlaps++;
            } else {
                surrounding.add(shape);
            }
        }
        if (overlaps == 0) return false;
        centerX *= 1.0F / overlaps;
        centerZ *= 1.0F / overlaps;
        float dx = (float) ((box.minX + box.maxX) * 0.5) - centerX;
        float dz = (float) ((box.minZ + box.maxZ) * 0.5) - centerZ;
        if (Math.abs(dx) < Math.ulp(1.0F) && Math.abs(dz) < Math.ulp(1.0F)) {
            dx = dz = 1.0F;
        }
        // Test escape corridors from the mean center of the overlapping shapes.
        // The shapes being escaped are excluded from the obstruction checks.
        // unlike java, anything with a collision box can push the player
        SimpleCollisionBox centered =
                box.copy().offset(centerX - (box.minX + box.maxX) * 0.5, 0.0, centerZ - (box.minZ + box.maxZ) * 0.5);
        for (Direction direction : HORIZONTAL_ESCAPES) {
            if (direction.getModX() != 0 ? dx == 0.0F : dz == 0.0F) continue;
            SimpleCollisionBox corridor =
                    centered.copy().expandToCoordinate(direction.getModX(), 0.0, direction.getModZ());
            if (surrounding.stream().noneMatch(corridor::isIntersected)) return true;
        }
        return false;
    }

    // Does minY to maxY exist in a block?
    private boolean suffocatesAt(CultPlayer player, int x, int z, double minY, double maxY) {
        SimpleCollisionBox axisAlignedBB = new SimpleCollisionBox(x, minY, z, x + 1.0, maxY, z + 1.0, false)
                .expand(-1.0E-7);
        return Collisions.suffocatesAt(player, axisAlignedBB);
    }

    private boolean moveToClosestSpace(CultPlayer player, double xPos, double yPos, double zPos, double maxY) {
        return moveTowardsClosestSpaceModern(player, xPos, zPos, yPos, maxY);
    }

    private boolean moveTowardsClosestSpaceModern(
            CultPlayer player, double xPosition, double zPosition, double minY, double maxY) {
        int blockX = (int) Math.floor(xPosition);
        int blockZ = (int) Math.floor(zPosition);

        if (!this.suffocatesAt(player, blockX, blockZ, minY, maxY)) {
            return false;
        }
        double relativeXMovement = xPosition - blockX;
        double relativeZMovement = zPosition - blockZ;
        Direction direction = null;
        double lowestValue = Double.MAX_VALUE;

        for (Direction direction2 : HORIZONTAL_ESCAPES) {
            double d6;
            double d7 = direction2 == Direction.WEST || direction2 == Direction.EAST
                    ? relativeXMovement
                    : relativeZMovement;
            d6 = direction2 == Direction.EAST || direction2 == Direction.SOUTH ? 1.0 - d7 : d7;
            // d7 and d6 flip the movement direction based on desired movement direction
            boolean doesSuffocate;
            switch (direction2) {
                case EAST:
                    doesSuffocate = this.suffocatesAt(player, blockX + 1, blockZ, minY, maxY);
                    break;
                case WEST:
                    doesSuffocate = this.suffocatesAt(player, blockX - 1, blockZ, minY, maxY);
                    break;
                case NORTH:
                    doesSuffocate = this.suffocatesAt(player, blockX, blockZ - 1, minY, maxY);
                    break;
                default:
                case SOUTH:
                    doesSuffocate = this.suffocatesAt(player, blockX, blockZ + 1, minY, maxY);
                    break;
            }

            if (d6 >= lowestValue || doesSuffocate) continue;
            lowestValue = d6;
            direction = direction2;
        }
        return direction != null;
    }

    private boolean calculateStatusForFluid(SimpleCollisionBox box, double fluidTop, BlockPos fluidPos) {
        SimpleCollisionBox fluidCollision = new SimpleCollisionBox(fluidPos);
        fluidCollision.maxY = fluidTop;

        return box.isCollided(fluidCollision);
    }

    /** Contacts ticking after the player last tick, whose push lands in this travel on the axes a teleport kept. */
    private static int pushersFromLastTick(SimulationContext context, PredictionResult lastPrediction, boolean kept) {
        if (context.getVehicle() != null
                || lastPrediction == null
                || lastPrediction.getSimulationContext() == null
                || !kept) {
            return 0;
        }
        return lastPrediction.getSimulationContext().getWorldData().getNumCollidingTickingAfterPlayer();
    }

    private static SimpleCollisionBox pushesWhileLoadingLevel(CultPlayer player, SimulationContext context) {
        if (context.getVehicle() != null) return null;
        SimpleCollisionBox pushes = player.checkManager.getSimulationProcessor().getPushesWhileLoadingLevel();
        return pushes == null ? null : pushes.copy();
    }

    private int getNumEntitiesCollidingWith(CultPlayer player, SimulationContext context) {
        int numColliding = 0;
        collidingTickingBeforePlayer = 0;
        pushingTickingAfterPlayer = 0;
        ridersTickingAfterPlayer = 0;
        List<SimpleCollisionBox> entityCollisionBoxes = null;
        List<HardCollidingEntityCollision> hardCollidingEntityCollisions = null;

        // Players in vehicles do not have collisions
        if (context.getVehicle() == null) {
            SimpleCollisionBox sweep = playerSweep(context);
            SimpleCollisionBox playerBox = pusherContactBox(sweep);
            SimpleCollisionBox entityBounds = new SimpleCollisionBox();
            SimpleCollisionBox entityScratch = new SimpleCollisionBox();
            CompensatedEntities entities = context.getEntities();
            PacketEntity rootOfSelf = entities.rootOfSelf();
            PacketEntity selfRidingRoot = entities.selfRidingRoot();
            PacketEntity serverVehicleRoot = entities.serverVehicleRoot();
            long playerTickOrder = entities.getSelf().getClientTickOrder();

            for (PacketEntity entity : entities.pushOrCollideCandidates()) {
                SimpleCollisionBox entityBox = collidingPusherBox(
                        player,
                        entities,
                        entity,
                        rootOfSelf,
                        selfRidingRoot,
                        serverVehicleRoot,
                        playerBox,
                        entityBounds,
                        entityScratch);
                if (entityBox == null) continue;
                numColliding++;
                if (entities.clientTickOrder(entity) < playerTickOrder) {
                    if (pushesPlayer(entity, entityBox, sweep)) collidingTickingBeforePlayer++;
                } else {
                    if (pushesPlayer(entity, entityBox, sweep)
                            && !(entity.isBoat() && EntityPush.carriesBoatPushAtReport(player, context)))
                        pushingTickingAfterPlayer++;
                    if (entities.isUntrackedRider(entity, selfRidingRoot, serverVehicleRoot))
                        ridersTickingAfterPlayer++;
                }
                boolean boatLike = entity.isBoat();
                boolean minecartLike = entity.isMinecart();
                if (boatLike || minecartLike || isBedrockHardCollidingEntity(entity)) {
                    if (hardCollidingEntityCollisions == null) hardCollidingEntityCollisions = new ArrayList<>();
                    hardCollidingEntityCollisions.add(
                            new HardCollidingEntityCollision(entityBox.copy(), boatLike, minecartLike));
                } else {
                    if (entityCollisionBoxes == null) entityCollisionBoxes = new ArrayList<>();
                    entityCollisionBoxes.add(entityBox.copy());
                }
            }
        }
        context.cacheEntityCollisionBoxesForMovementTick(
                entityCollisionBoxes == null ? List.of() : List.copyOf(entityCollisionBoxes));
        context.cacheHardCollidingEntityCollisionsForMovementTick(
                hardCollidingEntityCollisions == null ? List.of() : List.copyOf(hardCollidingEntityCollisions));

        return numColliding;
    }

    /** What LivingEntity#checkAutoSpinAttack may hit: no LivingEntity, any LivingEntity, or possibly a RemotePlayer. */
    private enum SpinTarget {
        NONE,
        LIVING,
        PLAYER
    }

    /** The pose-exact search; alternative entity positions remain separate boxes. */
    private static SpinTarget spinTarget(CultPlayer player, SimulationContext context) {
        if (player.isBedrockMovement()) return bedrockSpinTarget(player, context);
        var hit = JavaSpinAttack.check(player, context, context.getEnd());
        if (hit.player()) return SpinTarget.PLAYER;
        return hit.living() == JavaSpinAttack.Presence.ABSENT
                ? SpinTarget.NONE : SpinTarget.LIVING;
    }

    private static SpinTarget bedrockSpinTarget(CultPlayer player, SimulationContext context) {
        if (context.getVehicle() != null || player.riptideSpinAttackTicks <= 0) return SpinTarget.NONE;
        Vec3 from = context.getStart();
        SimpleCollisionBox start = GetBoundingBox.getPlayerBoundingBox(player, from.x, from.y, from.z);
        SimpleCollisionBox sweep = start.copy().union(context.getToMaximumExtent());
        if (RiptideUtil.releasedOnTheGround(player)) {
            for (SimpleCollisionBox lifted : RiptideUtil.liftedBoxes(player, start)) sweep.union(lifted);
        }
        SimpleCollisionBox entityBounds = new SimpleCollisionBox();
        SimpleCollisionBox entityScratch = new SimpleCollisionBox();
        CompensatedEntities entities = context.getEntities();
        PacketEntity selfRidingRoot = entities.selfRidingRoot();
        PacketEntity serverVehicleRoot = entities.serverVehicleRoot();
        SpinTarget target = SpinTarget.NONE;
        for (PacketEntity entity : entities.entityMap.values()) {
            if (!entity.isLivingEntity() || isSpectator(entity, entities)) continue;
            SimpleCollisionBox entityBox;
            if (entities.isUntrackedRider(entity, selfRidingRoot, serverVehicleRoot)) {
                if (!entities.untrackedRiderMayReach(entity, sweep, entityBounds, entityScratch)) continue;
                entityBox = entities.untrackedRiderPushBox(entity);
                if (entityBox == null) continue;
                entityBox = entity.toBoundingBox(player, entityBox);
            } else {
                entityBox = entity.toBoundingBox(
                        player, entity.getPossibleMovementCollisionBoxes(entityBounds, entityScratch));
            }
            if (!intersects(entityBox, 0.0D, 0.0D, sweep)) continue;
            if (entity instanceof PacketEntityPlayer) return SpinTarget.PLAYER;
            target = SpinTarget.LIVING;
        }
        return target;
    }

    /** EntitySelector#NO_SPECTATORS, which Level#getEntities applies, for a remote player of known game mode. */
    private static boolean isSpectator(PacketEntity entity, CompensatedEntities entities) {
        return entity instanceof PacketEntityPlayer remote
                && remote.profile != null
                && remote.actionSpectator(entities.clientPlayerModes);
    }

    /** The boxes the player's own box may hold through the context's movement. */
    private static SimpleCollisionBox playerSweep(SimulationContext context) {
        Vec3 from = context.getStart();
        SimpleCollisionBox playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(from.x, from.y, from.z, 0.6f, 1.8f);
        playerBox.union(context.getToMaximumExtent());
        return playerBox;
    }

    /** The box an entity must touch to push or collide with the player through the movement {@code sweep} holds. */
    private static SimpleCollisionBox pusherContactBox(SimpleCollisionBox sweep) {
        SimpleCollisionBox playerBox = sweep.copy();
        playerBox.expand(0.2); // bit more because why not
        return playerBox;
    }

    /**
     * Whether {@code entity}, colliding within {@code entityBox}, may push a player whose box lies within
     * {@code sweep}. A boat pushes the entities its box inflated by 0.2, -0.01, 0.2 intersects
     * (AbstractBoat#tick, AbstractBoat.java:325), and the player pushes the entities its own box intersects
     * (LivingEntity#pushEntities).
     */
    private static boolean pushesPlayer(PacketEntity entity, SimpleCollisionBox entityBox, SimpleCollisionBox sweep) {
        if (!entity.isBoat()) return true;
        return intersects(entityBox, 0.2F, -0.01F, sweep) || intersects(entityBox, 0.0D, 0.0D, sweep);
    }

    /** AABB#intersects of {@code box} inflated by {@code horizontal}, {@code vertical}, {@code horizontal}. */
    private static boolean intersects(
            SimpleCollisionBox box, double horizontal, double vertical, SimpleCollisionBox other) {
        return box.minX - horizontal < other.maxX
                && box.maxX + horizontal > other.minX
                && box.minY - vertical < other.maxY
                && box.maxY + vertical > other.minY
                && box.minZ - horizontal < other.maxZ
                && box.maxZ + horizontal > other.minZ;
    }

    /**
     * Recounts {@code prediction}'s colliding entities that tick after the player once the client tick has ended.
     * Each one's LivingEntity#pushEntities runs after its own move in ClientLevel#tickEntities, so it pushes the
     * player from where its interpolation step that tick left it, not from where it was when the player moved.
     */
    public static void recountCollidingTickingAfterPlayer(CultPlayer player, PredictionResult prediction) {
        SimulationContext context = prediction.getSimulationContext();
        if (context == null
                || context.getVehicle() != null
                || !context.getWorldData().isPushable()) return;
        // LivingEntity#pushEntities runs before Level#tickBlockEntities. From 26.3 the report is after that
        // pass, but a later entity still touched the player at the endpoint of travel, in its chosen pose.
        var pass = ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine.passAfterTravel(prediction);
        SimpleCollisionBox sweep = pass == null
                ? playerSweep(context)
                : GetBoundingBox.getBoundingBoxFromPosAndSize(
                        pass.travel().position().x,
                        pass.travel().position().y,
                        pass.travel().position().z,
                        pass.pose().width * context.getScale(),
                        pass.pose().height * context.getScale());
        SimpleCollisionBox playerBox = pusherContactBox(sweep);
        SimpleCollisionBox entityBounds = new SimpleCollisionBox();
        SimpleCollisionBox entityScratch = new SimpleCollisionBox();
        CompensatedEntities entities = context.getEntities();
        PacketEntity rootOfSelf = entities.rootOfSelf();
        PacketEntity selfRidingRoot = entities.selfRidingRoot();
        PacketEntity serverVehicleRoot = entities.serverVehicleRoot();
        long playerTickOrder = entities.getSelf().getClientTickOrder();
        int tickingAfterPlayer = 0;
        for (PacketEntity entity : entities.pushOrCollideCandidates()) {
            if (!entity.mayPushOrCollide() || entities.clientTickOrder(entity) < playerTickOrder) continue;
            if (entity.isBoat() && EntityPush.carriesBoatPushAtReport(player, context)) continue;
            // A rider pushes from the seat its vehicle gave it before this tick: the movement's own count holds it.
            if (entities.isUntrackedRider(entity, selfRidingRoot, serverVehicleRoot)) continue;
            SimpleCollisionBox entityBox = collidingPusherBox(
                    player,
                    entities,
                    entity,
                    rootOfSelf,
                    selfRidingRoot,
                    serverVehicleRoot,
                    playerBox,
                    entityBounds,
                    entityScratch);
            if (entityBox != null && pushesPlayer(entity, entityBox, sweep)) tickingAfterPlayer++;
        }
        WorldData world = context.getWorldData();
        world.setNumCollidingTickingAfterPlayer(tickingAfterPlayer + world.getNumRidersTickingAfterPlayer());
    }

    /**
     * Adds to {@code pushes} the x/z range of the Entity#push impulses a player standing at the given position takes
     * through one client tick. While LocalPlayer#tick waits for the level to load, ClientLevel#tickEntities still runs
     * every other entity's pushEntities.
     */
    public void addPushesOnStandingPlayer(CultPlayer player, double x, double y, double z, SimpleCollisionBox pushes) {
        if (player.compensatedEntities.getSelf().inVehicle()
                || !player.isBedrockMovement() && player.compensatedEntities.selfCertainlyCarriesPassenger()) return;
        SimpleCollisionBox playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(x, y, z, 0.6f, 1.8f);
        SimpleCollisionBox entityBounds = new SimpleCollisionBox();
        SimpleCollisionBox entityScratch = new SimpleCollisionBox();
        CompensatedEntities entities = player.compensatedEntities;
        PacketEntity rootOfSelf = entities.rootOfSelf();
        PacketEntity selfRidingRoot = entities.selfRidingRoot();
        PacketEntity serverVehicleRoot = entities.serverVehicleRoot();
        for (PacketEntity entity : entities.pushOrCollideCandidates()) {
            SimpleCollisionBox box = collidingPusherBox(
                    player,
                    entities,
                    entity,
                    rootOfSelf,
                    selfRidingRoot,
                    serverVehicleRoot,
                    playerBox,
                    entityBounds,
                    entityScratch);
            if (box == null) continue;
            double width = BoundingBoxSize.getWidth(player, entity);
            // The box holds every box the entity may have this tick; its centre spans the box less one size.
            double minCentreX = Math.min(box.minX + width / 2.0, (box.minX + box.maxX) / 2.0);
            double maxCentreX = Math.max(box.maxX - width / 2.0, (box.minX + box.maxX) / 2.0);
            double minCentreZ = Math.min(box.minZ + width / 2.0, (box.minZ + box.maxZ) / 2.0);
            double maxCentreZ = Math.max(box.maxZ - width / 2.0, (box.minZ + box.maxZ) / 2.0);
            EntityPush.addPushRange(pushes, minCentreX - x, maxCentreX - x, minCentreZ - z, maxCentreZ - z);
        }
    }

    /** The box of an entity that pushes or collides with the player when it touches {@code playerBox}, else null. */
    private static SimpleCollisionBox collidingPusherBox(
            CultPlayer player,
            CompensatedEntities entities,
            PacketEntity entity,
            PacketEntity rootOfSelf,
            PacketEntity selfRidingRoot,
            PacketEntity serverVehicleRoot,
            SimpleCollisionBox playerBox,
            SimpleCollisionBox entityBounds,
            SimpleCollisionBox entityScratch) {
        if (!entity.mayPushOrCollide()) return null;
        if (entities.sharesRootVehicleWithSelf(entity, rootOfSelf)) return null;

        SimpleCollisionBox entityBox;
        if (entities.isUntrackedRider(entity, selfRidingRoot, serverVehicleRoot)) {
            if (!mayPushAsUntrackedRider(player, entity)
                    || !entities.untrackedRiderMayReach(entity, playerBox, entityBounds, entityScratch)) return null;
            entityBox = entities.untrackedRiderPushBox(entity);
            if (entityBox == null) return null;
            entityBox = entity.toBoundingBox(player, entityBox);
        } else {
            // MCP-Reborn push/collision checks use the entity's concrete
            // bounding box, not the reach-expanded uncertainty box.
            entityBox =
                    entity.toBoundingBox(player, entity.getPossibleMovementCollisionBoxes(entityBounds, entityScratch));
        }
        return playerBox.isCollided(entityBox) ? entityBox : null;
    }

    /**
     * Whether a rider outside the player's riding trees pushes it: Entity#rideTick runs LivingEntity#tick, whose aiStep
     * pushes from the seat positionRider last gave the rider. Hard-colliding riders stay out, as Bedrock contact skips
     * them.
     */
    private static boolean mayPushAsUntrackedRider(CultPlayer player, PacketEntity entity) {
        return !player.isBedrockMovement()
                && entity.isLivingEntity()
                && !entity.isBoat()
                && !entity.isMinecart()
                && !isBedrockHardCollidingEntity(entity);
    }

    private static boolean isBedrockHardCollidingEntity(PacketEntity entity) {
        return entity.type == EntityTypeIds.STRIDER || entity.isHappyGhast();
    }

    private StuckEdgeData couldBeOnStuckEdge(
            CultPlayer player, Vec3 target, boolean lastOnGround, SimpleCollisionBox playerBB) {
        if (!player.isFlying
                && player.isSneaking
                && Collisions.isAboveGround(player, lastOnGround, playerBB)
                && !player.compensatedEntities.getSelf().inVehicle()) {
            // 16 - Magic number to stop people from crashing the server
            // 0.05 - Mojang's magic value that they use to calculate precision of sneaking
            // They move the position back by 0.05 blocks repeatedly until they are above ground
            // So by going forwards 0.05 blocks, we can determine if the player was influenced by this
            double posX = Math.max(0.05, CultMath.clamp(target.x, -16, 16) + 0.05);
            double posZ = Math.max(0.05, CultMath.clamp(target.z, -16, 16) + 0.05);
            double negX = Math.min(-0.05, CultMath.clamp(target.x, -16, 16) - 0.05);
            double negZ = Math.min(-0.05, CultMath.clamp(target.z, -16, 16) - 0.05);

            Vector3dm NE =
                    Collisions.maybeBackOffFromEdge(new Vector3dm(posX, 0, negZ), player, lastOnGround, playerBB, true);
            Vector3dm NW =
                    Collisions.maybeBackOffFromEdge(new Vector3dm(negX, 0, negZ), player, lastOnGround, playerBB, true);
            Vector3dm SE =
                    Collisions.maybeBackOffFromEdge(new Vector3dm(posX, 0, posZ), player, lastOnGround, playerBB, true);
            Vector3dm SW =
                    Collisions.maybeBackOffFromEdge(new Vector3dm(negX, 0, posZ), player, lastOnGround, playerBB, true);

            boolean isEast = NE.getX() != posX || SE.getX() != posX;
            boolean isWest = NW.getX() != negX || SW.getX() != negX;
            boolean isNorth = NE.getZ() != negZ || NW.getZ() != negZ;
            boolean isSouth = SE.getZ() != posZ || SW.getZ() != posZ;

            boolean zeroHorizBug = target.x == 0 && target.z == 0 && (isEast || isWest || isNorth || isSouth);

            return new StuckEdgeData(isNorth, isSouth, isEast, isWest, zeroHorizBug);
        }
        return NOT_ON_EDGE;
    }

    BubbleColumnData computeBubbleColumns(CultPlayer player, Vec3 to, SimulationContext context) {
        Vec3 from = context.getStart();

        int[] pushUpwards = new int[1];
        int[] pushDownwards = new int[1];
        int[] airUpwards = new int[1];
        int[] airDownwards = new int[1];
        Set<Long> visitedBlocks = new HashSet<>();

        double movementX = to.x - from.x;
        double movementY = to.y - from.y;
        double movementZ = to.z - from.z;
        if (movementX * movementX + movementY * movementY + movementZ * movementZ > 0.0) {
            // MCP-Reborn Entity#move records the clipped movement plus the original axis order.
            // Entity#checkInsideBlocks then replays that movement one axis at a time before
            // BubbleColumnBlock#entityInside applies Entity#onAboveBubbleColumn/onInsideBubbleColumn.
            double stepX = from.x, stepY = from.y, stepZ = from.z;
            for (Direction.Axis axis : axisStepOrder(context.getTarget())) {
                double movementOnAxis = switch (axis) {
                    case X -> movementX;
                    case Y -> movementY;
                    case Z -> movementZ;
                };
                if (movementOnAxis == 0.0) {
                    continue;
                }

                Direction direction = positiveDirection(axis);
                double nextX = stepX + movementOnAxis * direction.getModX();
                double nextY = stepY + movementOnAxis * direction.getModY();
                double nextZ = stepZ + movementOnAxis * direction.getModZ();
                accumulateBubbleColumns(
                        player,
                        context,
                        stepX,
                        stepY,
                        stepZ,
                        nextX,
                        nextY,
                        nextZ,
                        visitedBlocks,
                        pushUpwards,
                        pushDownwards,
                        airUpwards,
                        airDownwards);
                stepX = nextX;
                stepY = nextY;
                stepZ = nextZ;
            }
        } else {
            accumulateBubbleColumns(
                    player,
                    context,
                    from.x,
                    from.y,
                    from.z,
                    to.x,
                    to.y,
                    to.z,
                    visitedBlocks,
                    pushUpwards,
                    pushDownwards,
                    airUpwards,
                    airDownwards);
        }

        return new BubbleColumnData(airUpwards[0], pushUpwards[0], airDownwards[0], pushDownwards[0]);
    }

    private static List<Direction.Axis> axisStepOrder(Vec3 movement) {
        // MCP-Reborn 26.1 net.minecraft.core.Direction#axisStepOrder and the 1.21.1 client
        // Entity#collideWithShapes both resolve Y first, then the smaller
        // horizontal component before the larger one.
        return Math.abs(movement.x) < Math.abs(movement.z) ? Y_Z_X : Y_X_Z;
    }

    private static Direction positiveDirection(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.EAST;
            case Y -> Direction.UP;
            case Z -> Direction.SOUTH;
        };
    }

    private void accumulateBubbleColumns(
            CultPlayer player,
            SimulationContext context,
            double fromX,
            double fromY,
            double fromZ,
            double toX,
            double toY,
            double toZ,
            Set<Long> visitedBlocks,
            int[] pushUpwards,
            int[] pushDownwards,
            int[] airUpwards,
            int[] airDownwards) {
        SimpleCollisionBox fromBox = bubbleColumnBoxAt(player, context, fromX, fromY, fromZ);
        SimpleCollisionBox toBox = bubbleColumnBoxAt(player, context, toX, toY, toZ);
        SimpleCollisionBox sweptBox = fromBox.copy().union(toBox);
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        boolean longMove = dx * dx + dy * dy + dz * dz > 0.9999900000002526D * 0.9999900000002526D;

        Collisions.hasState(player, sweptBox, state -> BlockIds.is(state, BlockIds.BUBBLE_COLUMN), (state, pos) -> {
            if (!BlockIds.is(state, BlockIds.BUBBLE_COLUMN)) {
                return false;
            }

            boolean preciseIntersection = longMove || intersectsBlock(toBox, 1.0E-5F, pos);
            boolean pathTouches = preciseIntersection
                    || Collisions.sweptFullBlockEntityInside(fromBox, toBox, pos.getX(), pos.getY(), pos.getZ());
            if (!pathTouches || !preciseIntersection || !visitedBlocks.add(pos.asLong())) {
                return false;
            }

            int blockAbove = player.compensatedWorld.getBlockStateIdAt(pos.getX(), pos.getY() + 1, pos.getZ());
            boolean isDown = BlockProps.DRAG.has(state) && BlockProps.DRAG.booleanValue(state);
            boolean airAbove = player.compensatedWorld
                            .geometry()
                            .collision(blockAbove, pos)
                            .isEmpty()
                    && ac.cult.cultac.utils.nmsutil.ClientFluidQueries.modelFluid(blockAbove)
                            .isEmpty();
            if (isDown) {
                if (airAbove) airDownwards[0]++;
                else pushDownwards[0]++;
            } else {
                if (airAbove) airUpwards[0]++;
                else pushUpwards[0]++;
            }
            return false;
        });
    }

    static SimpleCollisionBox bubbleColumnBoxAt(
            CultPlayer player, SimulationContext context, double x, double y, double z) {
        if (context.getVehicle() == null) {
            if (!player.isBedrockMovement()) {
                // These counts bound possible callbacks; the estimated pose cannot bound all client boxes.
                return GetBoundingBox.getBoundingBoxFromPosAndSize(
                        x, y, z, context.getMaxWidth(), context.getMaxHeight());
            }
            return GetBoundingBox.getBoundingBoxFromPosAndSize(
                    x,
                    y,
                    z,
                    context.getPose().width * context.getScale(),
                    context.getPose().height * context.getScale());
        }

        return GetBoundingBox.getBoundingBoxFromPosAndSize(x, y, z, context.getMaxWidth(), context.getMaxHeight());
    }

    private boolean intersectsBlock(SimpleCollisionBox box, double inset, BlockPos pos) {
        return box.minX + inset < pos.getX() + 1.0D
                && box.maxX - inset > pos.getX()
                && box.minY + inset < pos.getY() + 1.0D
                && box.maxY - inset > pos.getY()
                && box.minZ + inset < pos.getZ() + 1.0D
                && box.maxZ - inset > pos.getZ();
    }

    public DesyncStatus isSlidingDown(CultPlayer player, SimulationContext context) {
        // MCP-Reborn Entity#move records the movement path, and Entity#baseTick
        // later calls checkInsideBlocks(movements). HoneyBlock#entityInside can
        // therefore run while leaving the honey side even if the final AABB is
        // no longer inside the honey block.
        SimpleCollisionBox smallHoneySearch = context.getFromMinimumExtent().union(context.getToMinimumExtent());
        SimpleCollisionBox largeHoneySearch = context.getFromMaximumExtent().union(context.getToMaximumExtent());
        IntPredicate honey = block -> BlockIds.is(block, BlockIds.HONEY_BLOCK);
        Collisions.StatePredicate sliding =
                (block, pos) -> Collisions.isSlidingDown(player, pos.getX(), pos.getY(), pos.getZ(), context.getEnd());
        boolean hasSmall = Collisions.hasState(player, smallHoneySearch, honey, sliding);
        boolean hasLarge = Collisions.hasState(player, largeHoneySearch, honey, sliding);
        return DesyncStatus.fromBooleans(hasSmall, hasLarge);
    }

    public StuckSpeedData calculateStuckSpeed(CultPlayer player, SimulationContext context, PistonPushes push) {
        // Preserve the existing extent approximation. A vehicle uses its full body;
        // player minimum/maximum extents account for pose and movement-threshold uncertainty.
        boolean inVehicle = context.getVehicle() != null;
        SimpleCollisionBox primaryFrom = inVehicle ? context.getFromMaximumExtent() : context.getFromMinimumExtent();
        SimpleCollisionBox primaryTo = inVehicle ? context.getToMaximumExtent() : context.getToMinimumExtent();
        boolean powderSnowCanApply = powderSnowCanApplyToRootEntity(player, context);
        Vec3 required = resolveStuckSpeedForExtent(player, context, primaryFrom, primaryTo, powderSnowCanApply);
        Vec3 optional = inVehicle
                ? null
                : resolveStuckSpeedForExtent(
                        player,
                        context,
                        context.getFromMaximumExtent(),
                        context.getToMaximumExtent(),
                        powderSnowCanApply);

        boolean hasPistonPush = push != null
                && push.getPistonPush() != null
                && !push.getPistonPush().isEmpty();
        if (hasPistonPush) {
            // A piston shove the Bedrock envelope allows may consume the stuck speed before the next move, so the
            // speed this move's blocks set is optional rather than required for the next packet.
            return new StuckSpeedData(null, firstKnownStuckSpeed(required, optional));
        }

        // This two-result representation does not cover every intermediate-box outcome.
        if (optional != null && optional.equals(required)) optional = null;
        return new StuckSpeedData(required, optional);
    }

    private Vec3 resolveStuckSpeedForExtent(
            CultPlayer player,
            SimulationContext context,
            SimpleCollisionBox from,
            SimpleCollisionBox to,
            boolean powderSnowCanApply) {
        ClientVersion version = player.getClientVersion();
        if (player.bedrockState == null
                && version.isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && version.isOlderThan(ClientVersion.V_1_21_9)) {
            return Collisions.checkStuckSpeedAlongMovementBefore1Dot21Dot9(
                    player,
                    from,
                    to,
                    version.isNewerThanOrEquals(ClientVersion.V_1_21_5),
                    version.isOlderThan(ClientVersion.V_1_21_4),
                    powderSnowCanApply);
        }
        Vec3 endpoint = Collisions.checkStuckSpeed(player, to, powderSnowCanApply);
        boolean ordered =
                player.bedrockState == null && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2);
        Vec3 path = ordered
                ? Collisions.checkStuckSpeedAlongMovement26Dot2(
                        player, from, to, context.getTarget(), powderSnowCanApply)
                : Collisions.checkStuckSpeedAlongMovement(player, from, to, context.getTarget(), powderSnowCanApply);
        // Keep the existing priorities: modern path order can override an endpoint effect.
        return ordered ? firstKnownStuckSpeed(path, endpoint) : firstKnownStuckSpeed(endpoint, path);
    }

    private boolean powderSnowCanApplyToRootEntity(CultPlayer player, SimulationContext context) {
        PacketEntity rootVehicle = context.getVehicle();
        if (rootVehicle != null && !rootVehicle.isLivingEntity()) {
            return true;
        }

        // PowderSnowBlock#entityInside only calls Entity#makeStuckInBlock for a
        // LivingEntity when entity.getInBlockState().is(this). Entity#getInBlockState
        // is the block at the root entity's current blockPosition, not every block
        // intersected by its body AABB during Entity#checkInsideBlocks.
        Vec3 end = context.getEnd();
        return BlockIds.is(
                player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(end.x, end.y, end.z)),
                BlockIds.POWDER_SNOW);
    }

    private Vec3 firstKnownStuckSpeed(Vec3 primary, Vec3 swept) {
        return primary != null ? primary : swept;
    }

    public MainSupportingBlockData findMainSupportingBlockPos(
            CultPlayer player,
            SimulationContext lastContext,
            double lastMovementX,
            double lastMovementZ,
            SimpleCollisionBox maxPose,
            boolean isOnGround) {
        return findMainSupportingBlockPos(
                player,
                lastContext == null ? null : lastContext.getWorldData().getMainSupportingBlockPos(),
                lastMovementX,
                lastMovementZ,
                maxPose,
                isOnGround);
    }

    /**
     * Entity#checkSupportingBlock after a move of ({@code lastMovementX}, {@code lastMovementZ}) that left the box
     * {@code maxPose}, from the support {@code previous} the move started with (null when unknown).
     */
    public MainSupportingBlockData findMainSupportingBlockPos(
            CultPlayer player,
            MainSupportingBlockData previous,
            double lastMovementX,
            double lastMovementZ,
            SimpleCollisionBox maxPose,
            boolean isOnGround) {
        return findMainSupportingBlockPos(
                player, previous, lastMovementX, lastMovementZ, maxPose, isOnGround, new Vec3(player.x, player.y, player.z));
    }

    /**
     * The same support for a replayed move: CollisionGetter#findSupportingBlock scores distances against the
     * mover's own position after its Entity#move, {@code ownPosition}, not the packet's reported position.
     */
    public MainSupportingBlockData findMainSupportingBlockPos(
            CultPlayer player,
            MainSupportingBlockData previous,
            double lastMovementX,
            double lastMovementZ,
            SimpleCollisionBox maxPose,
            boolean isOnGround,
            Vec3 ownPosition) {
        if (!isOnGround) {
            return new MainSupportingBlockData(null, false);
        }

        SimpleCollisionBox slightlyBelowPlayer = new SimpleCollisionBox(
                maxPose.minX, maxPose.minY - 1.0E-6D, maxPose.minZ, maxPose.maxX, maxPose.minY, maxPose.maxZ);

        BlockPos supportingBlock = findSupportingBlock(player, slightlyBelowPlayer, ownPosition);
        if (supportingBlock == null && (previous != null && !previous.lastOnGroundAndNoBlock())) {
            SimpleCollisionBox aabb2 = slightlyBelowPlayer.offset(-lastMovementX, 0.0D, -lastMovementZ);
            return new MainSupportingBlockData(findSupportingBlock(player, aabb2, ownPosition), true);
        }
        return new MainSupportingBlockData(supportingBlock, true);
    }

    /** The supporting block Entity#checkSupportingBlock picks inside {@code searchBox}, or null. */
    private BlockPos findSupportingBlock(CultPlayer player, SimpleCollisionBox searchBox, Vec3 ownPosition) {
        SupportScan scan = supportScan.inUse ? new SupportScan() : supportScan;
        try {
            // The cells BlockCollisions visits: one layer past the search box on each axis.
            SimpleCollisionBox cells = new SimpleCollisionBox(
                    Math.floor(searchBox.minX - 1.0E-7) - 1,
                    Math.floor(searchBox.minY - 1.0E-7) - 1,
                    Math.floor(searchBox.minZ - 1.0E-7) - 1,
                    Math.floor(searchBox.maxX + 1.0E-7) + 1,
                    Math.floor(searchBox.maxY + 1.0E-7) + 1,
                    Math.floor(searchBox.maxZ + 1.0E-7) + 1);
            Collisions.hasState(player, cells, scan.start(player, searchBox, cells, ownPosition));
            return scan.best;
        } finally {
            scan.clear();
        }
    }

    /** The per-block test of {@link #findSupportingBlock}: never matches, it keeps the closest supporting block. */
    private final class SupportScan implements Collisions.StateAtPredicate {
        private boolean inUse;
        private CultPlayer player;
        private SimpleCollisionBox searchBox;
        private SimpleCollisionBox cells;
        private double playerX, playerY, playerZ;
        private BlockPos best;
        private double bestDistance;

        private SupportScan start(
                CultPlayer player, SimpleCollisionBox searchBox, SimpleCollisionBox cells, Vec3 ownPosition) {
            inUse = true;
            this.player = player;
            this.searchBox = searchBox;
            this.cells = cells;
            playerX = ownPosition.x;
            playerY = ownPosition.y;
            playerZ = ownPosition.z;
            best = null;
            bestDistance = Double.MAX_VALUE;
            return this;
        }

        private void clear() {
            player = null;
            searchBox = null;
            cells = null;
            best = null;
            inUse = false;
        }

        @Override
        public boolean test(int block, int x, int y, int z) {
            // BlockCollisions#computeNext: an outer cell on one axis holds only a block with a large collision
            // shape, on two axes only a moving piston, and on all three none.
            int outer = (x == cells.minX || x == cells.maxX ? 1 : 0)
                    + (y == cells.minY || y == cells.maxY ? 1 : 0)
                    + (z == cells.minZ || z == cells.maxZ ? 1 : 0);
            if (outer == 3
                    || outer == 1 && !BLOCKS.facts(block).has(StateFacts.LARGE_COLLISION)
                    || outer == 2 && !BlockIds.is(block, BlockIds.MOVING_PISTON)) return false;

            CollisionBox collision =
                    ClientBlockShapes.movement(player, block, x, y, z);
            // BlockCollisions uses AABB#intersects for Shapes.block(), without the voxel join's 1.0E-7 epsilon.
            // A sub-epsilon overlap can therefore retain a full stone support beside a lower honey block.
            boolean fullBlock = collision instanceof VoxelShapeCollisionBox voxel
                    ? voxel.usesStrictFullBlockIntersection() : collision.isFullBlock();
            boolean intersects = !player.isBedrockMovement() && fullBlock
                    ? searchBox.intersects(
                            x, y, z,
                            x + 1.0, y + 1.0, z + 1.0)
                    : collision.isIntersected(searchBox);
            if (!intersects) return false;

            double distanceX = x + 0.5 - playerX;
            double distanceY = y + 0.5 - playerY;
            double distanceZ = z + 0.5 - playerZ;
            double distance = distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ;

            if (distance < bestDistance) {
                best = new BlockPos(x, y, z);
                bestDistance = distance;
            } else if (distance == bestDistance) {
                // CollisionGetter#findSupportingBlock keeps the greatest BlockPos#compareTo among equal distances.
                BlockPos blockPos = new BlockPos(x, y, z);
                if (best == null || best.compareTo(blockPos) < 0) {
                    best = blockPos;
                    bestDistance = distance;
                }
            }

            return false;
        }
    }
}
