package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * The delta movements a local passenger can carry out of its last Entity#rideTick: the tick zeroes the delta, then
 * LivingEntity#aiStep and LivingEntity#travel move the passenger by its own input from where the previous
 * Entity#positionRider put it, and Entity#positionRider replaces only the position. Taken at the ride tick, with that
 * tick's effects, attributes, blocks, keys and rotation; a later set_entity_motion replaces it.
 */
public final class PassengerRideTick {
    /** LocalPlayer#modifyInput for a passenger, by {@link #inputIndex}: x then z. */
    private static final double[] INPUTS = new double[18];

    static {
        for (int left = -1; left <= 1; left++) {
            for (int forward = -1; forward <= 1; forward++) {
                Vec3 input =
                        VelocityTransformer.getExactControllerModifiedInputStatic(new Vec3(left, 0.0D, forward), 1.0F);
                // Entity#getInputVector
                if (input.lengthSqr() > 1.0D) input = input.normalize();
                int index = inputIndex(left, forward);
                INPUTS[index] = input.x;
                INPUTS[index + 1] = input.z;
            }
        }
    }

    private double[] velocities = new double[16 * 3];
    private int count;
    /** Whether the velocities come from the ride tick's own travel, whose move can zero a horizontal axis. */
    private boolean fromTravel;

    private boolean inFluid;

    private final SimpleCollisionBox box = new SimpleCollisionBox();
    private final SimpleCollisionBox vehicleBox = new SimpleCollisionBox();
    private final SimpleCollisionBox vehicleScratch = new SimpleCollisionBox();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final FluidCurrent waterCurrent = new FluidCurrent();
    private final FluidCurrent lavaCurrent = new FluidCurrent();
    private boolean hasWaterCurrent;
    private boolean hasLavaCurrent;
    /** The lowest top the ridden boat's possible boxes allow, from {@link #boatMayBeUnderWater}. */
    private double boatTop;
    /** The boat the previous ride tick recorded on and its possible boxes, which this tick's boat moves from. */
    private PacketEntity previousBoat;

    private final SimpleCollisionBox previousBoatBox = new SimpleCollisionBox();

