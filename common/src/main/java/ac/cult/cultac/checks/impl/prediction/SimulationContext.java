package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockProtocolVersion;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.LastInstance;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.*;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import ac.cult.cultac.utils.nmsutil.Friction;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NmsBlockTags;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;

@Getter
@ToString
public class SimulationContext {
    Vec3 start, end;
    @Setter
    Vec3 target;
    @ToString.Exclude
    ClientVersion version;
    @ToString.Exclude
    TrigHandler trig;
    @ToString.Exclude
    CompensatedEntities entities;
    PacketEntity vehicle;
    float xRot, lastXRot;
    float yRot, lastYRot;
    boolean onGround;
    boolean sneaking, lastSneaking;
    boolean gliding;
    // Index at 0, null = none
    Integer jumpAmplifier;
    float depthStriderLevel;
    int swiftSneakLevel;
    // This is NOT the same thing as
    DesyncStatus isSprinting;
    int minAttackSlow, maxAttackSlow;
    // WorldData must be generated based on movement state...
    // I don't want to give access to the world to everything - bad for performance and an antipattern
    @Setter
    WorldData worldData;
    int riptideLevel;
    LastInstance lastTickSkip;
    boolean isTestingPointThree;
    Vec3 lastStuckSpeed = new Vec3(1, 1, 1);
    Vec3 horseInputs;
    MainSupportingBlockData lastTickMainSupportingBlockData;
    Pose pose;
    float scale;
    @Setter
    Vec3 rootClientVelocityAtPrediction;
    @Setter
    Vec3 normalizedRootClientVelocityAtPrediction;
    @Setter
    boolean rootClientVelocityPendingAtPrediction;
    @Setter
    Vec3 requiredCurrentMoveStuckSpeed;
    @Setter
    PredictionCarry profileCarry;
    AuthoredMovementFrame authoredInput;
    BedrockAuthInputFrame bedrockInput;
    BedrockProtocolVersion bedrockVersion = BedrockProtocolVersion.UNKNOWN;
    long bedrockInputTick = -1L;
    boolean hasTrustedAuthoredInput;
    @Setter
    TeleportData bedrockTeleport;
    @ToString.Exclude
    List<SimpleCollisionBox> entityCollisionBoxesForMovementTick;
    @ToString.Exclude
    List<SimpleCollisionBox> hardCollidingEntityCollisionBoxesForMovementTick;
    @ToString.Exclude
    List<HardCollidingEntityCollision> hardCollidingEntityCollisionsForMovementTick;

    public SimulationContext(Vec3 start, Vec3 end, Vec3 target, ClientVersion version, TrigHandler trig, CompensatedEntities entities, PacketEntity vehicle, float xRot, float lastXRot, float yRot, float lastYRot, boolean onGround, boolean sneaking, boolean lastSneaking, boolean gliding, Integer jumpAmplifier, float depthStriderLevel, int swiftSneakLevel, DesyncStatus isSprinting, int minAttackSlow, int maxAttackSlow, int riptideLevel, LastInstance lastTickSkip, boolean isTestingPointThree, Vec3 horseInputs, boolean clientMovementInputKnown, Vec3 clientMovementInput, MainSupportingBlockData lastTickMainSupportingBlockData, Pose pose, float scale) {
        this.start = start;
        this.end = end;
        this.target = target;
        this.version = version;
        this.trig = trig;
        this.entities = entities;
        this.vehicle = vehicle;
        this.xRot = xRot;
        this.lastXRot = lastXRot;
        this.yRot = yRot;
        this.lastYRot = lastYRot;
        this.onGround = onGround;
        this.sneaking = sneaking;
        this.lastSneaking = lastSneaking;
        this.gliding = gliding;
        this.jumpAmplifier = jumpAmplifier;
        this.depthStriderLevel = depthStriderLevel;
        this.swiftSneakLevel = swiftSneakLevel;
        this.isSprinting = isSprinting;
        this.minAttackSlow = minAttackSlow;
        this.maxAttackSlow = maxAttackSlow;
        this.riptideLevel = riptideLevel;
        this.lastTickSkip = lastTickSkip;
        this.isTestingPointThree = isTestingPointThree;
        this.horseInputs = horseInputs;
        this.lastTickMainSupportingBlockData = lastTickMainSupportingBlockData;
        this.pose = pose;
        this.scale = scale;
    }

