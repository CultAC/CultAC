package ac.grim.grimac.checks.impl.prediction.stage.world;


import ac.grim.grimac.checks.impl.prediction.DesyncStatus;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.*;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntityHorse;
import ac.grim.grimac.utils.data.packetentity.PacketEntityStrider;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.nmsutil.BlockProperties;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import ac.grim.grimac.utils.nmsutil.FluidTypeFlowing;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.potion.PotionEffectType;
import com.google.common.util.concurrent.AtomicDouble;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class WorldStageBuilder {
    public WorldData generateWorldData(GrimPlayer player, SimulationContext simulationContext, PredictionResult lastPrediction, DesyncStatus lastOnGround) {
        Vec3 to = simulationContext.getEnd();
        Vec3 from = simulationContext.getStart();
        Vec3 delta = to.subtract(from);

        // -0.001 is mojang's magic value, not ours.
        double expandAmount = 0.001;
        SimpleCollisionBox minFluid = simulationContext.getFromMinimumExtent().expand(-expandAmount);
        SimpleCollisionBox maxFluid = simulationContext.getFromMaximumExtent().expand(-expandAmount);

        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_12_2)) {
            // so maxY becomes less than minY because mojang is fucking stupid.
            // But it gets sorted to the right values
            maxFluid.expand(0, -0.4, 0);
            minFluid.expand(0, -0.4, 0);
        }

        double minFluidReferenceY = simulationContext.getFromMinimumExtent().minY;
        double maxFluidReferenceY = simulationContext.getVehicle() != null
                ? minFluidReferenceY
                : simulationContext.getFromMaximumExtent().minY;

        FluidState minFluidState = getFluidState(player, minFluid, minFluidReferenceY);
        // Vehicles have the same thing as the fluid state as before.
        FluidState maxFluidState = simulationContext.getVehicle() != null ? minFluidState : getFluidState(player, maxFluid, maxFluidReferenceY);

        DesyncStatus isInWater = DesyncStatus.fromBooleans(minFluidState.isInWater(), maxFluidState.isInWater());
        DesyncStatus isInLava = DesyncStatus.fromBooleans(minFluidState.isInLava(), maxFluidState.isInLava());
        DesyncStatus touchingLava = isInLava;
        DesyncStatus isInFlowingFluid = DesyncStatus.fromBooleans(minFluidState.isInFlowingFluid(), maxFluidState.isInFlowingFluid());

        if (minFluidState.isWaterTimingUncertain() || maxFluidState.isWaterTimingUncertain()) {
            isInWater = DesyncStatus.UNKNOWN;
            isInFlowingFluid = DesyncStatus.UNKNOWN;
        }
        if (minFluidState.isLavaTimingUncertain() || maxFluidState.isLavaTimingUncertain()) {
            isInLava = DesyncStatus.UNKNOWN;
            touchingLava = DesyncStatus.UNKNOWN;
            isInFlowingFluid = DesyncStatus.UNKNOWN;
        }

        // 1.14-1.15 use end of tick logic to check if in lava, so just hack around it since no one elses these client versions
        DesyncStatus weirdFourteenFifteenLava = DesyncStatus.FALSE;
        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_15_2) && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14)) {
            weirdFourteenFifteenLava = DesyncStatus.fromBoolean(Collisions.hasMaterial(player, simulationContext.getToMaximumExtent(), data -> data.getFirst().getMaterial() == Material.LAVA));
            // too lazy to do this correctly, this will do. (solves issues like between tick stuff... mostly)
            if (lastPrediction != null) {
                isInLava = isInLava.addBoolean(lastPrediction.getSimulationContext().getWorldData().weirdFourteenFifteenLava);
            }
        }

        if (player.packetStateData.riptideLevel > 0) {
            isInWater = DesyncStatus.UNKNOWN;
            isInLava = DesyncStatus.UNKNOWN;
            isInFlowingFluid = DesyncStatus.UNKNOWN;
        }

        boolean canFluidHop = false;

        if (lastPrediction != null) {
            WorldData oldWorldData = lastPrediction.getSimulationContext().getWorldData();
            boolean wasInWaterOrLava = oldWorldData.getInWater().determineOptimistically() || oldWorldData.getInLava().determineOptimistically();
            boolean didCollide = lastPrediction.getCollideAxisData().couldCollideHorizontally();
            canFluidHop = wasInWaterOrLava && didCollide && (simulationContext.getVehicle() == null || !ac.grim.grimac.utils.nmsutil.EntityTypeUtil.isBoat(simulationContext.getVehicle().type));
        }

        // MCP-Reborn Entity#baseTick refreshes wasTouchingWater/fluid heights
        // before travel, and LivingEntity#floatInWaterWhileRidden later reads
        // that cached state inside the same tick. Keep the current tick's
        // pre-move deep-water state so the next tick can carry the exact +0.04.
        boolean canFloatWhileRidden = simulationContext.getVehicle() != null
                && EntityTypeUtil.canFloatWhileRidden(simulationContext.getVehicle().type)
                // MCP-Reborn LivingEntity#floatInWaterWhileRidden requires the mount
                // to still be a vehicle, which the riding relationship in this
                // simulation context proves.
                && !minFluidState.isWaterTimingUncertain()
                && minFluidState.isInWater()
                // MCP-Reborn Entity#getFluidJumpThreshold returns 0.4 for these
                // rideable entities, and the float is only added strictly above it.
                && minFluidState.getMaxWaterHeight() > 0.4D;
        boolean couldFloatWhileRidden = simulationContext.getVehicle() != null
                && EntityTypeUtil.canFloatWhileRidden(simulationContext.getVehicle().type)
                // The same pre-move water sample drives LivingEntity#floatInWaterWhileRidden.
                // When client-visible fluid timing is uncertain, the exact +0.04 carry is
                // likewise uncertain, but the branch remains mathematically possible.
                && minFluidState.getMaxWaterHeight() > 0.4D
                && (minFluidState.isInWater() || minFluidState.isWaterTimingUncertain());

        AtomicReference<DesyncStatus> climbing = new AtomicReference<>(DesyncStatus.fromBoolean(Collisions.onClimbable(player, to.x, to.y, to.z)));

        BlockData toState = player.compensatedWorld.getBlockDataAt(to.x, to.y, to.z);
        if (toState.getMaterial() == Material.POWDER_SNOW && player.getInventory().getBoots().getType() == Material.LEATHER_BOOTS) {
            climbing.set(DesyncStatus.UNKNOWN);
        }

        float feetBoxSize = (float) (player.getMovementThreshold() * 2);
        SimpleCollisionBox feetBB = GetBoundingBox.getBoundingBoxFromPosAndSize(to.x, to.y, to.z, feetBoxSize, feetBoxSize);

        if (player.isPointThree()) {
            Collisions.hasMaterial(player, feetBB, mat -> {
                BlockData state = mat.getFirst();
                BlockPos blockPos = mat.getSecond();

                boolean onClimbable = Collisions.onClimbable(player, blockPos.getX(), blockPos.getY(), blockPos.getZ());
                CollisionBox blockCollision = CollisionData.getData(state.getMaterial()).getMovementCollisionBox(player, player.getClientVersion(), state);

                // The player can't occupy this block, so we don't care.
                if (!onClimbable && blockCollision.isFullBlock()) {
                    return false;
                }

                climbing.set(climbing.get().addBoolean(onClimbable));
                return false;
            });
        }

        // Pigs and striders are the only vehicle that can climb
        if (simulationContext.getVehicle() != null && !(simulationContext.getVehicle() instanceof PacketEntityStrider || simulationContext.getVehicle().type == EntityTypesCompat.PIG)) {
            climbing.set(DesyncStatus.FALSE);
        }

        MainSupportingBlockData mainSupportingBlockData = findMainSupportingBlockPos(player, lastPrediction == null ? null : lastPrediction.getSimulationContext(), delta, simulationContext.getToMaxPose(), player.onGround);

        boolean isSuffocating = mightBeSuffocating(player, simulationContext.getStart());
        int numColliding = getNumEntitiesCollidingWith(simulationContext);
        Material onBlock = BlockProperties.getOnPos(player, mainSupportingBlockData, to);

        SimpleCollisionBox minExtent = simulationContext.getFromMinimumExtent();
        StuckEdgeData stuckEdgeData = couldBeOnStuckEdge(player, simulationContext.getTarget(), lastOnGround.determineOptimistically(), minExtent);
        // If the player isn't in water, then they aren't in a bubble column
        final BubbleColumnData bubble = computeBubbleColumns(player, to, simulationContext);

        PistonPushes push = player.compensatedWorld.tickPlayerInPistonPushingArea(simulationContext);
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_5)) {
            player.compensatedWorld.removeInvalidPistonLikeStuff();
        }
        StuckSpeedData stuckSpeedData = calculateStuckSpeed(player, simulationContext, push);

        // Eye-fluid state is sampled at the movement start position.
        double eyeY = from.y + player.getEyeHeight() - 0.1111111119389534D;
        player.wasEyeInWater = eyeY < (float) Math.floor(eyeY)
                + player.compensatedWorld.getWaterFluidLevelAt(from.x, eyeY, from.z);
        // Portal contact uses the swept inside-block volume.
        player.intersectedWithNetherPortal = Collisions.hasMaterial(player,
                simulationContext.getFromMinimumExtent().copy().union(simulationContext.getToMinimumExtent()),
                pair -> pair.getFirst().getMaterial() == Material.NETHER_PORTAL);

        DesyncStatus honeySlide = isSlidingDown(player, simulationContext);
        // MCP-Reborn Entity#checkSupportingBlock stores mainSupportingBlockPos
        // during the previous Entity#move. The next tick's
        // Entity#getBlockJumpFactor reads that stored support position through
        // getBlockPosBelowThatAffectsMyMovement(), so use this context's
        // previous-tick support data rather than the previous context's older
        // support snapshot.
        DesyncStatus onHoney = DesyncStatus.fromBoolean(BlockProperties.onHoneyBlock(player, simulationContext.getLastTickMainSupportingBlockData(), from));

        DesyncStatus desyncLastOnGround = lastOnGround;

        if (!push.getPush().isEmpty() || player.packetStateData.riptideLevel > 0) {
            desyncLastOnGround = DesyncStatus.UNKNOWN;
        }
        if (simulationContext.getVehicle() != null
                && !desyncLastOnGround.determineOptimistically()
                && simulationContext.getLastTickMainSupportingBlockData().isOnGround()) {
            // Entity#setOnGroundWithMovement updates mainSupportingBlockPos only
            // from an on-ground move. For mounted roots, packet-handler vehicle
            // teleports and echoes can leave Grim's boolean boundary stale while
            // the stored support snapshot still proves the next ridden travel
            // can read grounded friction.
            desyncLastOnGround = DesyncStatus.UNKNOWN;
        }

        SimpleCollisionBox fishingRodPulls = findFishingRodPullsBox(player);

        // Negative jump boost breaks onGround optimization
        boolean hasNegativeJumpBoost = player.compensatedEntities.getPotionLevelForPlayer(PotionEffectType.JUMP_BOOST) != null && player.compensatedEntities.getPotionLevelForPlayer(PotionEffectType.JUMP_BOOST) < 0;
        boolean canJump = desyncLastOnGround.determineOptimistically()
                && (!simulationContext.isOnGround() || hasNegativeJumpBoost || canGroundJumpInShallowFluid(simulationContext, minFluidState, maxFluidState))
                && (simulationContext.getVehicle() == null || simulationContext.getVehicle() instanceof PacketEntityHorse);

        int soulSandCount = getSoulSandCount(player, simulationContext);

        return new WorldData(desyncLastOnGround, isInWater, isInLava, touchingLava, isInFlowingFluid, isSuffocating, canJump, canFluidHop, canFloatWhileRidden, couldFloatWhileRidden, climbing.get(), numColliding, soulSandCount, weirdFourteenFifteenLava, onBlock, stuckEdgeData, bubble, push, stuckSpeedData, honeySlide, onHoney, mainSupportingBlockData, fishingRodPulls);
    }

    private boolean canGroundJumpInShallowFluid(SimulationContext simulationContext, FluidState minFluidState, FluidState maxFluidState) {
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

    private FluidState getFluidState(GrimPlayer player, SimpleCollisionBox box, double entityBoundingBoxMinY) {
        FluidState state = new FluidState(false, false, false, 0.0D, 0.0D, false, false);
        state.setWaterTimingUncertain(player.compensatedWorld.hasRecentClientWaterChanges(box));
        state.setLavaTimingUncertain(player.compensatedWorld.hasRecentClientLavaChanges(box));

        boolean oldLavaLogic = player.getClientVersion().isOlderThan(ClientVersion.V_1_14);
        SimpleCollisionBox lavaCollision = box.copy();
        if (oldLavaLogic) {
            lavaCollision.expand(-0.1f, 0, -0.1f);
            state.inLava = Collisions.hasMaterial(player, lavaCollision, mat -> mat.getFirst().getMaterial() == Material.LAVA);
        }

        Collisions.hasMaterial(player, box, mat -> {
            boolean isInsideFluid = false;
            if (NmsBlockTags.isWater(mat.getFirst())) {
                double fluidHeight = player.compensatedWorld.getWaterFluidLevelAt(mat.getSecond());
                boolean status = calculateStatusForFluid(player, box, fluidHeight, mat.getSecond());
                state.setInWater(status || state.isInWater());
                if (status) {
                    state.setMaxWaterHeight(Math.max(state.getMaxWaterHeight(), getFluidHeightAboveMinY(entityBoundingBoxMinY, fluidHeight, mat.getSecond())));
                }
                isInsideFluid = status;
            }

            if (!oldLavaLogic && mat.getFirst().getMaterial() == Material.LAVA) {
                double fluidHeight = player.compensatedWorld.getLavaFluidLevelAt(mat.getSecond());
                // 1.14 doesn't care about actual lava collision box
                boolean status = calculateStatusForFluid(player, lavaCollision, fluidHeight, mat.getSecond());
                state.setInLava(status || state.isInLava());
                if (status) {
                    state.setMaxLavaHeight(Math.max(state.getMaxLavaHeight(), getFluidHeightAboveMinY(entityBoundingBoxMinY, fluidHeight, mat.getSecond())));
                }
                isInsideFluid = status;
            }

            // If the player isn't touching the fluid, then they aren't being pushed by the water
            if (!isInsideFluid) return false;

            Vector flow = FluidTypeFlowing.getFlow(player, mat.getSecond().getX(), mat.getSecond().getY(), mat.getSecond().getZ());
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

    private boolean mightBeSuffocating(GrimPlayer player, Vec3 oldPos) {
        if (player.compensatedEntities.getSelf().inVehicle()) return false;
        // TODO: Does 0.03 affect this and if so, how?
        float bbWidth = 0.6f;
        return this.moveToClosestSpace(player, oldPos.x - bbWidth * 0.35D, oldPos.y, oldPos.z + bbWidth * 0.35D) ||
                this.moveToClosestSpace(player, oldPos.x - bbWidth * 0.35D, oldPos.y, oldPos.z - bbWidth * 0.35D) ||
                this.moveToClosestSpace(player, oldPos.x + bbWidth * 0.35D, oldPos.y, oldPos.z - bbWidth * 0.35D) ||
                this.moveToClosestSpace(player, oldPos.x + bbWidth * 0.35D, oldPos.y, oldPos.z + bbWidth * 0.35D);
    }


    // 1.12- suffocation logic: Does the block at my feet + 0.5 or the block at my head suffocate me?
    // 1.13 suffocation logic: Does the block at my feet + 0.5 (if swimming) or my feet and head suffocate me?
    //
    // 1.14 suffocation logic: Does minY to maxY exist in a block?
    private boolean suffocatesAt(GrimPlayer player, int x, int z) {
        SimpleCollisionBox axisAlignedBB = new SimpleCollisionBox(x, player.boundingBox.minY, z, x + 1.0, player.boundingBox.maxY, z + 1.0, false).expand(-1.0E-7);
        return Collisions.suffocatesAt(player, axisAlignedBB);
    }

    private boolean moveToClosestSpace(GrimPlayer player, double xPos, double yPos, double zPos) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14)) {
            return moveTowardsClosestSpaceModern(player, xPos, zPos);
        } else {
            return moveTowardsClosestSpaceLegacy(player, xPos, yPos, zPos);
        }
    }

    private boolean moveTowardsClosestSpaceModern(GrimPlayer player, double xPosition, double zPosition) {
        int blockX = (int) Math.floor(xPosition);
        int blockZ = (int) Math.floor(zPosition);

        if (!this.suffocatesAt(player, blockX, blockZ)) {
            return false;
        }
        double relativeXMovement = xPosition - blockX;
        double relativeZMovement = zPosition - blockZ;
        BlockFace direction = null;
        double lowestValue = Double.MAX_VALUE;

        for (BlockFace direction2 : new BlockFace[]{BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH}) {
            double d6;
            double d7 = direction2 == BlockFace.WEST || direction2 == BlockFace.EAST ? relativeXMovement : relativeZMovement;
            d6 = direction2 == BlockFace.EAST || direction2 == BlockFace.SOUTH ? 1.0 - d7 : d7;
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

    private boolean moveTowardsClosestSpaceLegacy(GrimPlayer player, double x, double lastY, double z) {
        int floorX = GrimMath.floor(x);
        int floorZ = GrimMath.floor(z);
        int floorY = GrimMath.floor(lastY + 0.5);

        double d0 = x - floorX;
        double d1 = z - floorZ;

        // 1.13 has slightly different logic for swimming, but no one uses 1.13 clients,
        // and it's a slight situational bypass rather than anything major so who cares.
        boolean suffocates = !clearAbove(player, floorX, floorY, floorZ);

        if (suffocates) {
            double d2 = 9999.0D;
            if (clearAbove(player, floorX - 1, floorY, floorZ) && d0 < d2) {
                return true;
            }

            if (clearAbove(player, floorX + 1, floorY, floorZ) && 1.0D - d0 < d2) {
                return true;
            }

            if (clearAbove(player, floorX, floorY, floorZ - 1) && d1 < d2) {
                return true;
            }

            if (clearAbove(player, floorX, floorY, floorZ + 1) && 1.0D - d1 < d2) {
                return true;
            }
        }
        return false;
    }

    private boolean clearAbove(GrimPlayer player, int x, int y, int z) {
        return !Collisions.doesBlockSuffocate(player, x, y, z) && !Collisions.doesBlockSuffocate(player, x, y + 1, z);
    }

    private boolean calculateStatusForFluid(GrimPlayer player, SimpleCollisionBox box, double fluidHeight, BlockPos fluidPos) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_13)) {
            SimpleCollisionBox fluidCollision = new SimpleCollisionBox(fluidPos);
            fluidCollision.maxY = fluidCollision.minY + fluidHeight;

            return box.isCollided(fluidCollision);
        } else {
            return isLegacyFluidIn(box, fluidPos.getY(), fluidHeight);
        }
    }

    private boolean isLegacyFluidIn(SimpleCollisionBox box, int y, double fluidHeight) {
        if (fluidHeight == 0) return false;
        if (y < Math.floor(box.minY)) return false;
        if (y >= Math.ceil(box.maxY)) return false;
        double d0 = (float) (y + 1) - fluidHeight;
        return GrimMath.ceil(box.maxY) >= d0;
    }

    private int getNumEntitiesCollidingWith(SimulationContext context) {
        int numColliding = 0;
        List<SimpleCollisionBox> entityCollisionBoxes = new ArrayList<>();
        List<HardCollidingEntityCollision> hardCollidingEntityCollisions = new ArrayList<>();

        // Players in vehicles do not have collisions
        if (context.getVehicle() == null) {
            Vec3 from = context.getStart();

            // Calculate the offset of the player to colliding other stuff
            SimpleCollisionBox playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(from.x, from.y, from.z, 0.6f, 1.8f);
            playerBox.union(context.getToMaximumExtent());
            playerBox.expand(0.2); // bit more because why not

            for (Int2ObjectMap.Entry<PacketEntity> entry : context.getEntities().entityMap.int2ObjectEntrySet()) { PacketEntity entity = entry.getValue();
                boolean boatLike = ac.grim.grimac.utils.nmsutil.EntityTypeUtil.isBoat(entity.type);
                boolean minecartLike = entity.isMinecart();
                boolean bedrockHardCollidingEntity = entity.type == EntityTypesCompat.STRIDER
                        || ac.grim.grimac.utils.nmsutil.EntityTypeUtil.isHappyGhast(entity.type);
                boolean hardCollidingEntity = boatLike || minecartLike || bedrockHardCollidingEntity;
                // Players can push living entities. Some entities also provide
                // hard movement collision boxes that Bedrock consumes as geometry.
                // The one exemption to a living entity is an armor stand (matters due to plugins sending them)
                if (!entity.isLivingEntity() && !hardCollidingEntity || entity.type == EntityTypesCompat.ARMOR_STAND)
                    continue;

                // MCP-Reborn push/collision checks use the entity's concrete
                // bounding box, not the reach-expanded uncertainty box.
                SimpleCollisionBox entityBox = entity.getPossibleMovementCollisionBoxes();

                if (playerBox.isCollided(entityBox)) {
                    numColliding++;
                    if (hardCollidingEntity) {
                        hardCollidingEntityCollisions.add(new HardCollidingEntityCollision(entityBox.copy(), boatLike, minecartLike));
                    } else {
                        entityCollisionBoxes.add(entityBox.copy());
                    }
                }
            }

        }
        context.cacheEntityCollisionBoxesForMovementTick(List.copyOf(entityCollisionBoxes));
        context.cacheHardCollidingEntityCollisionsForMovementTick(List.copyOf(hardCollidingEntityCollisions));

        return numColliding;
    }

    private StuckEdgeData couldBeOnStuckEdge(GrimPlayer player, Vec3 target, boolean lastOnGround, SimpleCollisionBox playerBB) {
        if (!player.isFlying && player.isSneaking && Collisions.isAboveGround(player, lastOnGround, playerBB) && !player.compensatedEntities.getSelf().inVehicle()) {
            // 16 - Magic number to stop people from crashing the server
            // 0.05 - Mojang's magic value that they use to calculate precision of sneaking
            // They move the position back by 0.05 blocks repeatedly until they are above ground
            // So by going forwards 0.05 blocks, we can determine if the player was influenced by this
            double posX = Math.max(0.05, GrimMath.clamp(target.x, -16, 16) + 0.05);
            double posZ = Math.max(0.05, GrimMath.clamp(target.z, -16, 16) + 0.05);
            double negX = Math.min(-0.05, GrimMath.clamp(target.x, -16, 16) - 0.05);
            double negZ = Math.min(-0.05, GrimMath.clamp(target.z, -16, 16) - 0.05);

            Vector NE = Collisions.maybeBackOffFromEdge(new Vector(posX, 0, negZ), player, lastOnGround, playerBB, true);
            Vector NW = Collisions.maybeBackOffFromEdge(new Vector(negX, 0, negZ), player, lastOnGround, playerBB, true);
            Vector SE = Collisions.maybeBackOffFromEdge(new Vector(posX, 0, posZ), player, lastOnGround, playerBB, true);
            Vector SW = Collisions.maybeBackOffFromEdge(new Vector(negX, 0, posZ), player, lastOnGround, playerBB, true);

            boolean isEast = NE.getX() != posX || SE.getX() != posX;
            boolean isWest = NW.getX() != negX || SW.getX() != negX;
            boolean isNorth = NE.getZ() != negZ || NW.getZ() != negZ;
            boolean isSouth = SE.getZ() != posZ || SW.getZ() != posZ;

            boolean zeroHorizBug = target.x == 0 && target.z == 0 && (isEast || isWest || isNorth || isSouth);

            return new StuckEdgeData(isNorth, isSouth, isEast, isWest, zeroHorizBug);
        }
        return new StuckEdgeData(false, false, false, false, false);
    }

    BubbleColumnData computeBubbleColumns(GrimPlayer player, Vec3 to, SimulationContext context) {
        Vec3 from = context.getStart();

        int[] pushUpwards = new int[1];
        int[] pushDownwards = new int[1];
        int[] airUpwards = new int[1];
        int[] airDownwards = new int[1];
        Set<Long> visitedBlocks = new HashSet<>();

        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_13)) {
            Vec3 movement = to.subtract(from);
            if (movement.lengthSqr() > 0.0) {
                // MCP-Reborn Entity#move records the clipped movement plus the original axis order.
                // Entity#checkInsideBlocks then replays that movement one axis at a time before
                // BubbleColumnBlock#entityInside applies Entity#onAboveBubbleColumn/onInsideBubbleColumn.
                Vec3 stepFrom = from;
                for (Direction.Axis axis : axisStepOrder(context.getTarget())) {
                    double movementOnAxis = movement.get(axis);
                    if (movementOnAxis == 0.0) {
                        continue;
                    }

                    Vec3 stepTo = stepFrom.relative(positiveDirection(axis), movementOnAxis);
                    accumulateBubbleColumns(player, context, stepFrom, stepTo, visitedBlocks, pushUpwards, pushDownwards, airUpwards, airDownwards);
                    stepFrom = stepTo;
                }
            } else {
                accumulateBubbleColumns(player, context, from, to, visitedBlocks, pushUpwards, pushDownwards, airUpwards, airDownwards);
            }
        }

        return new BubbleColumnData(airUpwards[0], pushUpwards[0], airDownwards[0], pushDownwards[0]);
    }

    private static List<Direction.Axis> axisStepOrder(Vec3 movement) {
        // MCP-Reborn 26.1 Direction#axisStepOrder and the 1.21.1 client
        // Entity#collideWithShapes both resolve Y first, then the smaller
        // horizontal component before the larger one.
        return Math.abs(movement.x) < Math.abs(movement.z)
                ? List.of(Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X)
                : List.of(Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z);
    }

    private static Direction positiveDirection(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.EAST;
            case Y -> Direction.UP;
            case Z -> Direction.SOUTH;
        };
    }

    private void accumulateBubbleColumns(GrimPlayer player, SimulationContext context, Vec3 from, Vec3 to, Set<Long> visitedBlocks, int[] pushUpwards, int[] pushDownwards, int[] airUpwards, int[] airDownwards) {
        SimpleCollisionBox fromBox = bubbleColumnBoxAt(context, from);
        SimpleCollisionBox toBox = bubbleColumnBoxAt(context, to);
        SimpleCollisionBox preciseToBox = toBox.copy().expand(-1.0E-5F);
        SimpleCollisionBox sweptBox = fromBox.copy().union(toBox);
        boolean longMove = from.distanceToSqr(to) > 0.9999900000002526D * 0.9999900000002526D;

        Collisions.hasMaterial(player, sweptBox, mat -> {
            BlockData state = mat.getFirst();
            if (state.getMaterial() != Material.BUBBLE_COLUMN) {
                return false;
            }

            BlockPos pos = mat.getSecond();
            boolean preciseIntersection = longMove || intersectsBlock(preciseToBox, pos);
            boolean pathTouches = preciseIntersection
                    || Collisions.sweptFullBlockEntityInside(fromBox, toBox, pos.getX(), pos.getY(), pos.getZ());
            if (!pathTouches || !preciseIntersection || !visitedBlocks.add(pos.asLong())) {
                return false;
            }

            net.minecraft.world.level.block.state.BlockState blockAbove = player.compensatedWorld.getBlockStateAt(pos.above());
            boolean isDown = NmsBlockTags.getBoolean(NmsBlockTags.toNmsState(state), BlockStateProperties.DRAG);
            boolean airAbove = blockAbove.getCollisionShape(player.compensatedWorld, pos).isEmpty()
                    && blockAbove.getFluidState().isEmpty();
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

    static SimpleCollisionBox bubbleColumnBoxAt(SimulationContext context, Vec3 position) {
        if (context.getVehicle() == null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, context.getPose().width * context.getScale(), context.getPose().height * context.getScale());
        }

        return GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, context.getMaxWidth(), context.getMaxHeight());
    }

    private boolean intersectsBlock(SimpleCollisionBox box, BlockPos pos) {
        return box.minX < pos.getX() + 1.0D && box.maxX > pos.getX()
                && box.minY < pos.getY() + 1.0D && box.maxY > pos.getY()
                && box.minZ < pos.getZ() + 1.0D && box.maxZ > pos.getZ();
    }

    private int getSoulSandCount(GrimPlayer player, SimulationContext context) {
        if (context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_14)) return 0;

        Vec3 to = context.getEnd();
        float sneakHeight = context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_9) ? 1.65f : 1.5f;
        float height = player.isSneaking ? sneakHeight : 1.8f;
        SimpleCollisionBox playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(to.x, to.y, to.z, 0.6f, height);

        double expandAmount = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_19_4) ? 1e-7 : 0.001;
        playerBox.expand(-expandAmount);

        int[] soulSandCount = new int[1];
        Collisions.hasMaterial(player, playerBox, mat -> {
            if (mat.getFirst().getMaterial() == Material.SOUL_SAND) {
                soulSandCount[0]++;
            }
            return false;
        });
        return soulSandCount[0];
    }

    public DesyncStatus isSlidingDown(GrimPlayer player, SimulationContext context) {
        // MCP-Reborn Entity#move records the movement path, and Entity#baseTick
        // later calls checkInsideBlocks(movements). HoneyBlock#entityInside can
        // therefore run while leaving the honey side even if the final AABB is
        // no longer inside the honey block.
        SimpleCollisionBox smallHoneySearch = context.getFromMinimumExtent().copy().union(context.getToMinimumExtent());
        SimpleCollisionBox largeHoneySearch = context.getFromMaximumExtent().copy().union(context.getToMaximumExtent());
        boolean hasSmall = Collisions.hasMaterial(player, smallHoneySearch, mat -> {
            if (mat.getFirst().getMaterial() == Material.HONEY_BLOCK) {
                BlockPos pos = mat.getSecond();
                return Collisions.isSlidingDown(player, pos.getX(), pos.getY(), pos.getZ(), context.getEnd());
            }
            return false;
        });
        boolean hasLarge = Collisions.hasMaterial(player, largeHoneySearch, mat -> {
            if (mat.getFirst().getMaterial() == Material.HONEY_BLOCK) {
                BlockPos pos = mat.getSecond();
                return Collisions.isSlidingDown(player, pos.getX(), pos.getY(), pos.getZ(), context.getEnd());
            }
            return false;
        });
        return DesyncStatus.fromBooleans(hasSmall, hasLarge);
    }

    public StuckSpeedData calculateStuckSpeed(GrimPlayer player, SimulationContext context, PistonPushes push) {
        // MCP-Reborn Entity#checkInsideBlocks uses Entity#makeBoundingBox(to)
        // for the entity itself. The min/max split models player pose and 0.03
        // movement-threshold uncertainty; vehicle roots do not have a shorter
        // player-like minimum body, so their required stuck speed must use the
        // real vehicle AABB.
        SimpleCollisionBox primaryTo = context.getVehicle() != null ? context.getToMaximumExtent() : context.getToMinimumExtent();
        SimpleCollisionBox primaryFrom = context.getVehicle() != null ? context.getFromMaximumExtent() : context.getFromMinimumExtent();
        boolean powderSnowCanApply = powderSnowCanApplyToRootEntity(player, context);
        Vec3 minPrimary = Collisions.checkStuckSpeed(player, primaryTo, powderSnowCanApply);
        Vec3 minSwept = Collisions.checkStuckSpeedAlongMovement(player, primaryFrom, primaryTo, context.getTarget(), powderSnowCanApply);
        Vec3 maxPrimary = context.getVehicle() != null ? null : Collisions.checkStuckSpeed(player, context.getToMaximumExtent(), powderSnowCanApply);
        Vec3 maxSwept = context.getVehicle() != null ? null : Collisions.checkStuckSpeedAlongMovement(player, context.getFromMaximumExtent(), context.getToMaximumExtent(), context.getTarget(), powderSnowCanApply);

        boolean hasPistonPush = push != null && push.getPistonPush() != null && !push.getPistonPush().isEmpty();
        Vec3 required = minPrimary;
        Vec3 optional = firstKnownStuckSpeed(maxPrimary, maxSwept);

        if (hasPistonPush) {
            // MCP-Reborn LocalPlayer#tick sends movement during ClientLevel#tickEntities,
            // before Minecraft#tick runs piston block entities. A later
            // PistonMovingBlockEntity#moveEntityByPiston calls
            // Entity#move(MoverType.PISTON), which clears Entity#stuckSpeedMultiplier
            // without applying it to the piston shove, so piston-overlapped stuck
            // speed is optional rather than required for the next packet.
            Vec3 pistonOptional = firstKnownStuckSpeed(
                    firstKnownStuckSpeed(required, minSwept),
                    optional);
            return new StuckSpeedData(null, pistonOptional);
        }

        if (required == null) {
            required = minSwept;
        }

        // This is technically incorrect to simply disregard sub-possibilities of bounding boxes
        // I don't care enough to fix it though, this will never happen outside a test server
        if (optional != null && optional.equals(required)) optional = null;
        return new StuckSpeedData(required, optional);
    }

    private boolean powderSnowCanApplyToRootEntity(GrimPlayer player, SimulationContext context) {
        PacketEntity rootVehicle = context.getVehicle();
        if (rootVehicle != null && !rootVehicle.isLivingEntity()) {
            return true;
        }

        // PowderSnowBlock#entityInside only calls Entity#makeStuckInBlock for a
        // LivingEntity when entity.getInBlockState().is(this). Entity#getInBlockState
        // is the block at the root entity's current blockPosition, not every block
        // intersected by its body AABB during Entity#checkInsideBlocks.
        return player.compensatedWorld.getBlockDataAt(context.getEnd().x, context.getEnd().y, context.getEnd().z).getMaterial() == Material.POWDER_SNOW;
    }

    private Vec3 firstKnownStuckSpeed(Vec3 primary, Vec3 swept) {
        return primary != null ? primary : swept;
    }

    private SimpleCollisionBox findFishingRodPullsBox(GrimPlayer player) {
        if (player.compensatedEntities.fishingRodPulls.isEmpty()) return null;

        SimpleCollisionBox fishingRodPulls = new SimpleCollisionBox();
        for (SimpleCollisionBox pullTo : player.compensatedEntities.fishingRodPulls) {
            Vector pullOrigin = new Vector(player.lastX, player.lastY + 0.8 * 1.8, player.lastZ);

            Vector diff = new Vector(pullTo.minX, pullTo.minY, pullTo.minZ).subtract(pullOrigin).multiply(0.1);
            fishingRodPulls.minX = Math.min(0, diff.getX());
            fishingRodPulls.minY = Math.min(0, diff.getY());
            fishingRodPulls.minZ = Math.min(0, diff.getZ());

            diff = new Vector(pullTo.maxX, pullTo.maxY, pullTo.maxZ).subtract(pullOrigin).multiply(0.1);
            fishingRodPulls.maxX = Math.max(0, diff.getX());
            fishingRodPulls.maxY = Math.max(0, diff.getY());
            fishingRodPulls.maxZ = Math.max(0, diff.getZ());
        }
        return fishingRodPulls;
    }

    private MainSupportingBlockData findMainSupportingBlockPos(GrimPlayer player, SimulationContext lastContext, Vec3 lastMovement, SimpleCollisionBox maxPose, boolean isOnGround) {
        if (!isOnGround) {
            return new MainSupportingBlockData(null, false);
        }

        SimpleCollisionBox slightlyBelowPlayer = new SimpleCollisionBox(maxPose.minX, maxPose.minY - 1.0E-6D, maxPose.minZ, maxPose.maxX, maxPose.minY, maxPose.maxZ);

        Optional<BlockPos> supportingBlock = findSupportingBlock(player, slightlyBelowPlayer);
        if (!supportingBlock.isPresent() && (lastContext != null && !lastContext.getWorldData().getMainSupportingBlockPos().lastOnGroundAndNoBlock())) {
            if (lastMovement != null) {
                SimpleCollisionBox aabb2 = slightlyBelowPlayer.offset(-lastMovement.x, 0.0D, -lastMovement.z);
                supportingBlock = findSupportingBlock(player, aabb2);
                return new MainSupportingBlockData(supportingBlock.orElse(null), true);
            }
        } else {
            return new MainSupportingBlockData(supportingBlock.orElse(null), true);
        }

        return new MainSupportingBlockData(null, true);
    }

    private Optional<BlockPos> findSupportingBlock(GrimPlayer player, SimpleCollisionBox searchBox) {
        Vec3 playerPos = new Vec3(player.x, player.y, player.z);

        AtomicReference<BlockPos> bestBlockPos = new AtomicReference<>();
        AtomicDouble blockPosDistance = new AtomicDouble(Double.MAX_VALUE);

        Collisions.hasMaterial(player, searchBox, (thing) -> {
            BlockPos blockPos = thing.getSecond();

            CollisionBox collision = CollisionData.getData(thing.getFirst().getMaterial()).getMovementCollisionBox(player, player.getClientVersion(), thing.getFirst(), blockPos.getX(), blockPos.getY(), blockPos.getZ());
            if (!collision.isIntersected(searchBox)) return false;

            Vec3 blockCenter = new Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
            double distance = playerPos.distanceToSqr(blockCenter);

            if (distance < blockPosDistance.get() || distance == blockPosDistance.get() && (bestBlockPos.get() == null || firstHasPriorityOverSecond(blockPos, bestBlockPos.get()))) {
                bestBlockPos.set(blockPos);
                blockPosDistance.set(distance);
            }

            return false;
        });


        return Optional.ofNullable(bestBlockPos.get());
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