    /**
     * Records the velocities the ride tick that started at {@code x, y, z} can leave; {@code touchedPowderSnow} tells
     * whether the previous tick's box can have touched powder snow.
     */
    public void record(CultPlayer player, double x, double y, double z, boolean touchedPowderSnow) {
        this.count = 0;
        this.fromTravel = true;
        PacketEntitySelf self = player.compensatedEntities.playerEntity;
        KnownInput keys = player.packetStateData.knownInput;
        boolean legacy = player.getClientVersion().isOlderThan(ClientVersion.V_1_21_4);
        boolean jump = keys.jump();
        float halfWidth = player.pose.width * player.dimensionScale / 2.0F;
        this.box.set(
                x - halfWidth,
                y,
                z - halfWidth,
                x + halfWidth,
                y + player.pose.height * player.dimensionScale,
                z + halfWidth);
        boolean water;
        boolean lava;
        PacketEntity vehicle = self.getRiding();
        if (vehicle == null || !vehicle.isBoat()) this.previousBoat = null;
        if (vehicle != null && vehicle.isBoat() && !this.boatMayBeUnderWater(player, vehicle)) {
            if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)) {
                // AbstractBoat#modifyPassengerFluidInteractionBox keeps only the part of the box above the boat.
                this.box.minY = Math.max(this.box.minY, this.boatTop);
                boolean above = this.boatTop < this.box.maxY;
                water = above && this.touchesFluid(player, FluidTags.WATER);
                lava = above && this.touchesFluid(player, FluidTags.LAVA);
            } else {
                // Entity#updateInWaterStateAndDoWaterCurrentPushing keeps a boat's passenger out of water.
                water = false;
                lava = this.touchesFluid(player, FluidTags.LAVA);
            }
        } else {
            water = this.touchesFluid(player, FluidTags.WATER);
            lava = this.touchesFluid(player, FluidTags.LAVA);
        }
        this.inFluid = water || lava;
        // Entity#baseTick pushes the zeroed delta by the fluid's current.
        this.hasWaterCurrent =
                water && this.waterCurrent.compute(player, this.box, true, 0.0D, 0.0D, FluidTags.WATER, 0.014D);
        this.hasLavaCurrent = lava
                && this.lavaCurrent.compute(
                        player,
                        this.box,
                        true,
                        0.0D,
                        0.0D,
                        FluidTags.LAVA,
                        player.compensatedWorld.hasFastLava() ? 0.007D : 0.0023333333333333335D);
        int blockX = CultMath.floor(x);
        int blockZ = CultMath.floor(z);
        int feetState = player.compensatedWorld.getBlockStateIdAt(blockX, CultMath.floor(y), blockZ);
        int belowState = player.compensatedWorld.getBlockStateIdAt(blockX, CultMath.floor(y - 0.500001D), blockZ);
        boolean climbable = Collisions.onClimbable(player, x, y, z);
        // LivingEntity#handleRelativeFrictionAndCalculateMovement climbs with leather boots after powder snow.
        boolean climbs = climbable || touchedPowderSnow;
        boolean chunkLoaded = player.compensatedWorld.isChunkLoaded(blockX >> 4, blockZ >> 4);
        boolean aboveMinY = y > player.compensatedWorld.getMinHeight();
        float jumpPower = (float) self.jumpStrength
                        * (BlockIds.is(feetState, BlockIds.HONEY_BLOCK) || BlockIds.is(belowState, BlockIds.HONEY_BLOCK)
                                ? 0.5F
                                : 1.0F)
                + jumpBoostPower(self);

        float yawRadians = player.xRot * ((float) Math.PI / 180F);
        float sin = player.trigHandler.sin(yawRadians);
        float cos = player.trigHandler.cos(yawRadians);
        int index = inputIndex(
                (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0), (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0));
        double inputX = INPUTS[index];
        double inputZ = INPUTS[index + 1];
        boolean sprintOpen = player.sprintState.outcomeOpen();
        for (int sprintCase = 0; sprintCase < (sprintOpen ? 2 : 1); sprintCase++) {
            boolean sprinting = sprintOpen ? sprintCase == 1 : player.isSprinting;
            // Before 1.21.4 Player#tick keeps a passenger's ground state, which only a ride tick's move sets.
            for (int groundCase = 0; groundCase < (legacy ? 2 : 1); groundCase++) {
                boolean onGround = groundCase == 1;
                float blockFriction = onGround ? BlockProperties.getMaterialFriction(belowState) : 1.0F;
                // LivingEntity#getFrictionInfluencedSpeed; Player#getFlyingSpeed off the ground.
                float speed = onGround
                        ? movementSpeed(self, sprinting)
                                * (0.21600002F / (blockFriction * blockFriction * blockFriction))
                        : sprinting ? 0.025999999F : 0.02F;
                this.air(
                        player,
                        self,
                        inputX * speed,
                        inputZ * speed,
                        sin,
                        cos,
                        0.0D,
                        blockFriction,
                        climbable,
                        climbs,
                        chunkLoaded,
                        aboveMinY);
                // LivingEntity#jumpFromGround, which a noJumpDelay left by an earlier jump can hold back.
                if (onGround && jump && jumpPower > 1.0E-5F) {
                    double boostX = sprinting ? -sin * 0.2F : 0.0D;
                    double boostZ = sprinting ? cos * 0.2F : 0.0D;
                    this.air(
                            player,
                            self,
                            inputX * speed,
                            inputZ * speed,
                            sin,
                            cos,
                            jumpPower,
                            blockFriction,
                            climbable,
                            climbs,
                            chunkLoaded,
                            aboveMinY,
                            boostX,
                            boostZ);
                }
                if (water) this.water(self, inputX, inputZ, sin, cos, sprinting, onGround, jump, jumpPower, climbable);
                if (lava) this.lava(self, inputX, inputZ, sin, cos, sprinting, jump, jumpPower);
            }
        }
    }

    /** A client tick without a ride tick ends the boat the next ride tick could move from. */
    public void clearBoat() {
        this.previousBoat = null;
    }

    /** A set_entity_motion after the ride tick sets the delta movement the passenger keeps. */
    public void setMotion(Vec3 velocity) {
        this.count = 0;
        this.fromTravel = false;
        this.add(velocity.x, velocity.y, velocity.z);
    }

    /** Whether the recorded ride tick started touching a fluid. */
    public boolean inFluid() {
        return this.inFluid;
    }

    /**
     * The recorded velocities; for the ride tick's own travel, each with the axes Entity#move zeroes when it consumes a
     * multiplier or collides horizontally, before gravity.
     */
    public List<Vec3> velocities() {
        List<Vec3> result = new ArrayList<>(this.fromTravel ? this.count * 4 : this.count);
        for (int i = 0; i < this.count; i++) {
            double x = this.velocities[i * 3];
            double y = this.velocities[i * 3 + 1];
            double z = this.velocities[i * 3 + 2];
            addDistinct(result, new Vec3(x, y, z));
            if (!this.fromTravel) continue;
            if (x != 0.0D) addDistinct(result, new Vec3(0.0D, y, z));
            if (z != 0.0D) addDistinct(result, new Vec3(x, y, 0.0D));
            if (x != 0.0D || z != 0.0D) addDistinct(result, new Vec3(0.0D, y, 0.0D));
        }
        return result;
    }

    private void air(
            CultPlayer player,
            PacketEntitySelf self,
            double inputX,
            double inputZ,
            float sin,
            float cos,
            double y,
            float blockFriction,
            boolean climbable,
            boolean climbs,
            boolean chunkLoaded,
            boolean aboveMinY) {
        this.air(
                player,
                self,
                inputX,
                inputZ,
                sin,
                cos,
                y,
                blockFriction,
                climbable,
                climbs,
                chunkLoaded,
                aboveMinY,
                0.0D,
                0.0D);
    }

    /** LivingEntity#travelInAir from a delta of {@code boostX, y, boostZ}. */
    private void air(
            CultPlayer player,
            PacketEntitySelf self,
            double inputX,
            double inputZ,
            float sin,
            float cos,
            double y,
            float blockFriction,
            boolean climbable,
            boolean climbs,
            boolean chunkLoaded,
            boolean aboveMinY,
            double boostX,
            double boostZ) {
        // Entity#moveRelative
        double x = boostX + inputX * cos - inputZ * sin;
        double z = boostZ + inputZ * cos + inputX * sin;
        // LivingEntity#handleOnClimbable
        if (climbable) {
            x = Math.clamp(x, -0.15F, 0.15F);
            z = Math.clamp(z, -0.15F, 0.15F);
            y = Math.max(y, -0.15F);
        }
        boolean use26Dot2 = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_2);
        float airDrag = use26Dot2 ? Friction.computeModifiedFriction(0.91F, (float) self.airDragModifier) : 0.91F;
        float verticalDrag = use26Dot2 ? Friction.computeModifiedFriction(0.98F, (float) self.airDragModifier) : 0.98F;
        float friction = blockFriction * airDrag;
        this.airVertical(self, x * friction, y, z * friction, verticalDrag, chunkLoaded, aboveMinY);
        // A horizontal collision or the jump key on a climbable lifts the passenger before gravity.
        if (climbs) this.airVertical(self, x * friction, 0.2D, z * friction, verticalDrag, chunkLoaded, aboveMinY);
    }

    private void airVertical(
            PacketEntitySelf self,
            double x,
            double y,
            double z,
            float verticalDrag,
            boolean chunkLoaded,
            boolean aboveMinY) {
        Integer levitation = effectAmplifier(self, MovementEffect.LEVITATION);
        if (levitation != null) {
            this.add(x, (y + (0.05D * (levitation + 1) - y) * 0.2D) * verticalDrag, z);
            return;
        }
        this.add(x, (y - effectiveGravity(self, y)) * verticalDrag, z);
        // An unloaded chunk below the passenger on the client.
        if (!chunkLoaded) this.add(x, (aboveMinY ? -0.1D : 0.0D) * verticalDrag, z);
    }

    /** LivingEntity#travelInWater after LivingEntity#aiStep's water current and jump. */
    private void water(
            PacketEntitySelf self,
            double inputX,
            double inputZ,
            float sin,
            float cos,
            boolean sprinting,
            boolean onGround,
            boolean jump,
            float jumpPower,
            boolean climbable) {
        float slowDown = sprinting ? 0.9F : 0.8F;
        float speed = 0.02F;
        float waterWalker = (float) self.waterMovementEfficiency;
        if (!onGround) waterWalker *= 0.5F;
        if (waterWalker > 0.0F) {
            slowDown += (0.54600006F - slowDown) * waterWalker;
            speed += (movementSpeed(self, sprinting) - speed) * waterWalker;
        }
        if (effectAmplifier(self, MovementEffect.DOLPHINS_GRACE) != null) slowDown = 0.96F;
        for (int currentCase = 0; currentCase < (this.hasWaterCurrent ? 2 : 1); currentCase++) {
            double startX = currentCase == 0 ? 0.0D : this.waterCurrent.x;
            double startY = currentCase == 0 ? 0.0D : this.waterCurrent.y;
            double startZ = currentCase == 0 ? 0.0D : this.waterCurrent.z;
            // LivingEntity#aiStep zeroes a small delta before the jump.
            if (startX * startX + startZ * startZ < 9.0E-6D) {
                startX = 0.0D;
                startZ = 0.0D;
            }
            if (Math.abs(startY) < 0.003D) startY = 0.0D;
            for (int jumpCase = 0; jumpCase < (jump ? 3 : 1); jumpCase++) {
                double x = startX;
                double y = startY;
                double z = startZ;
                if (jumpCase == 1) {
                    // LivingEntity#jumpInLiquid
                    y += 0.04F;
                } else if (jumpCase == 2) {
                    // LivingEntity#jumpFromGround in shallow water
                    y = Math.max(jumpPower, y);
                    if (sprinting) {
                        x += -sin * 0.2F;
                        z += cos * 0.2F;
                    }
                }
                double gravity = effectiveGravity(self, y);
                boolean falling = y <= 0.0D;
                x += inputX * speed * cos - inputZ * speed * sin;
                z += inputZ * speed * cos + inputX * speed * sin;
                this.waterDrag(x, y, z, slowDown, gravity, falling, sprinting);
                if (climbable) this.waterDrag(x, 0.2D, z, slowDown, gravity, falling, sprinting);
            }
        }
    }

    private void waterDrag(
            double x, double y, double z, float slowDown, double gravity, boolean falling, boolean sprinting) {
        double dragged = y * 0.8F;
        this.add(x * slowDown, fluidFallingAdjusted(dragged, gravity, falling, sprinting), z * slowDown);
        // LivingEntity#jumpOutOfFluid
        this.add(x * slowDown, 0.3F, z * slowDown);
    }

    /** LivingEntity#travelInLava after LivingEntity#aiStep's lava current and jump. */
    private void lava(
            PacketEntitySelf self,
            double inputX,
            double inputZ,
            float sin,
            float cos,
            boolean sprinting,
            boolean jump,
            float jumpPower) {
        for (int currentCase = 0; currentCase < (this.hasLavaCurrent ? 2 : 1); currentCase++) {
            double startX = currentCase == 0 ? 0.0D : this.lavaCurrent.x;
            double startY = currentCase == 0 ? 0.0D : this.lavaCurrent.y;
            double startZ = currentCase == 0 ? 0.0D : this.lavaCurrent.z;
            if (startX * startX + startZ * startZ < 9.0E-6D) {
                startX = 0.0D;
                startZ = 0.0D;
            }
            if (Math.abs(startY) < 0.003D) startY = 0.0D;
            for (int jumpCase = 0; jumpCase < (jump ? 3 : 1); jumpCase++) {
                double x = startX;
                double y = startY;
                double z = startZ;
                if (jumpCase == 1) {
                    y += 0.04F;
                } else if (jumpCase == 2) {
                    y = Math.max(jumpPower, y);
                    if (sprinting) {
                        x += -sin * 0.2F;
                        z += cos * 0.2F;
                    }
                }
                double gravity = effectiveGravity(self, y);
                boolean falling = y <= 0.0D;
                x += inputX * 0.02F * cos - inputZ * 0.02F * sin;
                z += inputZ * 0.02F * cos + inputX * 0.02F * sin;
                double sink = gravity != 0.0D ? -gravity / 4.0D : 0.0D;
                // Shallow lava drags each axis apart; deep lava halves the whole delta.
                this.add(x * 0.5D, fluidFallingAdjusted(y * 0.8F, gravity, falling, sprinting) + sink, z * 0.5D);
                this.add(x * 0.5D, y * 0.5D + sink, z * 0.5D);
                this.add(x * 0.5D, 0.3F, z * 0.5D);
            }
        }
    }

    /** LivingEntity#getFluidFallingAdjustedMovement */
    private static double fluidFallingAdjusted(double y, double gravity, boolean falling, boolean sprinting) {
        if (gravity == 0.0D || sprinting) return y;
        if (falling && Math.abs(y - 0.005D) >= 0.003D && Math.abs(y - gravity / 16.0D) < 0.003D) return -0.003D;
        return y - gravity / 16.0D;
    }

    private boolean touchesFluid(CultPlayer player, FluidTags tag) {
        int minX = CultMath.floor(this.box.minX);
        int maxX = CultMath.floor(this.box.maxX);
        int minY = CultMath.floor(this.box.minY);
        int maxY = CultMath.floor(this.box.maxY);
        int minZ = CultMath.floor(this.box.minZ);
        int maxZ = CultMath.floor(this.box.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    SimFluidState fluid =
                            ClientFluidQueries.modelFluid(player.compensatedWorld.getBlockStateIdAt(x, y, z));
                    if (!fluid.isEmpty() && ClientFluidQueries.is(player, fluid, tag)) return true;
                }
            }
        }
        return false;
    }

    /**
     * AbstractBoat#isUnderWater, whose status AbstractBoat#tick takes before the boat moves: either the previous ride
     * tick's boxes or this tick's can hold it, and a first ride tick may be under water. Sets {@link #boatTop} to the
     * lowest top this tick's boxes allow.
     */
    private boolean boatMayBeUnderWater(CultPlayer player, PacketEntity boat) {
        boat.getPossibleMovementCollisionBoxes(this.vehicleBox, this.vehicleScratch);
        double height = BoundingBoxSize.getHeight(player, boat);
        this.boatTop = this.vehicleBox.minY + height;
        boolean underWater = this.previousBoat != boat
                || this.underWater(player, this.previousBoatBox, height)
                || this.underWater(player, this.vehicleBox, height);
        this.previousBoat = boat;
        this.previousBoatBox.set(this.vehicleBox);
        return underWater;
    }

    /** AbstractBoat#isUnderwater for any top {@code boat} allows: water in the band over the top rises past it. */
    private boolean underWater(CultPlayer player, SimpleCollisionBox boat, double height) {
        double top = boat.minY + height;
        double surface = top + 0.001D;
        int minX = CultMath.floor(boat.minX);
        int maxX = (int) Math.ceil(boat.maxX);
        int minY = CultMath.floor(top);
        int maxY = (int) Math.ceil(boat.maxY + 0.001D);
        int minZ = CultMath.floor(boat.minZ);
        int maxZ = (int) Math.ceil(boat.maxZ);
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    this.pos.set(x, y, z);
                    SimFluidState fluid = ClientFluidQueries.fluidAt(player.compensatedWorld, this.pos);
                    if (!fluid.isEmpty()
                            && ClientFluidQueries.is(player, fluid, FluidTags.WATER)
                            && surface < y + ClientFluidQueries.height(player.compensatedWorld, this.pos, fluid)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void add(double x, double y, double z) {
        for (int i = 0; i < this.count * 3; i += 3) {
            if (this.velocities[i] == x && this.velocities[i + 1] == y && this.velocities[i + 2] == z) return;
        }
        if (this.count * 3 == this.velocities.length) {
            double[] grown = new double[this.velocities.length * 2];
            System.arraycopy(this.velocities, 0, grown, 0, this.velocities.length);
            this.velocities = grown;
        }
        this.velocities[this.count * 3] = x;
        this.velocities[this.count * 3 + 1] = y;
        this.velocities[this.count * 3 + 2] = z;
        this.count++;
    }

    private static void addDistinct(List<Vec3> list, Vec3 velocity) {
        if (!list.contains(velocity)) list.add(velocity);
    }

    private static int inputIndex(int left, int forward) {
        return ((left + 1) * 3 + forward + 1) * 2;
    }

    /** LivingEntity#getSpeed; sprinting adds its 0.3 multiplied-total modifier. */
    private static float movementSpeed(PacketEntitySelf self, boolean sprinting) {
        return (float) (sprinting ? self.playerSpeed * (1.0D + 0.3D) : self.playerSpeed);
    }

    /** LivingEntity#getEffectiveGravity: slow falling caps it while the delta does not rise. */
    private static double effectiveGravity(PacketEntitySelf self, double y) {
        if (!self.hasGravity) return 0.0D;
        return y <= 0.0D && effectAmplifier(self, MovementEffect.SLOW_FALLING) != null
                ? Math.min(self.gravity, 0.01D)
                : self.gravity;
    }

    /** LivingEntity#getJumpBoostPower */
    private static float jumpBoostPower(PacketEntitySelf self) {
        Integer jumpBoost = effectAmplifier(self, MovementEffect.JUMP_BOOST);
        return jumpBoost == null ? 0.0F : 0.1F * (jumpBoost + 1.0F);
    }

    private static Integer effectAmplifier(PacketEntity entity, MovementEffect effect) {
        return entity.potionsMap == null ? null : entity.potionsMap.get(effect);
    }
}