    public void handleLastRequiredStuckSpeed(Vec3 stuckSpeedRequired) {
        if (stuckSpeedRequired != null) {
            lastStuckSpeed = stuckSpeedRequired;
        }
        setTarget(target.multiply(1 / lastStuckSpeed.x, 1 / lastStuckSpeed.y, 1 / lastStuckSpeed.z));
    }

    public void attachAuthoredInput(AuthoredMovementFrame authoredInput) {
        this.authoredInput = authoredInput;
        this.bedrockInput = authoredInput instanceof BedrockAuthInputFrame frame ? frame : null;
        this.hasTrustedAuthoredInput = authoredInput != null;
        this.bedrockInputTick = -1L;
        if (bedrockInput != null) {
            this.bedrockVersion = bedrockInput.getProtocolVersion();
        }
    }

    public void setBedrockAuthoritativeInputTick(long bedrockInputTick) {
        this.bedrockInputTick = Math.max(0L, bedrockInputTick);
    }

    public boolean hasTrustedAuthoredInput() {
        return hasTrustedAuthoredInput;
    }

    public long getAuthoredInputTick() {
        return bedrockInputTick;
    }

    public void cacheEntityCollisionBoxesForMovementTick(List<SimpleCollisionBox> entityCollisionBoxesForMovementTick) {
        this.entityCollisionBoxesForMovementTick = entityCollisionBoxesForMovementTick;
    }

    public void cacheHardCollidingEntityCollisionBoxesForMovementTick(List<SimpleCollisionBox> hardCollidingEntityCollisionBoxesForMovementTick) {
        this.hardCollidingEntityCollisionBoxesForMovementTick = hardCollidingEntityCollisionBoxesForMovementTick;
        this.hardCollidingEntityCollisionsForMovementTick = hardCollidingEntityCollisionBoxesForMovementTick == null ? null
                : hardCollidingEntityCollisionBoxesForMovementTick.stream()
                .map(box -> new HardCollidingEntityCollision(box, false, false))
                .toList();
    }

    public void cacheHardCollidingEntityCollisionsForMovementTick(List<HardCollidingEntityCollision> hardCollidingEntityCollisionsForMovementTick) {
        this.hardCollidingEntityCollisionsForMovementTick = hardCollidingEntityCollisionsForMovementTick;
        this.hardCollidingEntityCollisionBoxesForMovementTick = hardCollidingEntityCollisionsForMovementTick == null ? null
                : hardCollidingEntityCollisionsForMovementTick.stream()
                .map(HardCollidingEntityCollision::box)
                .toList();
    }

    public double getTargetScalarHoriz() {
        return 1 / lastStuckSpeed.x;
    }

    public double getTargetScalarVert() {
        return 1 / lastStuckSpeed.y;
    }

    public DesyncStatus getLastOnGround() {
        return worldData.getLastOnGround();
    }

    public boolean usesFallFlyingMovement() {
        return usesFallFlyingMovement(version, gliding, worldData == null ? DesyncStatus.FALSE : worldData.getClimbingAtStart());
    }

    public static boolean usesFallFlyingMovement(ClientVersion version, boolean gliding, DesyncStatus climbingAtStart) {
        // Added in 25w02a / 1.21.5: travelFallFlying calls travelInAir and then
        // stopFallFlying when already on a climbable, before performing the move.
        return gliding && (version.isOlderThan(ClientVersion.V_1_21_5)
                || !climbingAtStart.determinePessimistically());
    }

    private double getMovementThreshold(ClientVersion version) {
        return version.isOlderThan(ClientVersion.V_1_18_2) ? 0.03 : 0.0002;
    }

