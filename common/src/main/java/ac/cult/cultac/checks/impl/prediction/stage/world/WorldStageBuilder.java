package ac.cult.cultac.checks.impl.prediction.stage.world;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects;
import ac.cult.cultac.checks.impl.prediction.runner.PredictionScratch;
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
import ac.cult.cultac.utils.data.*;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityArmorStand;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedWorld;
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
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import lombok.Data;
import org.jetbrains.annotations.Nullable;

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
    /** Never written: BubbleColumnData has no mutating caller. */
    private static final BubbleColumnData NO_BUBBLE_COLUMN = new BubbleColumnData(0, 0, 0, 0);
    private static final IntPredicate IS_BUBBLE_COLUMN = state -> BlockIds.is(state, BlockIds.BUBBLE_COLUMN);
    /** The probes' suffocation tests while {@link #blockPushes} walks an envelope, else null. */
    private Map<SuffocationProbe, Boolean> suffocation;

    private final SupportScan supportScan = new SupportScan();
    // Box scratch: each holds a box for one query on the owning player's thread and is never stored or returned.
    private final SimpleCollisionBox minFluidBox = new SimpleCollisionBox();
    private final SimpleCollisionBox maxFluidBox = new SimpleCollisionBox();
    private final SimpleCollisionBox extentBox = new SimpleCollisionBox();
    private final SimpleCollisionBox sweepFromBox = new SimpleCollisionBox();
    private final SimpleCollisionBox sweepToBox = new SimpleCollisionBox();
    private final SimpleCollisionBox sweptBox = new SimpleCollisionBox();
    private final SimpleCollisionBox pusherContactBox = new SimpleCollisionBox();
    private final SimpleCollisionBox entityBoundsBox = new SimpleCollisionBox();
    private final SimpleCollisionBox entityScratchBox = new SimpleCollisionBox();
    private final SimpleCollisionBox spinStartBox = new SimpleCollisionBox();
    private final SimpleCollisionBox spinSweepBox = new SimpleCollisionBox();
    private final SimpleCollisionBox spinExtentBox = new SimpleCollisionBox();
    // One computeBubbleColumns call's sweep and counts, which bubbleColumnVisitor reads and writes.
    private final Collisions.StatePredicate bubbleColumnVisitor = this::visitBubbleColumn;
    private CultPlayer bubblePlayer;
    private SimpleCollisionBox bubbleFromBox, bubbleToBox;
    private boolean bubbleLongMove;
    private @Nullable LongOpenHashSet bubbleVisited;
    private int bubblePushUpwards, bubblePushDownwards, bubbleAirUpwards, bubbleAirDownwards;
    private int collidingTickingBeforePlayer;
    private int pushingTickingAfterPlayer;
    private int ridersTickingAfterPlayer;

    public WorldData generateWorldData(
            CultPlayer player,
            SimulationContext simulationContext,
            PredictionResult lastPrediction,
            DesyncStatus lastOnGround) {
        Vec3 to = simulationContext.getEnd();
        Vec3 from = simulationContext.getStart();
        double deltaX = to.x - from.x;
        double deltaZ = to.z - from.z;

        // -0.001 is mojang's magic value, not ours.
        double expandAmount = 0.001;
        boolean javaOnFoot = simulationContext.getVehicle() == null && !player.isBedrockMovement();
        FluidState minFluidState;
        FluidState maxFluidState;
        if (javaOnFoot) {
            // Entity#baseTick reads the box of the pose and scale Player#updatePlayerPose left last tick.
            SimpleCollisionBox poseBox = simulationContext.getFromActualPose(minFluidBox);
            double poseReferenceY = poseBox.minY;
            minFluidState = getFluidState(player, poseBox.expand(-expandAmount), poseReferenceY, true);
            maxFluidState = minFluidState;
        } else {
            SimpleCollisionBox minFluid =
                    simulationContext.getFromMinimumExtent(minFluidBox).expand(-expandAmount);
            SimpleCollisionBox maxFluid =
                    simulationContext.getFromMaximumExtent(maxFluidBox).expand(-expandAmount);

            double minFluidReferenceY = simulationContext.getFromMinimumExtent(extentBox).minY;
            double maxFluidReferenceY = simulationContext.getVehicle() != null
                    ? minFluidReferenceY
                    : simulationContext.getFromMaximumExtent(extentBox).minY;

            minFluidState = getFluidState(player, minFluid, minFluidReferenceY, false);
            // Vehicles have the same thing as the fluid state as before.
            maxFluidState = simulationContext.getVehicle() != null
                    ? minFluidState
                    : getFluidState(player, maxFluid, maxFluidReferenceY, false);
        }

        DesyncStatus isInWater = DesyncStatus.fromBooleans(minFluidState.isInWater(), maxFluidState.isInWater());
        DesyncStatus isInLava = DesyncStatus.fromBooleans(minFluidState.isInLava(), maxFluidState.isInLava());
        DesyncStatus touchingLava = isInLava;
        DesyncStatus isInFlowingFluid =
                DesyncStatus.fromBooleans(minFluidState.isInFlowingFluid(), maxFluidState.isInFlowingFluid());

        if (minFluidState.isWaterTimingUncertain() || maxFluidState.isWaterTimingUncertain()) {
            if (!javaOnFoot) isInWater = DesyncStatus.UNKNOWN;
            isInFlowingFluid = DesyncStatus.UNKNOWN;
        }
        if (minFluidState.isLavaTimingUncertain() || maxFluidState.isLavaTimingUncertain()) {
            if (!javaOnFoot) {
                isInLava = DesyncStatus.UNKNOWN;
                touchingLava = DesyncStatus.UNKNOWN;
            }
            isInFlowingFluid = DesyncStatus.UNKNOWN;
        }
        if (javaOnFoot) {
            isInWater = FluidRange.presence(minFluidState.getWater(), maxFluidState.getWater());
            isInLava = FluidRange.presence(minFluidState.getLava(), maxFluidState.getLava());
            touchingLava = isInLava;
        }

        if (player.packetStateData.riptideLevel > 0) {
            isInWater = DesyncStatus.UNKNOWN;
            isInLava = DesyncStatus.UNKNOWN;
            isInFlowingFluid = DesyncStatus.UNKNOWN;
        }

        boolean canFluidHop = false;

        if (lastPrediction != null) {
            WorldData oldWorldData = lastPrediction.getSimulationContext().getWorldData();
            boolean wasInWaterOrLava = oldWorldData.getInWater().determineOptimistically()
                    || oldWorldData.getInLava().determineOptimistically();
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
        DesyncStatus riddenFloat = ClientFluidQueries.floatWhileRidden(
                player,
                simulationContext,
                minFluidState.isInWater(),
                minFluidState.getMaxWaterHeight(),
                minFluidState.isWaterTimingUncertain());
        boolean canFloatWhileRidden = riddenFloat.determinePessimistically();
        boolean couldFloatWhileRidden = riddenFloat.determineOptimistically();

        // LivingEntity#travelFallFlying samples the starting block, before movement.
        DesyncStatus climbingAtStart = sampleClimbingAtStart(player, simulationContext);

        DesyncStatus climbing = DesyncStatus.fromBoolean(Collisions.onClimbable(player, to.x, to.y, to.z));

        if (player.getInventory().getBoots().getItem() == ItemTypes.LEATHER_BOOTS
                && wasInPowderSnow(player, lastPrediction, to)) {
            climbing = DesyncStatus.UNKNOWN;
        }

        // Pigs, striders and nautiluses keep LivingEntity#onClimbable; AbstractHorse and HappyGhast return false
        if (simulationContext.getVehicle() != null
                && !(simulationContext.getVehicle() instanceof PacketEntityStrider
                        || simulationContext.getVehicle() instanceof PacketEntityNautilus
                        || simulationContext.getVehicle().type == EntityTypeIds.PIG)) {
            climbingAtStart = DesyncStatus.FALSE;
            climbing = DesyncStatus.FALSE;
        }

        MainSupportingBlockData mainSupportingBlockData = findMainSupportingBlockPos(
                player,
                player.checkManager.getSimulationProcessor().lastTickSupport(lastPrediction),
                deltaX,
                deltaZ,
                simulationContext.getToMaxPose(),
                player.onGround);

        int numColliding = getNumEntitiesCollidingWith(player, simulationContext);
        SpinTarget spinTarget = spinTarget(player, simulationContext);
        CarriedPush carriedPush = player.checkManager.getSimulationProcessor().getCarriedPush();
        int numCarriedPushing = pushersFromLastTick(simulationContext, lastPrediction, !carriedPush.isZero());
        boolean pushable =
                player.isBedrockMovement() || !simulationContext.getEntities().selfCertainlyCarriesPassenger();
        int numPushing = (pushable ? collidingTickingBeforePlayer : 0) + numCarriedPushing;
        int onBlock = BlockProperties.getOnPos(player, mainSupportingBlockData, to);

        SimpleCollisionBox minExtent = simulationContext.getFromMinimumExtent(extentBox);
        StuckEdgeData stuckEdgeData = couldBeOnStuckEdge(
                player,
                simulationContext.getTarget(),
                lastOnGround.determineOptimistically(),
                JavaFallDistance.leastBeforeMove(player, simulationContext, isInWater, isInLava),
                minExtent);
        // If the player isn't in water, then they aren't in a bubble column
        final BubbleColumnData bubble = computeBubbleColumns(player, to, simulationContext);

        PistonPushes push = player.compensatedWorld.tickPlayerInPistonPushingArea(
                simulationContext,
                lastOnGround.determineOptimistically(),
                lastPrediction == null
                        ? null
                        : lastPrediction.getSimulationContext().getWorldData().getPistonPushes());
        List<BlockPush> blockPushes = blockPushes(player, simulationContext, push);
        // Java 1.21.2+ sends tick-end after the block-entity pass. Its piston,
        // shulker and recent world state already advance in CheckManager at that
        // boundary, including idle ticks; prediction must not advance them again.
        StuckSpeedData stuckSpeedData = calculateStuckSpeed(player, simulationContext, push);

        // Entity#baseTick samples the eye at the movement start, at the pose Player#updatePlayerPose left last tick.
        boolean eyeInWater = eyeInWater(player, simulationContext, player.pose, extentBox);
        // LocalPlayer#isUnderWater is the eye Player#updateIsUnderwater read before this tick's Entity#baseTick.
        boolean underwater = lastPrediction != null
                && lastPrediction.getSimulationContext().getWorldData().isEyeInWater();
        // Entity#updateSwimming runs in Entity#baseTick, before LocalPlayer#aiStep updates the sprint and flying
        // flags; Player#updateSwimming clears it while flying, which only a flight kept since last tick proves.
        boolean swimming = simulationContext.getVehicle() == null
                && !(player.isFlying && player.swimState.flewLastTick())
                && player.sprintState.flagBeforeAiStep()
                && (player.swimState.swimming() && player.gamemode != GameMode.SPECTATOR
                        ? isInWater.determineOptimistically()
                        : underwater && player.compensatedWorld.getWaterFluidLevelAt(from.x, from.y, from.z) > 0);
        // Portal contact uses the swept inside-block volume.
        player.intersectedWithNetherPortal = Collisions.hasState(
                player,
                simulationContext
                        .getFromMinimumExtent(sweepFromBox)
                        .union(simulationContext.getToMinimumExtent(sweepToBox)),
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

        Boolean groundAfterShove = push.onGroundAfterShoveBeforeTravel();
        if (simulationContext.isRiptideReleaseCertain()) {
            // Entity#move of TridentItem#releaseUsing's upward lift leaves onGround false before LivingEntity#aiStep.
            desyncLastOnGround = DesyncStatus.FALSE;
        } else if (groundAfterShove != null) {
            desyncLastOnGround = DesyncStatus.fromBoolean(groundAfterShove);
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

        SimpleCollisionBox fishingRodPulls = findFishingRodPullsBox(player);

        // Negative jump boost breaks onGround optimization
        boolean hasNegativeJumpBoost =
                player.compensatedEntities.getPotionLevelForPlayer(MovementEffect.JUMP_BOOST) != null
                        && player.compensatedEntities.getPotionLevelForPlayer(MovementEffect.JUMP_BOOST) < 0;
        boolean canJump = desyncLastOnGround.determineOptimistically()
                && (!javaOnFoot || mayJumpFromGround(minFluidState, fluidJumpThreshold(simulationContext)))
                && (!simulationContext.isOnGround()
                        || hasNegativeJumpBoost
                        || canGroundJumpInShallowFluid(simulationContext, minFluidState, maxFluidState))
                && (simulationContext.getVehicle() == null
                        || simulationContext.getVehicle() instanceof PacketEntityHorse);

        return new WorldData(
                desyncLastOnGround,
                isInWater,
                isInLava,
                touchingLava,
                isInFlowingFluid,
                blockPushes.stream().anyMatch(BlockPush::pushes),
                blockPushes,
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
                fishingRodPulls,
                pushesWhileLoadingLevel(player, simulationContext),
                lastOnGround,
                eyeInWater,
                underwater,
                swimming,
                simulationContext.getVehicle() != null && minFluidState.isInWater(),
                spinTarget != SpinTarget.NONE,
                spinTarget == SpinTarget.PLAYER);
    }

    // EntityFluidInteraction#update finds the eye only among the fluids inside Entity#getFluidInteractionBox.
    private static boolean eyeInWater(
            CultPlayer player, SimulationContext context, Pose pose, SimpleCollisionBox body) {
        Vec3 from = context.getStart();
        if (context.getVehicle() != null) {
            if (player.isBedrockMovement()) {
                return ClientFluidQueries.eyesInWater(
                        player, context.getFromMinimumExtent(), from, from.y + pose.eyeHeight * player.dimensionScale);
            }
            // Entity#rideTick runs the passenger's Entity#baseTick where the previous Entity#positionRider seated it;
            // on the first ride tick that is where Entity#startRiding found it, standing.
            Vec3 seat = player.compensatedEntities.lastRideTickSeat();
            from = seat != null ? seat : new Vec3(player.x, player.y, player.z);
            if (seat == null) pose = Pose.STANDING;
        }
        GetBoundingBox.getBoundingBoxFromPosAndSize(
                body, from.x, from.y, from.z, pose.width * player.dimensionScale, pose.height * player.dimensionScale);
        return ClientFluidQueries.eyesInWater(player, body, from, from.y + pose.eyeHeight * player.dimensionScale);
    }

    /**
     * Entity#wasInPowderSnow, which LivingEntity#handleRelativeFrictionAndCalculateMovement reads after this move:
     * Entity#baseTick copies it from the powder snow the previous tick's Entity#applyEffectsFromBlocks entered.
     */
    private static boolean wasInPowderSnow(CultPlayer player, @Nullable PredictionResult lastPrediction, Vec3 to) {
        Boolean entered =
                lastPrediction == null ? null : JavaInsideBlockEffects.enteredPowderSnow(player, lastPrediction);
        if (entered != null) return entered;
        return BlockIds.is(
                player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(to.x, to.y, to.z)),
                BlockIds.POWDER_SNOW);
    }

    private DesyncStatus sampleClimbingAtStart(CultPlayer player, SimulationContext context) {
        Vec3 position = context.getStart();
        DesyncStatus climbingAtStart = DesyncStatus.fromBoolean(
                Collisions.onClimbable(player, position.x, position.y, position.z, context.isGliding()));
        return climbingAtStart;
    }

    /**
     * Whether LivingEntity#aiStep may run jumpFromGround for some state the fluid cells may hold: in lava only at or
     * below the threshold, otherwise in water only at or below it.
     */
    private static boolean mayJumpFromGround(FluidState state, double threshold) {
        FluidRange water = state.getWater();
        FluidRange lava = state.getLava();
        return lava.may && lava.least() <= threshold
                || !lava.must && (!water.must || water.least() <= threshold);
    }

    /** Entity#getFluidJumpThreshold at the pose and scale LivingEntity#aiStep runs with. */
    private static double fluidJumpThreshold(SimulationContext context) {
        return context.getPose().eyeHeight * context.getScale() < 0.4 ? 0.0 : 0.4;
    }

    private boolean canGroundJumpInShallowFluid(
            SimulationContext simulationContext, FluidState minFluidState, FluidState maxFluidState) {
        if (!simulationContext.isOnGround()) {
            return false;
        }

        // MCP-Reborn LivingEntity#aiStep calls jumpFromGround, not jumpInLiquid,
        // when this.onGround() is true and the current fluid height is at or
        // below Entity#getFluidJumpThreshold() (0.4 for normal players).
        return minFluidState.canGroundJumpFromFluid()
                || (simulationContext.getVehicle() == null && maxFluidState.canGroundJumpFromFluid());
    }

    @Data
    private static class FluidState {
        boolean inWater;
        boolean inLava;
        boolean inFlowingFluid;
        double maxWaterHeight;
        double maxLavaHeight;
        boolean waterTimingUncertain;
        boolean lavaTimingUncertain;
        final FluidRange water = new FluidRange();
        final FluidRange lava = new FluidRange();

        private boolean canGroundJumpFromFluid() {
            return (inWater && maxWaterHeight > 0.0D && maxWaterHeight <= 0.4D)
                    || (inLava && maxLavaHeight > 0.0D && maxLavaHeight <= 0.4D);
        }
    }

    /**
     * One fluid in a box over every combination of the states recently changed cells may still hold on the client;
     * heights are EntityFluidInteraction#update's tracker height.
     */
    private static final class FluidRange {
        boolean may;
        boolean must;
        double highest;
        private double mustLeast;
        private double optionalLeast = Double.POSITIVE_INFINITY;

        /** The least height of a combination holding the fluid. */
        double least() {
            return must ? mustLeast : optionalLeast;
        }

        void addCell(boolean any, boolean all, double low, double high) {
            if (!any) return;
            may = true;
            highest = Math.max(highest, high);
            if (all) {
                must = true;
                mustLeast = Math.max(mustLeast, low);
            } else {
                optionalLeast = Math.min(optionalLeast, low);
            }
        }

        static DesyncStatus presence(FluidRange a, FluidRange b) {
            return DesyncStatus.fromBooleans(a.must && b.must, a.may || b.may);
        }
    }

    private FluidState getFluidState(
            CultPlayer player, SimpleCollisionBox box, double entityBoundingBoxMinY, boolean withRecentChanges) {
        FluidState state = new FluidState();
        state.setWaterTimingUncertain(player.compensatedWorld.hasRecentClientWaterChanges(box));
        state.setLavaTimingUncertain(player.compensatedWorld.hasRecentClientLavaChanges(box));
        scanClientFluid(player, box, entityBoundingBoxMinY, state);
        if (withRecentChanges && !scanRecentFluidChanges(player, box, entityBoundingBoxMinY, state)) {
            state.water.addCell(state.inWater, true, state.maxWaterHeight, state.maxWaterHeight);
            state.lava.addCell(state.inLava, true, state.maxLavaHeight, state.maxLavaHeight);
        }
        return state;
    }

    /**
     * Fills {@code state}'s ranges from every combination of the states the cells of {@code box}, and the cells above
     * them, held since a recent change; false when no such cell has one.
     */
    private boolean scanRecentFluidChanges(
            CultPlayer player, SimpleCollisionBox box, double entityBoundingBoxMinY, FluidState state) {
        CompensatedWorld world = player.compensatedWorld;
        int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY), maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ);
        boolean changed = false;
        for (int x = minX; x <= maxX && !changed; x++) {
            for (int y = minY; y <= maxY + 1 && !changed; y++) {
                for (int z = minZ; z <= maxZ && !changed; z++) {
                    changed = world.recentClientFluidReplacedStates(x, y, z) != null;
                }
            }
        }
        if (!changed) return false;

        IntArrayList blocks = new IntArrayList(2);
        IntArrayList aboves = new IntArrayList(2);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    statesAt(world, x, y, z, blocks);
                    statesAt(world, x, y + 1, z, aboves);
                    BlockPos pos = new BlockPos(x, y, z);
                    boolean anyWater = false, allWater = true, anyLava = false, allLava = true;
                    double lowWater = Double.POSITIVE_INFINITY, highWater = 0.0D;
                    double lowLava = Double.POSITIVE_INFINITY, highLava = 0.0D;
                    for (int b = 0; b < blocks.size(); b++) {
                        for (int a = 0; a < aboves.size(); a++) {
                            ClientFluidQueries.Sample fluid =
                                    ClientFluidQueries.sample(player, blocks.getInt(b), aboves.getInt(a));
                            boolean inside = (fluid.water() || fluid.lava())
                                    && calculateStatusForFluid(player, box, fluid.height(), pos);
                            double height = getFluidHeightAboveMinY(entityBoundingBoxMinY, fluid.height(), pos);
                            if (inside && fluid.water()) {
                                anyWater = true;
                                lowWater = Math.min(lowWater, height);
                                highWater = Math.max(highWater, height);
                            } else {
                                allWater = false;
                            }
                            if (inside && fluid.lava()) {
                                anyLava = true;
                                lowLava = Math.min(lowLava, height);
                                highLava = Math.max(highLava, height);
                            } else {
                                allLava = false;
                            }
                        }
                    }
                    state.water.addCell(anyWater, allWater, lowWater, highWater);
                    state.lava.addCell(anyLava, allLava, lowLava, highLava);
                }
            }
        }
        return true;
    }

    private static void statesAt(CompensatedWorld world, int x, int y, int z, IntArrayList out) {
        out.clear();
        out.add(world.getBlockStateIdAt(x, y, z));
        IntList replaced = world.recentClientFluidReplacedStates(x, y, z);
        if (replaced == null) return;
        for (int i = 0; i < replaced.size(); i++) {
            if (!out.contains(replaced.getInt(i))) out.add(replaced.getInt(i));
        }
    }

    private void scanClientFluid(
            CultPlayer player, SimpleCollisionBox box, double entityBoundingBoxMinY, FluidState state) {
        // Collisions#hasState visits floor(min) to floor(max) on each axis.
        if (!player.compensatedWorld.mayHaveFluid(
                (int) Math.floor(box.minX),
                (int) Math.floor(box.minY),
                (int) Math.floor(box.minZ),
                (int) Math.floor(box.maxX),
                (int) Math.floor(box.maxY),
                (int) Math.floor(box.maxZ))) return;

        Collisions.hasState(player, box, (block, x, y, z) -> {
            ClientFluidQueries.Sample fluid = ClientFluidQueries.sample(player, block, x, y, z);
            if (!fluid.water() && !fluid.lava()) return false;
            BlockPos pos = new BlockPos(x, y, z);
            boolean isInsideFluid = false;
            if (fluid.water()) {
                double fluidHeight = fluid.height();
                boolean status = calculateStatusForFluid(player, box, fluidHeight, pos);
                state.setInWater(status || state.isInWater());
                if (status) {
                    state.setMaxWaterHeight(Math.max(
                            state.getMaxWaterHeight(),
                            getFluidHeightAboveMinY(entityBoundingBoxMinY, fluidHeight, pos)));
                }
                isInsideFluid = status;
            }

            if (fluid.lava()) {
                double fluidHeight = fluid.height();
                boolean status = calculateStatusForFluid(player, box, fluidHeight, pos);
                state.setInLava(status || state.isInLava());
                if (status) {
                    state.setMaxLavaHeight(Math.max(
                            state.getMaxLavaHeight(),
                            getFluidHeightAboveMinY(entityBoundingBoxMinY, fluidHeight, pos)));
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
        });
    }

    private double getFluidHeightAboveMinY(double entityBoundingBoxMinY, double fluidHeight, BlockPos fluidPos) {
        // MCP-Reborn EntityFluidInteraction#update measures tracker.height from
        // entity.getBoundingBox().minY, not from the deflated fluid-interaction box.
        return Math.max(0.0D, fluidPos.getY() + fluidHeight - entityBoundingBoxMinY);
    }

    /**
     * The pushes LocalPlayer#aiStep's moveTowardsClosestSpace may set this tick, one of which it sets. Before 26.3
     * Level#tickBlockEntities shoves the player after LocalPlayer#sendPosition, so the probes test the box at the
     * reported position moved by those shoves; a shove the engine does not know exactly lies anywhere in the pass's
     * push envelope, and each region of it where every probe tests the same blocks and picks the same side gives
     * one push.
     */
    private List<BlockPush> blockPushes(CultPlayer player, SimulationContext context, PistonPushes pushes) {
        if (player.compensatedEntities.getSelf().inVehicle()) return List.of(BlockPush.NONE);
        if (player.isBedrockMovement()) return List.of(BlockPush.NONE);
        if (player.compensatedWorld.pistons.reportsAfterBlockEntities()) {
            return List.of(blockPushAt(player, context, 0.0D, 0.0D, 0.0D));
        }
        ShulkerBoxShoveResult exactShove = pushes.getShulkerBoxShoveResult();
        if (exactShove != null) {
            Vec3 moved = exactShove.movement();
            return List.of(blockPushAt(player, context, moved.x, moved.y, moved.z));
        }
        SimpleCollisionBox shoves = pushes.getPush().copy();
        if (pushes.isShulkerBoxShoveBeforeTravel()) shoves.union(pushes.getShulkerBoxPush());
        if (shoves.isEmpty()) return List.of(blockPushAt(player, context, 0.0D, 0.0D, 0.0D));
        suffocation = new HashMap<>();
        try {
            return blockPushesWithin(
                    player,
                    context,
                    new SimpleCollisionBox(
                            Math.min(0.0D, shoves.minX),
                            Math.min(0.0D, shoves.minY),
                            Math.min(0.0D, shoves.minZ),
                            Math.max(0.0D, shoves.maxX),
                            Math.max(0.0D, shoves.maxY),
                            Math.max(0.0D, shoves.maxZ)));
        } finally {
            suffocation = null;
        }
    }

    /**
     * Every push the probes give for an offset in {@code envelope}. A probe's push changes only where its point
     * crosses a block or half-block line (the block it tests, and the nearer of its west and east or north and south
     * sides), where its x and z fractions are equal or sum to one (the nearer of its two axes), or where the box's
     * bottom or top crosses a suffocating shape; the offsets tested hit every region and boundary those lines leave.
     */
    private List<BlockPush> blockPushesWithin(CultPlayer player, SimulationContext context, SimpleCollisionBox envelope) {
        Vec3 start = context.getStart();
        float bbWidth = context.getPose().width * context.getScale();
        double half = bbWidth * 0.35D;
        double bottom = player.boundingBox.minY;
        double top = player.boundingBox.maxY;
        int minBlockX = (int) Math.floor(start.x - half + envelope.minX) - 1;
        int maxBlockX = (int) Math.floor(start.x + half + envelope.maxX) + 1;
        int minBlockZ = (int) Math.floor(start.z - half + envelope.minZ) - 1;
        int maxBlockZ = (int) Math.floor(start.z + half + envelope.maxZ) + 1;
        int minBlockY = (int) Math.floor(bottom + envelope.minY);
        int maxBlockY = (int) Math.ceil(top + envelope.maxY);

        Set<Long> suffocatingColumns = new HashSet<>();
        TreeSet<Double> dys = new TreeSet<>(List.of(envelope.minY, envelope.maxY));
        List<SimpleCollisionBox> shapes = new ArrayList<>();
        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int z = minBlockZ; z <= maxBlockZ; z++) {
                for (int y = minBlockY; y < maxBlockY; y++) {
                    if (!Collisions.doesBlockSuffocate(player, x, y, z)) continue;
                    suffocatingColumns.add(column(x, z));
                    shapes.clear();
                    int state = player.compensatedWorld.getBlockStateIdAt(x, y, z);
                    ClientBlockShapes.movement(player, state, x, y, z).downCast(shapes);
                    for (SimpleCollisionBox shape : shapes) {
                        addWithin(dys, shape.maxY - bottom - 1.0E-7, envelope.minY, envelope.maxY);
                        addWithin(dys, shape.minY - top + 1.0E-7, envelope.minY, envelope.maxY);
                    }
                }
            }
        }
        if (suffocatingColumns.isEmpty()) return List.of(BlockPush.NONE);

        Set<Double> xLines = new HashSet<>(), zLines = new HashSet<>();
        Set<Double> differences = new HashSet<>(), sums = new HashSet<>();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                double px = start.x + sx * half, pz = start.z + sz * half;
                if (!anySuffocates(suffocatingColumns, px + envelope.minX, px + envelope.maxX,
                        pz + envelope.minZ, pz + envelope.maxZ)) continue;
                for (long k = (long) Math.ceil(2 * (px + envelope.minX)); k <= Math.floor(2 * (px + envelope.maxX)); k++)
                    xLines.add(k / 2.0D - px);
                for (long k = (long) Math.ceil(2 * (pz + envelope.minZ)); k <= Math.floor(2 * (pz + envelope.maxZ)); k++)
                    zLines.add(k / 2.0D - pz);
                for (long m = (long) Math.ceil(px + envelope.minX - pz - envelope.maxZ);
                        m <= Math.floor(px + envelope.maxX - pz - envelope.minZ); m++)
                    differences.add(m - px + pz);
                for (long m = (long) Math.ceil(px + envelope.minX + pz + envelope.minZ);
                        m <= Math.floor(px + envelope.maxX + pz + envelope.maxZ); m++)
                    sums.add(m - px - pz);
            }
        }

        // Each z between two crossings of the lines crosses every region spanning it.
        TreeSet<Double> crossings = new TreeSet<>(List.of(envelope.minZ, envelope.maxZ));
        for (double z : zLines) addWithin(crossings, z, envelope.minZ, envelope.maxZ);
        List<Double> verticals = new ArrayList<>(xLines);
        verticals.add(envelope.minX);
        verticals.add(envelope.maxX);
        for (double x : verticals) {
            for (double d : differences) addWithin(crossings, x - d, envelope.minZ, envelope.maxZ);
            for (double s : sums) addWithin(crossings, s - x, envelope.minZ, envelope.maxZ);
        }
        for (double d : differences) {
            for (double s : sums) addWithin(crossings, (s - d) / 2.0D, envelope.minZ, envelope.maxZ);
        }

        Set<BlockPush> found = new LinkedHashSet<>();
        double[] ys = withMidpoints(dys);
        for (double dz : withMidpoints(crossings)) {
            TreeSet<Double> xs = new TreeSet<>(List.of(envelope.minX, envelope.maxX));
            for (double x : xLines) addWithin(xs, x, envelope.minX, envelope.maxX);
            for (double d : differences) addWithin(xs, dz + d, envelope.minX, envelope.maxX);
            for (double s : sums) addWithin(xs, s - dz, envelope.minX, envelope.maxX);
            for (double dx : withMidpoints(xs)) {
                for (double dy : ys) found.add(blockPushAt(player, context, dx, dy, dz));
            }
        }
        return List.copyOf(found);
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static boolean anySuffocates(Set<Long> columns, double minX, double maxX, double minZ, double maxZ) {
        for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++) {
            for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) {
                if (columns.contains(column(x, z))) return true;
            }
        }
        return false;
    }

    private static void addWithin(TreeSet<Double> values, double value, double min, double max) {
        if (value >= min && value <= max) values.add(value);
    }

    private static double[] withMidpoints(TreeSet<Double> values) {
        double[] out = new double[values.size() * 2 - 1];
        int i = 0;
        Double last = null;
        for (double value : values) {
            if (last != null) out[i++] = (last + value) / 2.0D;
            out[i++] = value;
            last = value;
        }
        return out;
    }

    private BlockPush blockPushAt(CultPlayer player, SimulationContext context, double dx, double dy, double dz) {
        Vec3 oldPos = context.getStart();
        double x = oldPos.x + dx;
        double z = oldPos.z + dz;
        // TODO: Does 0.03 affect this and if so, how?
        float bbWidth = context.getPose().width * context.getScale();
        BlockPush push = BlockPush.NONE;
        push = this.moveToClosestSpace(player, push, x - bbWidth * 0.35D, z + bbWidth * 0.35D, dy);
        push = this.moveToClosestSpace(player, push, x - bbWidth * 0.35D, z - bbWidth * 0.35D, dy);
        push = this.moveToClosestSpace(player, push, x + bbWidth * 0.35D, z - bbWidth * 0.35D, dy);
        return this.moveToClosestSpace(player, push, x + bbWidth * 0.35D, z + bbWidth * 0.35D, dy);
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
    private boolean suffocatesAt(CultPlayer player, int x, int z, double dy) {
        if (suffocation == null) return testSuffocatesAt(player, x, z, dy);
        return suffocation.computeIfAbsent(new SuffocationProbe(x, z, dy), p -> testSuffocatesAt(player, x, z, dy));
    }

    private record SuffocationProbe(int x, int z, double dy) {}

    private boolean testSuffocatesAt(CultPlayer player, int x, int z, double dy) {
        SimpleCollisionBox axisAlignedBB = extentBox
                .set(x, player.boundingBox.minY + dy, z, x + 1.0, player.boundingBox.maxY + dy, z + 1.0, false)
                .expand(-1.0E-7);
        return Collisions.suffocatesAt(player, axisAlignedBB);
    }

    private BlockPush moveToClosestSpace(CultPlayer player, BlockPush push, double xPos, double zPos, double dy) {
        Direction direction = moveTowardsClosestSpaceModern(player, xPos, zPos, dy);
        return direction == null ? push : push.then(direction);
    }

    private Direction moveTowardsClosestSpaceModern(
            CultPlayer player, double xPosition, double zPosition, double dy) {
        int blockX = (int) Math.floor(xPosition);
        int blockZ = (int) Math.floor(zPosition);

        if (!this.suffocatesAt(player, blockX, blockZ, dy)) {
            return null;
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
                    doesSuffocate = this.suffocatesAt(player, blockX + 1, blockZ, dy);
                    break;
                case WEST:
                    doesSuffocate = this.suffocatesAt(player, blockX - 1, blockZ, dy);
                    break;
                case NORTH:
                    doesSuffocate = this.suffocatesAt(player, blockX, blockZ - 1, dy);
                    break;
                default:
                case SOUTH:
                    doesSuffocate = this.suffocatesAt(player, blockX, blockZ + 1, dy);
                    break;
            }

            if (d6 >= lowestValue || doesSuffocate) continue;
            lowestValue = d6;
            direction = direction2;
        }
        return direction;
    }

    private boolean calculateStatusForFluid(
            CultPlayer player, SimpleCollisionBox box, double fluidHeight, BlockPos fluidPos) {
        SimpleCollisionBox fluidCollision = new SimpleCollisionBox(fluidPos);
        fluidCollision.maxY = fluidCollision.minY + fluidHeight;

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
            SimpleCollisionBox sweep = playerSweep(context, sweptBox, sweepToBox);
            SimpleCollisionBox playerBox = pusherContactBox(sweep, pusherContactBox);
            SimpleCollisionBox entityBounds = entityBoundsBox;
            SimpleCollisionBox entityScratch = entityScratchBox;
            CompensatedEntities entities = context.getEntities();
            PacketEntity rootOfSelf = entities.rootOfSelf();
            PacketEntity selfRidingRoot = entities.selfRidingRoot();
            PacketEntity serverVehicleRoot = entities.serverVehicleRoot();
            long playerTickOrder = entities.getSelf().getClientTickOrder();

            List<PacketEntity> candidates = entities.pushOrCollideCandidates();
            for (int i = 0; i < candidates.size(); i++) {
                PacketEntity entity = candidates.get(i);
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
                    if (pushesPlayer(entity, entityBox, sweep)) pushingTickingAfterPlayer++;
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

    /**
     * The LivingEntity that may lie in the box LivingEntity#checkAutoSpinAttack searches while autoSpinAttackTicks is
     * positive: the player's boxes before and after the travel, the box before it lifted by a release on the ground.
     * Every LivingEntity but a spectator counts, armor stands included.
     */
    private SpinTarget spinTarget(CultPlayer player, SimulationContext context) {
        if (context.getVehicle() != null || player.riptideSpinAttackTicks <= 0) return SpinTarget.NONE;
        Vec3 from = context.getStart();
        SimpleCollisionBox start = GetBoundingBox.getPlayerBoundingBox(spinStartBox, player, from.x, from.y, from.z);
        SimpleCollisionBox sweep = spinSweepBox.set(start).union(context.getToMaximumExtent(spinExtentBox));
        if (RiptideUtil.releasedOnTheGround(player)) {
            for (SimpleCollisionBox lifted : RiptideUtil.liftedBoxes(player, start)) sweep.union(lifted);
        }
        SimpleCollisionBox entityBounds = entityBoundsBox;
        SimpleCollisionBox entityScratch = entityScratchBox;
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

    /**
     * Whether LivingEntity#checkAutoSpinAttack certainly finds a LivingEntity after the travel to the context's end: a
     * living entity at rest, in a box the client has applied, intersects the least box the player swept.
     */
    public static boolean certainlySpinAttacks(CultPlayer player, SimulationContext context) {
        if (context.getVehicle() != null
                || context.getRiptideLevel() != 0
                || context.isTestingPointThree()
                || !player.isTickingReliablyFor(1)) return false;
        int received = player.lastTransactionReceived.get();
        SimpleCollisionBox sweep = context.getFromMinimumExtent().union(context.getToMinimumExtent());
        for (PacketEntity entity : context.getEntities().entityMap.values()) {
            if (!entity.isLivingEntity()
                    || entity instanceof PacketEntityPlayer
                    || entity.type == EntityTypeIds.PUFFERFISH
                    || entity instanceof PacketEntityArmorStand stand && !stand.hasMovementSize()
                    || entity.isDead
                    || !entity.hasCertainBox(received)) continue;
            SimpleCollisionBox entityBox = entity.toBoundingBox(
                    player, entity.getExactMovementCollisionBox().copy());
            if (intersects(entityBox, 0.0D, 0.0D, sweep)) return true;
        }
        return false;
    }

    /** EntitySelector#NO_SPECTATORS, which Level#getEntities applies, for a remote player of known game mode. */
    private static boolean isSpectator(PacketEntity entity, CompensatedEntities entities) {
        return entity instanceof PacketEntityPlayer remote
                && remote.profile != null
                && remote.actionSpectator(entities.clientPlayerModes);
    }

    /**
     * The boxes the player's own box may hold through the context's movement, written into {@code out}, with
     * {@code scratch} distinct from it.
     */
    private static SimpleCollisionBox playerSweep(
            SimulationContext context, SimpleCollisionBox out, SimpleCollisionBox scratch) {
        Vec3 from = context.getStart();
        SimpleCollisionBox playerBox =
                GetBoundingBox.getBoundingBoxFromPosAndSize(out, from.x, from.y, from.z, 0.6f, 1.8f);
        playerBox.union(context.getToMaximumExtent(scratch));
        return playerBox;
    }

    /**
     * The box an entity must touch to push or collide with the player through the movement {@code sweep} holds,
     * written into {@code out}.
     */
    private static SimpleCollisionBox pusherContactBox(SimpleCollisionBox sweep, SimpleCollisionBox out) {
        SimpleCollisionBox playerBox = out.set(sweep);
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
        PredictionScratch scratch = PredictionScratch.of(player);
        SimpleCollisionBox sweep = scratch == null
                ? playerSweep(context, new SimpleCollisionBox(), new SimpleCollisionBox())
                : playerSweep(context, scratch.recountSweep, scratch.recountSweepScratch);
        SimpleCollisionBox playerBox =
                pusherContactBox(sweep, scratch == null ? new SimpleCollisionBox() : scratch.recountContact);
        SimpleCollisionBox entityBounds = scratch == null ? new SimpleCollisionBox() : scratch.recountEntityBounds;
        SimpleCollisionBox entityScratch = scratch == null ? new SimpleCollisionBox() : scratch.recountEntityScratch;
        CompensatedEntities entities = context.getEntities();
        PacketEntity rootOfSelf = entities.rootOfSelf();
        PacketEntity selfRidingRoot = entities.selfRidingRoot();
        PacketEntity serverVehicleRoot = entities.serverVehicleRoot();
        long playerTickOrder = entities.getSelf().getClientTickOrder();
        int tickingAfterPlayer = 0;
        List<PacketEntity> candidates = entities.pushOrCollideCandidates();
        for (int i = 0; i < candidates.size(); i++) {
            PacketEntity entity = candidates.get(i);
            if (!entity.mayPushOrCollide() || entities.clientTickOrder(entity) < playerTickOrder) continue;
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
            if (!mayPushAsUntrackedRider(player, entity)) return null;
            if (pushesFromWhereItMounted(player, entity)) {
                entityBox = entity.toBoundingBox(
                        player, entity.getPossibleMovementCollisionBoxes(entityBounds, entityScratch));
            } else {
                if (!entities.untrackedRiderMayReach(entity, playerBox, entityBounds, entityScratch)) return null;
                entityBox = entities.untrackedRiderPushBox(entity);
                if (entityBox == null) return null;
                entityBox = entity.toBoundingBox(player, entityBox);
            }
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
     * pushes from the seat positionRider last gave the rider, or on the first ride tick after a mount from riding
     * nothing, from where Entity#startRiding found it. Hard-colliding riders stay out, as Bedrock contact skips them.
     */
    private static boolean mayPushAsUntrackedRider(CultPlayer player, PacketEntity entity) {
        return !player.isBedrockMovement()
                && entity.isLivingEntity()
                && !entity.isBoat()
                && !entity.isMinecart()
                && !isBedrockHardCollidingEntity(entity);
    }

    /** {@link PacketEntity#ridesFirstTickFromItsMount} this tick, for the client versions whose order was read. */
    private static boolean pushesFromWhereItMounted(CultPlayer player, PacketEntity rider) {
        ClientVersion version = player.getClientVersion();
        return (version == ClientVersion.V_1_21_11 || version.isNewerThanOrEquals(ClientVersion.V_26_2))
                && rider.ridesFirstTickFromItsMount(player.packetStateData.acceptedClientTick);
    }

    private static boolean isBedrockHardCollidingEntity(PacketEntity entity) {
        return entity.type == EntityTypeIds.STRIDER || entity.isHappyGhast();
    }

    private StuckEdgeData couldBeOnStuckEdge(
            CultPlayer player, Vec3 target, boolean lastOnGround, double fallDistance, SimpleCollisionBox playerBB) {
        if (!player.isFlying
                && player.isSneaking
                && Collisions.isAboveGround(player, lastOnGround, fallDistance, playerBB)
                && !player.compensatedEntities.getSelf().inVehicle()) {
            // 16 - Magic number to stop people from crashing the server
            // 0.05 - Mojang's magic value that they use to calculate precision of sneaking
            // They move the position back by 0.05 blocks repeatedly until they are above ground
            // So by going forwards 0.05 blocks, we can determine if the player was influenced by this
            double posX = Math.max(0.05, CultMath.clamp(target.x, -16, 16) + 0.05);
            double posZ = Math.max(0.05, CultMath.clamp(target.z, -16, 16) + 0.05);
            double negX = Math.min(-0.05, CultMath.clamp(target.x, -16, 16) - 0.05);
            double negZ = Math.min(-0.05, CultMath.clamp(target.z, -16, 16) - 0.05);

            Vector3dm NE = Collisions.maybeBackOffFromEdge(
                    new Vector3dm(posX, 0, negZ), player, lastOnGround, fallDistance, playerBB);
            Vector3dm NW = Collisions.maybeBackOffFromEdge(
                    new Vector3dm(negX, 0, negZ), player, lastOnGround, fallDistance, playerBB);
            Vector3dm SE = Collisions.maybeBackOffFromEdge(
                    new Vector3dm(posX, 0, posZ), player, lastOnGround, fallDistance, playerBB);
            Vector3dm SW = Collisions.maybeBackOffFromEdge(
                    new Vector3dm(negX, 0, posZ), player, lastOnGround, fallDistance, playerBB);

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

        bubblePlayer = player;
        bubblePushUpwards = bubblePushDownwards = bubbleAirUpwards = bubbleAirDownwards = 0;
        bubbleVisited = null;

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
                accumulateBubbleColumns(player, context, stepX, stepY, stepZ, nextX, nextY, nextZ);
                stepX = nextX;
                stepY = nextY;
                stepZ = nextZ;
            }
        } else {
            accumulateBubbleColumns(player, context, from.x, from.y, from.z, to.x, to.y, to.z);
        }

        if ((bubbleAirUpwards | bubblePushUpwards | bubbleAirDownwards | bubblePushDownwards) == 0) {
            return NO_BUBBLE_COLUMN;
        }
        return new BubbleColumnData(bubbleAirUpwards, bubblePushUpwards, bubbleAirDownwards, bubblePushDownwards);
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
            double toZ) {
        SimpleCollisionBox fromBox = bubbleColumnBoxAt(sweepFromBox, player, context, fromX, fromY, fromZ);
        SimpleCollisionBox toBox = bubbleColumnBoxAt(sweepToBox, player, context, toX, toY, toZ);
        SimpleCollisionBox sweptBox = this.sweptBox.set(fromBox).union(toBox);
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        bubbleFromBox = fromBox;
        bubbleToBox = toBox;
        bubbleLongMove = dx * dx + dy * dy + dz * dz > 0.9999900000002526D * 0.9999900000002526D;

        Collisions.hasState(player, sweptBox, IS_BUBBLE_COLUMN, bubbleColumnVisitor);
    }

    private boolean visitBubbleColumn(int state, BlockPos pos) {
        if (!BlockIds.is(state, BlockIds.BUBBLE_COLUMN)) {
            return false;
        }

        boolean preciseIntersection = bubbleLongMove || intersectsBlock(bubbleToBox, 1.0E-5F, pos);
        boolean pathTouches = preciseIntersection
                || Collisions.sweptFullBlockEntityInside(
                        bubbleFromBox, bubbleToBox, pos.getX(), pos.getY(), pos.getZ());
        if (!pathTouches || !preciseIntersection) {
            return false;
        }
        if (bubbleVisited == null) bubbleVisited = new LongOpenHashSet();
        if (!bubbleVisited.add(pos.asLong())) {
            return false;
        }

        CultPlayer player = bubblePlayer;
        int blockAbove = player.compensatedWorld.getBlockStateIdAt(pos.getX(), pos.getY() + 1, pos.getZ());
        boolean isDown = BlockProps.DRAG.has(state) && BlockProps.DRAG.booleanValue(state);
        boolean airAbove = player.compensatedWorld
                        .geometry()
                        .collision(blockAbove, pos)
                        .isEmpty()
                && ac.cult.cultac.utils.nmsutil.ClientFluidQueries.modelFluid(blockAbove)
                        .isEmpty();
        if (isDown) {
            if (airAbove) bubbleAirDownwards++;
            else bubblePushDownwards++;
        } else {
            if (airAbove) bubbleAirUpwards++;
            else bubblePushUpwards++;
        }
        return false;
    }

    static SimpleCollisionBox bubbleColumnBoxAt(
            SimpleCollisionBox out, CultPlayer player, SimulationContext context, double x, double y, double z) {
        if (context.getVehicle() == null) {
            if (!player.isBedrockMovement()) {
                // These counts bound possible callbacks; the estimated pose cannot bound all client boxes.
                return GetBoundingBox.getBoundingBoxFromPosAndSize(
                        out, x, y, z, context.getMaxWidth(), context.getMaxHeight());
            }
            return GetBoundingBox.getBoundingBoxFromPosAndSize(
                    out,
                    x,
                    y,
                    z,
                    context.getPose().width * context.getScale(),
                    context.getPose().height * context.getScale());
        }

        return GetBoundingBox.getBoundingBoxFromPosAndSize(out, x, y, z, context.getMaxWidth(), context.getMaxHeight());
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
        IntPredicate honey = block -> BlockIds.is(block, BlockIds.HONEY_BLOCK);
        Collisions.StatePredicate sliding =
                (block, pos) -> Collisions.isSlidingDown(player, pos.getX(), pos.getY(), pos.getZ(), context.getEnd());
        SimpleCollisionBox smallHoneySearch =
                context.getFromMinimumExtent(sweepFromBox).union(context.getToMinimumExtent(sweepToBox));
        boolean hasSmall = Collisions.hasState(player, smallHoneySearch, honey, sliding);
        SimpleCollisionBox largeHoneySearch =
                context.getFromMaximumExtent(sweepFromBox).union(context.getToMaximumExtent(sweepToBox));
        boolean hasLarge = Collisions.hasState(player, largeHoneySearch, honey, sliding);
        return DesyncStatus.fromBooleans(hasSmall, hasLarge);
    }

    public StuckSpeedData calculateStuckSpeed(CultPlayer player, SimulationContext context, PistonPushes push) {
        // Preserve the existing extent approximation. A vehicle uses its full body;
        // player minimum/maximum extents account for pose and movement-threshold uncertainty.
        boolean inVehicle = context.getVehicle() != null;
        SimpleCollisionBox primaryFrom =
                inVehicle ? context.getFromMaximumExtent(sweepFromBox) : context.getFromMinimumExtent(sweepFromBox);
        SimpleCollisionBox primaryTo =
                inVehicle ? context.getToMaximumExtent(sweepToBox) : context.getToMinimumExtent(sweepToBox);
        boolean powderSnowCanApply = powderSnowCanApplyToRootEntity(player, context);
        Vec3 required = resolveStuckSpeedForExtent(player, context, primaryFrom, primaryTo, powderSnowCanApply);
        Vec3 optional = inVehicle
                ? null
                : resolveStuckSpeedForExtent(
                        player,
                        context,
                        context.getFromMaximumExtent(sweepFromBox),
                        context.getToMaximumExtent(sweepToBox),
                        powderSnowCanApply);

        boolean hasPistonPush = push != null
                && push.getPistonPush() != null
                && !push.getPistonPush().isEmpty();
        if (hasPistonPush) {
            // MCP-Reborn LocalPlayer#tick sends movement during ClientLevel#tickEntities,
            // before Minecraft#tick runs piston block entities. A later
            // PistonMovingBlockEntity#moveEntityByPiston calls
            // Entity#move(MoverType.PISTON), which clears Entity#stuckSpeedMultiplier
            // without applying it to the piston shove, so piston-overlapped stuck
            // speed is optional rather than required for the next packet.
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

    private SimpleCollisionBox findFishingRodPullsBox(CultPlayer player) {
        if (player.compensatedEntities.fishingRodPulls.isEmpty()) return null;

        SimpleCollisionBox fishingRodPulls = new SimpleCollisionBox();
        for (SimpleCollisionBox pullTo : player.compensatedEntities.fishingRodPulls) {
            Vector3dm pullOrigin = new Vector3dm(player.lastX, player.lastY + 0.8 * 1.8, player.lastZ);

            Vector3dm diff = new Vector3dm(pullTo.minX, pullTo.minY, pullTo.minZ)
                    .subtract(pullOrigin)
                    .multiply(0.1);
            fishingRodPulls.minX = Math.min(0, diff.getX());
            fishingRodPulls.minY = Math.min(0, diff.getY());
            fishingRodPulls.minZ = Math.min(0, diff.getZ());

            diff = new Vector3dm(pullTo.maxX, pullTo.maxY, pullTo.maxZ)
                    .subtract(pullOrigin)
                    .multiply(0.1);
            fishingRodPulls.maxX = Math.max(0, diff.getX());
            fishingRodPulls.maxY = Math.max(0, diff.getY());
            fishingRodPulls.maxZ = Math.max(0, diff.getZ());
        }
        return fishingRodPulls;
    }

    /**
     * Entity#checkSupportingBlock. {@code lastSupport} is the supporting block the move started with, whose
     * onGroundNoBlocks stops the search behind the movement; null when there is none.
     */
    public MainSupportingBlockData findMainSupportingBlockPos(
            CultPlayer player,
            @Nullable MainSupportingBlockData lastSupport,
            double lastMovementX,
            double lastMovementZ,
            SimpleCollisionBox maxPose,
            boolean isOnGround) {
        if (!isOnGround) {
            return new MainSupportingBlockData(null, false);
        }

        double minX = maxPose.minX, minY = maxPose.minY - 1.0E-6D, minZ = maxPose.minZ;
        double maxX = maxPose.maxX, maxY = maxPose.minY, maxZ = maxPose.maxZ;

        BlockPos supportingBlock = findSupportingBlock(player, minX, minY, minZ, maxX, maxY, maxZ);
        if (supportingBlock == null && lastSupport != null && !lastSupport.lastOnGroundAndNoBlock()) {
            double offsetX = -lastMovementX, offsetZ = -lastMovementZ;
            return new MainSupportingBlockData(
                    findSupportingBlock(
                            player,
                            minX + offsetX,
                            minY + 0.0D,
                            minZ + offsetZ,
                            maxX + offsetX,
                            maxY + 0.0D,
                            maxZ + offsetZ),
                    true);
        }
        return new MainSupportingBlockData(supportingBlock, true);
    }

    /** The supporting block Entity#checkSupportingBlock picks inside the search box with these bounds, or null. */
    private BlockPos findSupportingBlock(
            CultPlayer player, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        SupportScan scan = supportScan.inUse ? new SupportScan() : supportScan;
        try {
            scan.start(player, minX, minY, minZ, maxX, maxY, maxZ);
            Collisions.hasState(player, scan.cells, scan);
            return scan.best;
        } finally {
            scan.clear();
        }
    }

    /** The per-block test of {@link #findSupportingBlock}: never matches, it keeps the closest supporting block. */
    private final class SupportScan implements Collisions.StateAtPredicate {
        // Written by start for each search, which this scan runs one at a time.
        private final SimpleCollisionBox searchBox = new SimpleCollisionBox();
        private final SimpleCollisionBox cells = new SimpleCollisionBox();
        private boolean inUse;
        private CultPlayer player;
        private double playerX, playerY, playerZ;
        private BlockPos best;
        private double bestDistance;

        private SupportScan start(
                CultPlayer player, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            inUse = true;
            this.player = player;
            searchBox.set(minX, minY, minZ, maxX, maxY, maxZ);
            // The cells BlockCollisions visits: one layer past the search box on each axis.
            cells.set(
                    Math.floor(minX - 1.0E-7) - 1,
                    Math.floor(minY - 1.0E-7) - 1,
                    Math.floor(minZ - 1.0E-7) - 1,
                    Math.floor(maxX + 1.0E-7) + 1,
                    Math.floor(maxY + 1.0E-7) + 1,
                    Math.floor(maxZ + 1.0E-7) + 1);
            playerX = player.x;
            playerY = player.y;
            playerZ = player.z;
            best = null;
            bestDistance = Double.MAX_VALUE;
            return this;
        }

        private void clear() {
            player = null;
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

            CollisionBox collision = ClientBlockShapes.movement(player, block, x, y, z);
            if (!collision.isIntersected(searchBox)) return false;

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
