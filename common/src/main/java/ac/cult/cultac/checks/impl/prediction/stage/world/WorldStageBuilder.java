package ac.cult.cultac.checks.impl.prediction.stage.world;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPush;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.*;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
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
    private static final Direction[] HORIZONTAL_ESCAPES = {
        Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH
    };
    private static final List<Direction.Axis> Y_Z_X = List.of(Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X);
    private static final List<Direction.Axis> Y_X_Z = List.of(Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z);
    /** Never written: StuckEdgeData has no mutating caller. */
    private static final StuckEdgeData NOT_ON_EDGE = new StuckEdgeData(false, false, false, false, false);

    private final SupportScan supportScan = new SupportScan();
    private int collidingTickingBeforePlayer;

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
        SimpleCollisionBox minFluid = simulationContext.getFromMinimumExtent().expand(-expandAmount);
        SimpleCollisionBox maxFluid = simulationContext.getFromMaximumExtent().expand(-expandAmount);

        double minFluidReferenceY = simulationContext.getFromMinimumExtent().minY;
        double maxFluidReferenceY = simulationContext.getVehicle() != null
                ? minFluidReferenceY
                : simulationContext.getFromMaximumExtent().minY;

        FluidState minFluidState = getFluidState(player, minFluid, minFluidReferenceY);
        // Vehicles have the same thing as the fluid state as before.
        FluidState maxFluidState = simulationContext.getVehicle() != null
                ? minFluidState
                : getFluidState(player, maxFluid, maxFluidReferenceY);

        DesyncStatus isInWater = DesyncStatus.fromBooleans(minFluidState.isInWater(), maxFluidState.isInWater());
        DesyncStatus isInLava = DesyncStatus.fromBooleans(minFluidState.isInLava(), maxFluidState.isInLava());
        DesyncStatus touchingLava = isInLava;
        DesyncStatus isInFlowingFluid =
                DesyncStatus.fromBooleans(minFluidState.isInFlowingFluid(), maxFluidState.isInFlowingFluid());

        if (minFluidState.isWaterTimingUncertain() || maxFluidState.isWaterTimingUncertain()) {
            isInWater = DesyncStatus.UNKNOWN;
            isInFlowingFluid = DesyncStatus.UNKNOWN;
        }
        if (minFluidState.isLavaTimingUncertain() || maxFluidState.isLavaTimingUncertain()) {
            isInLava = DesyncStatus.UNKNOWN;
            touchingLava = DesyncStatus.UNKNOWN;
            isInFlowingFluid = DesyncStatus.UNKNOWN;
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

        int toState = player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(to.x, to.y, to.z));
        if (BlockIds.is(toState, BlockIds.POWDER_SNOW)
                && player.getInventory().getBoots().getItem()
                        == ac.cult.cultac.utils.inventory.ItemTypes.LEATHER_BOOTS) {
            climbing = DesyncStatus.UNKNOWN;
        }

        // Pigs and striders are the only vehicle that can climb
        if (simulationContext.getVehicle() != null
                && !(simulationContext.getVehicle() instanceof PacketEntityStrider
                        || simulationContext.getVehicle().type == EntityTypeIds.PIG)) {
            climbingAtStart = DesyncStatus.FALSE;
            climbing = DesyncStatus.FALSE;
        }

        MainSupportingBlockData mainSupportingBlockData = findMainSupportingBlockPos(
                player,
                lastPrediction == null ? null : lastPrediction.getSimulationContext(),
                deltaX,
                deltaZ,
                simulationContext.getToMaxPose(),
                player.onGround);

        boolean isSuffocating = mightBeSuffocating(player, simulationContext);
        int numColliding = getNumEntitiesCollidingWith(simulationContext);
        CarriedPush carriedPush = player.checkManager.getSimulationProcessor().getCarriedPush();
        int numCarriedPushing = pushersFromLastTick(simulationContext, lastPrediction, !carriedPush.isZero());
        int numPushing = collidingTickingBeforePlayer + numCarriedPushing;
        int onBlock = BlockProperties.getOnPos(player, mainSupportingBlockData, to);

        SimpleCollisionBox minExtent = simulationContext.getFromMinimumExtent();
        StuckEdgeData stuckEdgeData = couldBeOnStuckEdge(
                player, simulationContext.getTarget(), lastOnGround.determineOptimistically(), minExtent);
        // If the player isn't in water, then they aren't in a bubble column
        final BubbleColumnData bubble = computeBubbleColumns(player, to, simulationContext);

        PistonPushes push = player.compensatedWorld.tickPlayerInPistonPushingArea(simulationContext);
        // Java 1.21.2+ sends tick-end after the block-entity pass. Its piston,
        // shulker and recent world state already advance in CheckManager at that
        // boundary, including idle ticks; prediction must not advance them again.
        StuckSpeedData stuckSpeedData = calculateStuckSpeed(player, simulationContext, push);

        // Eye-fluid state is sampled at the movement start position.
        player.wasEyeInWater = ClientFluidQueries.eyesInWater(
                player, simulationContext.getFromMinimumExtent(), from, from.y + player.getEyeHeight());
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

        if (!push.getPush().isEmpty() || player.packetStateData.riptideLevel > 0) {
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
                isSuffocating,
                canJump,
                canFluidHop,
                canFloatWhileRidden,
                couldFloatWhileRidden,
                climbingAtStart,
                climbing,
                numColliding,
                numColliding - collidingTickingBeforePlayer,
                numPushing,
                numCarriedPushing,
                carriedPush,
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
                lastOnGround);
    }

    private DesyncStatus sampleClimbingAtStart(CultPlayer player, SimulationContext context) {
        Vec3 position = context.getStart();
        DesyncStatus climbingAtStart = DesyncStatus.fromBoolean(
                Collisions.onClimbable(player, position.x, position.y, position.z, context.isGliding()));
        return climbingAtStart;
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
    @AllArgsConstructor
    private static class FluidState {
        boolean inWater;
        boolean inLava;
        boolean inFlowingFluid;
        double maxWaterHeight;
        double maxLavaHeight;
        boolean waterTimingUncertain;
        boolean lavaTimingUncertain;

        private boolean canGroundJumpFromFluid() {
            return (inWater && maxWaterHeight > 0.0D && maxWaterHeight <= 0.4D)
                    || (inLava && maxLavaHeight > 0.0D && maxLavaHeight <= 0.4D);
        }
    }

    private FluidState getFluidState(CultPlayer player, SimpleCollisionBox box, double entityBoundingBoxMinY) {
        FluidState state = new FluidState(false, false, false, 0.0D, 0.0D, false, false);
        state.setWaterTimingUncertain(player.compensatedWorld.hasRecentClientWaterChanges(box));
        state.setLavaTimingUncertain(player.compensatedWorld.hasRecentClientLavaChanges(box));

        SimpleCollisionBox lavaCollision = box.copy();

        Collisions.hasState(player, box, (block, pos) -> {
            boolean isInsideFluid = false;
            ClientFluidQueries.Sample fluid = ClientFluidQueries.sample(player, block, pos);
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
                // 1.14 doesn't care about actual lava collision box
                boolean status = calculateStatusForFluid(player, lavaCollision, fluidHeight, pos);
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

        return state;
    }

    private double getFluidHeightAboveMinY(double entityBoundingBoxMinY, double fluidHeight, BlockPos fluidPos) {
        // MCP-Reborn EntityFluidInteraction#update measures tracker.height from
        // entity.getBoundingBox().minY, not from the deflated fluid-interaction box.
        return Math.max(0.0D, fluidPos.getY() + fluidHeight - entityBoundingBoxMinY);
    }

    private boolean mightBeSuffocating(CultPlayer player, SimulationContext context) {
        if (player.compensatedEntities.getSelf().inVehicle()) return false;
        Vec3 oldPos = context.getStart();
        if (player.isBedrockMovement()) return false;
        // TODO: Does 0.03 affect this and if so, how?
        float bbWidth = 0.6f;
        return this.moveToClosestSpace(player, oldPos.x - bbWidth * 0.35D, oldPos.y, oldPos.z + bbWidth * 0.35D)
                || this.moveToClosestSpace(player, oldPos.x - bbWidth * 0.35D, oldPos.y, oldPos.z - bbWidth * 0.35D)
                || this.moveToClosestSpace(player, oldPos.x + bbWidth * 0.35D, oldPos.y, oldPos.z - bbWidth * 0.35D)
                || this.moveToClosestSpace(player, oldPos.x + bbWidth * 0.35D, oldPos.y, oldPos.z + bbWidth * 0.35D);
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
    private boolean suffocatesAt(CultPlayer player, int x, int z) {
        SimpleCollisionBox axisAlignedBB = new SimpleCollisionBox(
                        x, player.boundingBox.minY, z, x + 1.0, player.boundingBox.maxY, z + 1.0, false)
                .expand(-1.0E-7);
        return Collisions.suffocatesAt(player, axisAlignedBB);
    }

    private boolean moveToClosestSpace(CultPlayer player, double xPos, double yPos, double zPos) {
        return moveTowardsClosestSpaceModern(player, xPos, zPos);
    }

    private boolean moveTowardsClosestSpaceModern(CultPlayer player, double xPosition, double zPosition) {
        int blockX = (int) Math.floor(xPosition);
        int blockZ = (int) Math.floor(zPosition);

        if (!this.suffocatesAt(player, blockX, blockZ)) {
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
                    doesSuffocate = this.suffocatesAt(player, blockX + 1, blockZ);
                    break;
                case WEST:
                    doesSuffocate = this.suffocatesAt(player, blockX - 1, blockZ);
                    break;
                case NORTH:
                    doesSuffocate = this.suffocatesAt(player, blockX, blockZ - 1);
                    break;
                default:
                case SOUTH:
                    doesSuffocate = this.suffocatesAt(player, blockX, blockZ + 1);
                    break;
            }

            if (d6 >= lowestValue || doesSuffocate) continue;
            lowestValue = d6;
            direction = direction2;
        }
        return direction != null;
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

    private int getNumEntitiesCollidingWith(SimulationContext context) {
        int numColliding = 0;
        collidingTickingBeforePlayer = 0;
        List<SimpleCollisionBox> entityCollisionBoxes = null;
        List<HardCollidingEntityCollision> hardCollidingEntityCollisions = null;

        // Players in vehicles do not have collisions
        if (context.getVehicle() == null) {
            SimpleCollisionBox playerBox = pusherContactBox(context);
            SimpleCollisionBox entityBounds = new SimpleCollisionBox();
            SimpleCollisionBox entityScratch = new SimpleCollisionBox();
            CompensatedEntities entities = context.getEntities();
            long playerTickOrder = entities.getSelf().getClientTickOrder();

            for (PacketEntity entity : entities.entityMap.values()) {
                SimpleCollisionBox entityBox =
                        collidingPusherBox(entities, entity, playerBox, entityBounds, entityScratch);
                if (entityBox == null) continue;
                numColliding++;
                if (entities.clientTickOrder(entity) < playerTickOrder) collidingTickingBeforePlayer++;
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

    /** The box an entity must touch to push or collide with the player through the context's movement. */
    private static SimpleCollisionBox pusherContactBox(SimulationContext context) {
        Vec3 from = context.getStart();
        SimpleCollisionBox playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(from.x, from.y, from.z, 0.6f, 1.8f);
        playerBox.union(context.getToMaximumExtent());
        playerBox.expand(0.2); // bit more because why not
        return playerBox;
    }

    /**
     * Recounts {@code prediction}'s colliding entities that tick after the player once the client tick has ended.
     * Each one's LivingEntity#pushEntities runs after its own move in ClientLevel#tickEntities, so it pushes the
     * player from where its interpolation step that tick left it, not from where it was when the player moved.
     */
    public static void recountCollidingTickingAfterPlayer(PredictionResult prediction) {
        SimulationContext context = prediction.getSimulationContext();
        if (context == null || context.getVehicle() != null) return;
        SimpleCollisionBox playerBox = pusherContactBox(context);
        SimpleCollisionBox entityBounds = new SimpleCollisionBox();
        SimpleCollisionBox entityScratch = new SimpleCollisionBox();
        CompensatedEntities entities = context.getEntities();
        long playerTickOrder = entities.getSelf().getClientTickOrder();
        int tickingAfterPlayer = 0;
        for (PacketEntity entity : entities.entityMap.values()) {
            if (!mayPushOrCollide(entity) || entities.clientTickOrder(entity) < playerTickOrder) continue;
            if (collidingPusherBox(entities, entity, playerBox, entityBounds, entityScratch) != null)
                tickingAfterPlayer++;
        }
        context.getWorldData().setNumCollidingTickingAfterPlayer(tickingAfterPlayer);
    }

    /**
     * Adds to {@code pushes} the x/z range of the Entity#push impulses a player standing at the given position takes
     * through one client tick. While LocalPlayer#tick waits for the level to load, ClientLevel#tickEntities still runs
     * every other entity's pushEntities.
     */
    public void addPushesOnStandingPlayer(CultPlayer player, double x, double y, double z, SimpleCollisionBox pushes) {
        if (player.compensatedEntities.getSelf().inVehicle()) return;
        SimpleCollisionBox playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(x, y, z, 0.6f, 1.8f);
        SimpleCollisionBox entityBounds = new SimpleCollisionBox();
        SimpleCollisionBox entityScratch = new SimpleCollisionBox();
        CompensatedEntities entities = player.compensatedEntities;
        for (PacketEntity entity : entities.entityMap.values()) {
            SimpleCollisionBox box = collidingPusherBox(entities, entity, playerBox, entityBounds, entityScratch);
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
            CompensatedEntities entities,
            PacketEntity entity,
            SimpleCollisionBox playerBox,
            SimpleCollisionBox entityBounds,
            SimpleCollisionBox entityScratch) {
        if (!mayPushOrCollide(entity)) return null;
        if (entities.sharesRootVehicleWithSelf(entity) || entities.isUntrackedRider(entity)) return null;

        // MCP-Reborn push/collision checks use the entity's concrete
        // bounding box, not the reach-expanded uncertainty box.
        SimpleCollisionBox entityBox = entity.getPossibleMovementCollisionBoxes(entityBounds, entityScratch);
        return playerBox.isCollided(entityBox) ? entityBox : null;
    }

    /** The type test {@link #collidingPusherBox} applies before any position or riding test. */
    private static boolean mayPushOrCollide(PacketEntity entity) {
        boolean hardCollidingEntity = entity.isBoat() || entity.isMinecart() || isBedrockHardCollidingEntity(entity);
        // Players can push living entities. Some entities also provide
        // hard movement collision boxes that Bedrock consumes as geometry.
        // The one exemption to a living entity is an armor stand (matters due to plugins sending them)
        return !(!entity.isLivingEntity() && !hardCollidingEntity || entity.type == EntityTypeIds.ARMOR_STAND);
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

    private MainSupportingBlockData findMainSupportingBlockPos(
            CultPlayer player,
            SimulationContext lastContext,
            double lastMovementX,
            double lastMovementZ,
            SimpleCollisionBox maxPose,
            boolean isOnGround) {
        if (!isOnGround) {
            return new MainSupportingBlockData(null, false);
        }

        SimpleCollisionBox slightlyBelowPlayer = new SimpleCollisionBox(
                maxPose.minX, maxPose.minY - 1.0E-6D, maxPose.minZ, maxPose.maxX, maxPose.minY, maxPose.maxZ);

        BlockPos supportingBlock = findSupportingBlock(player, slightlyBelowPlayer);
        if (supportingBlock == null
                && (lastContext != null
                        && !lastContext
                                .getWorldData()
                                .getMainSupportingBlockPos()
                                .lastOnGroundAndNoBlock())) {
            SimpleCollisionBox aabb2 = slightlyBelowPlayer.offset(-lastMovementX, 0.0D, -lastMovementZ);
            return new MainSupportingBlockData(findSupportingBlock(player, aabb2), true);
        }
        return new MainSupportingBlockData(supportingBlock, true);
    }

    /** The supporting block Entity#checkSupportingBlock picks inside {@code searchBox}, or null. */
    private BlockPos findSupportingBlock(CultPlayer player, SimpleCollisionBox searchBox) {
        SupportScan scan = supportScan.inUse ? new SupportScan() : supportScan;
        try {
            Collisions.hasState(player, searchBox, scan.start(player, searchBox));
            return scan.best;
        } finally {
            scan.clear();
        }
    }

    /** The per-block test of {@link #findSupportingBlock}: never matches, it keeps the closest supporting block. */
    private final class SupportScan implements Collisions.StatePredicate {
        private boolean inUse;
        private CultPlayer player;
        private SimpleCollisionBox searchBox;
        private double playerX, playerY, playerZ;
        private BlockPos best;
        private double bestDistance;

        private SupportScan start(CultPlayer player, SimpleCollisionBox searchBox) {
            inUse = true;
            this.player = player;
            this.searchBox = searchBox;
            playerX = player.x;
            playerY = player.y;
            playerZ = player.z;
            best = null;
            bestDistance = Double.MAX_VALUE;
            return this;
        }

        private void clear() {
            player = null;
            searchBox = null;
            best = null;
            inUse = false;
        }

        @Override
        public boolean test(int block, BlockPos blockPos) {
            CollisionBox collision =
                    ClientBlockShapes.movement(player, block, blockPos.getX(), blockPos.getY(), blockPos.getZ());
            if (!collision.isIntersected(searchBox)) return false;

            double distanceX = blockPos.getX() + 0.5 - playerX;
            double distanceY = blockPos.getY() + 0.5 - playerY;
            double distanceZ = blockPos.getZ() + 0.5 - playerZ;
            double distance = distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ;

            if (distance < bestDistance
                    || distance == bestDistance && (best == null || firstHasPriorityOverSecond(blockPos, best))) {
                best = blockPos;
                bestDistance = distance;
            }

            return false;
        }
    }

    private boolean firstHasPriorityOverSecond(BlockPos first, BlockPos second) {
        // Order of loop is X, Y, and Z
        // We prioritize lowest Y axis, then lowest X axis, then lowest Z axis
        // Ties among the X and Z positions are broken by the order of looping being X
        //
        // X O O
        // 0 X 0
        // 0 0 X
        // If the three blocks were this, the lowest right would win because of iteration order
        //
        // X 0 0
        // 0 0 X
        // But the upper left would win here because of prioritizing negative X and negative Z
        if (first.getY() < second.getY()) return true;

        double sumX = second.getX() - first.getX();
        double sumY = second.getZ() - first.getZ();

        double horizontalSumTotal = sumX + sumY;
        if (horizontalSumTotal == 0) {
            // If X is farther in the X direction, then it was found later and therefore won't override
            return sumX < 0;
        }

        // Otherwise, lower X and lower Z have priority
        return horizontalSumTotal < 0;
    }
}