    public SimpleCollisionBox getToMaxPose() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, 0.6f, 1.8f);
    }

    public SimpleCollisionBox getToMaximumExtent() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, 0.6f, 1.8f).expand(getMovementThreshold(version));
    }

    public SimpleCollisionBox getToMinimumExtent() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), 0.6f);
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, 0.6f, 0.6f).expand(-getMovementThreshold(version));
    }

    public SimpleCollisionBox getFromMaximumExtent() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(start.x, start.y, start.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(start.x, start.y, start.z, 0.6f, 1.8f);
    }

    public SimpleCollisionBox getFromMinimumExtent() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(start.x, start.y, start.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(start.x, start.y, start.z, getMaxWidth(), 0.6f);
    }

    public SimpleCollisionBox getToCubeCollision() {
        if (getVehicle() != null) {
            return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, getMaxWidth(), getMaxHeight());
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, 0.6f, 0.6f);
    }

    public SimpleCollisionBox getToActualPose() {
        if (getVehicle() != null) {
            return getToMaxPose();
        }
        return GetBoundingBox.getBoundingBoxFromPosAndSize(end.x, end.y, end.z, pose.width * scale, pose.height * scale);
    }

    public float getMaxSpeed(CultPlayer player) {
        float blockUnderPlayer = BlockProperties.getFriction(player, lastTickMainSupportingBlockData, start);
        return getFrictionInfluencedSpeed(blockUnderPlayer, player);
    }

    public float getGroundAirSpeed(CultPlayer player) {
        float blockUnderPlayer = BlockProperties.getFriction(player, lastTickMainSupportingBlockData, start);
        float maxSpeed = 0;
        for (boolean onGround : worldData.getLastOnGround().getStates()) {
            maxSpeed = Math.max(maxSpeed, getFrictionInfluencedSpeed(blockUnderPlayer, player, onGround, false, false));
        }
        return maxSpeed;
    }

    public float getFluidSpeed(CultPlayer player, boolean water, boolean lava) {
        float blockUnderPlayer = BlockProperties.getFriction(player, lastTickMainSupportingBlockData, start);
        return getFrictionInfluencedSpeed(blockUnderPlayer, player, false, water, lava);
    }

    public float getMoveRelativeSpeed(CultPlayer player, boolean onGround, boolean water, boolean lava) {
        float blockUnderPlayer = BlockProperties.getFriction(player, lastTickMainSupportingBlockData, start);
        return getFrictionInfluencedSpeed(blockUnderPlayer, player, onGround, water, lava);
    }

    public float getMaxWidth() {
        if (getVehicle() != null) {
            return BoundingBoxSize.getWidth(getVehicle(), version);
        }
        return 0.6f;
    }

    public float getMaxHeight() {
        if (getVehicle() != null) {
            return BoundingBoxSize.getHeight(getVehicle(), version);
        }
        return 1.8f;
    }

    private float getFrictionInfluencedSpeed(float friction, CultPlayer player) {
        float maxSpeed = 0;
        for (boolean onGround : worldData.getLastOnGround().getStates()) {
            for (boolean water : worldData.getInWater().getStates()) {
                for (boolean lava : worldData.getInLava().getStates()) {
                    maxSpeed = Math.max(maxSpeed, getFrictionInfluencedSpeed(friction, player, onGround, water, lava));
                }
            }
        }
        return maxSpeed;
    }

    private float getFrictionInfluencedSpeed(float friction, CultPlayer player, boolean isOnGround, boolean water, boolean lava) {
        final double normalSpeed = getPlayerMovementSpeed(player);

        if (vehicle instanceof PacketEntityNautilus && water) {
            // AbstractNautilus#travelInWater bypasses LivingEntity's generic
            // 0.02 fluid acceleration and calls moveRelative(getSpeed(), input).
            // travelRidden sets getSpeed() to getRiddenSpeed immediately before
            // travel, which is 0.0325 * MOVEMENT_SPEED while in water.
            return (float) normalSpeed;
        }

        if (water) {
            float speed = 0.02f;

            if (depthStriderLevel > 0) {
                speed += (normalSpeed * 1.3 - speed) * depthStriderLevel / 3.0F;
            }
            return speed;
        }

        if (lava) {
            return 0.02f;
        }

        float onGroundSpeed = (float) (normalSpeed * (0.21600002f / (friction * friction * friction)));
        if (isOnGround) {
            // MCP-Reborn 26.2 LivingEntity#getFrictionInfluencedSpeed receives the
            // friction_modifier-adjusted block friction and only boosts movement
            // speed on slippery blocks (friction > 0.6).
            if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2)) {
                float modifiedFriction = Friction.computeModifiedFriction(friction, (float) player.compensatedEntities.getEntityInControl().frictionModifier);
                if (modifiedFriction > 0.6f) {
                    return (float) (normalSpeed * (0.21600002f / (modifiedFriction * modifiedFriction * modifiedFriction)));
                }
                return (float) normalSpeed;
            }
            return onGroundSpeed;
        }

        // The game uses values known as flyingSpeed for some vehicles in the air
        if (vehicle != null) {
            if (vehicle.type == EntityTypesCompat.PIG
                    || vehicle instanceof PacketEntityHorse
                    || vehicle instanceof PacketEntityStrider
                    || vehicle instanceof PacketEntityNautilus) {
                // MCP-Reborn LivingEntity#getFlyingSpeed uses this.getSpeed() * 0.1F for
                // player-controlled ridden living entities. normalSpeed already mirrors
                // getRiddenSpeed(...) for pigs, horses, and striders below.
                return (float) (normalSpeed * 0.1f);
            }
        }

        if (player.isFlying) {
            // TODO: Lunar support (FUCK LUNAR)
            return player.flySpeed * 20 * (player.isSprinting ? 0.1f : 0.05f);
        }

        // MCP-Reborn Player#getFlyingSpeed uses the sprinting air acceleration
        // whenever sprinting is possible for this tick.
        return getPlayerFlyingSpeed(isSprinting);
    }

    static float getPlayerFlyingSpeed(DesyncStatus sprinting) {
        return sprinting.determineOptimistically() ? 0.025999999F : 0.02F;
    }

    private double getPlayerMovementSpeed(CultPlayer player) { if (vehicle == null) {
            return entities.getPlayerMovementSpeed();
        }
        if (vehicle instanceof PacketEntityCamel camel) {
            final double sprintBonus = player.vehicleData.camelSprintingState != ac.cult.cultac.utils.data.SprintingState.STOPPED
                    && camel.dashCooldown == 0 && !camel.dashing ? 0.1 : 0;
            return camel.movementSpeedAttribute + sprintBonus;
        }

        if (vehicle instanceof PacketEntityHorse) {
            return ((PacketEntityHorse) vehicle).movementSpeedAttribute;
        }
        if (vehicle instanceof PacketEntityNautilus nautilus) {
            boolean water = worldData != null && worldData.getInWater().determineOptimistically();
            return (water ? 0.0325F : 0.02F) * nautilus.movementSpeedAttribute;
        }
        // Must be rideable?
        if (vehicle instanceof PacketEntityRideable) {
            PacketEntityRideable rideable = (PacketEntityRideable) vehicle;
            double speed = rideable.movementSpeedAttribute;
            if (vehicle.type == EntityTypesCompat.PIG) {
                speed *= 0.225D;
            } else if (vehicle.type == EntityTypesCompat.STRIDER) {
                speed *= isMountedStriderSuffocating(player) ? 0.35F : 0.55F;
            }
            return (float) (speed * rideable.boost.factor());
        }
        return 0; // ??? I guess riding a weird vehicle?
    }

    private boolean isMountedStriderSuffocating(CultPlayer player) {
        if (!(vehicle instanceof PacketEntityStrider)) {
            return false;
        }

        // MCP-Reborn Strider#tick recomputes this local state from the root
        // strider's exact start-of-tick position via blockPosition(),
        // getBlockStateOnLegacy(), and getFluidHeight(FluidTags.LAVA) before
        // super.tick() reaches LivingEntity#travelRidden/getRiddenSpeed.
        BlockPos blockPos = new BlockPos((int) Math.floor(start.x), (int) Math.floor(start.y), (int) Math.floor(start.z));
        Material currentBlock = player.compensatedWorld.getBlockStateAt(blockPos).getBukkitMaterial();
        Material legacyBlock = BlockProperties.getOnPos(player, lastTickMainSupportingBlockData, start);
        boolean warm = NmsBlockTags.hasBlockTag(currentBlock, BlockTags.STRIDER_WARM_BLOCKS)
                || NmsBlockTags.hasBlockTag(legacyBlock, BlockTags.STRIDER_WARM_BLOCKS)
                || player.compensatedWorld.getLavaFluidLevelAt(blockPos) > 0.0D;
        return !warm;
    }

    public boolean isBedrockTeleportTick() {
        return bedrockTeleport != null;
    }

    public record HardCollidingEntityCollision(
            SimpleCollisionBox box,
            boolean boatLike,
            boolean minecartLike
    ) {
    }
}
