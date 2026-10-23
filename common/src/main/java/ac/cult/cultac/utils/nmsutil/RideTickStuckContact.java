package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;

/**
 * The stuck multipliers a passenger's Entity#rideTick can leave its first move off the vehicle, as bits over
 * {@link Collisions#PLAYER_STUCK_SPEEDS}. The ride tick's inside-block pass follows the passenger's own travel from
 * where the previous Entity#positionRider put it, or from where it mounted; Entity#positionRider then moves it without
 * one.
 */
public final class RideTickStuckContact {
    /** Every multiplier, for a ride tick whose contacts cannot be bounded. */
    public static final int ANY = (1 << Collisions.PLAYER_STUCK_SPEEDS.size()) - 1;
    // Weaving reaches the client with latency, so a touched cobweb offers both of its multipliers.
    private static final int COBWEB = 0b0011;
    private static final int SWEET_BERRY_BUSH = 0b0100;
    private static final int POWDER_SNOW = 0b1000;
    private static final int MAX_BLOCKS = 4096;
    /** Player#getFlyingSpeed's sprinting reach through LocalPlayer#modifyInput, with room to spare. */
    private static final double AIR_TRAVEL = 0.05D;
    /** Room past LivingEntity#getSpeed for the float rounding and a water current. */
    private static final double SPEED_MARGIN = 0.02D;
    /** Entity#maxUpStep without a step-height attribute. */
    private static final double STEP = 0.6D;
    /** LivingEntity#handleRelativeFrictionAndCalculateMovement climbs 0.2 before the move. */
    private static final double CLIMB = 0.25D;

    private final SimpleCollisionBox vehicleBox = new SimpleCollisionBox();
    private final SimpleCollisionBox scratch = new SimpleCollisionBox();
    private double lastSeatSlackXZ;
    private double lastSeatSlackY;
    private boolean touchedPowderSnow;

    /** Whether the last scanned ride tick's box can have touched powder snow anywhere, which Entity#baseTick keeps. */
    public boolean touchedPowderSnow() {
        return this.touchedPowderSnow;
    }

    /**
     * The multipliers the ride tick of {@code vehicle}'s passenger can have set, or {@link #ANY}. {@code priorSeat} is
     * the seat the previous ride tick ended on, null on the first; {@code inFluid} tells whether the ride tick started
     * touching a fluid.
     */
    public int scan(CultPlayer player, PacketEntity vehicle, Vec3 priorSeat, boolean inFluid) {
        double x = player.x;
        double y = player.y;
        double z = player.z;
        // The prior seat was taken at the middle of the vehicle's possible boxes the previous ride tick saw.
        double slackXZ = 0.0D;
        double slackY = 0.0D;
        if (priorSeat != null) {
            x = priorSeat.x;
            y = priorSeat.y;
            z = priorSeat.z;
            slackXZ = this.lastSeatSlackXZ;
            slackY = this.lastSeatSlackY;
        }
        // The seat this ride tick ends on is taken the same way; the client's vehicle can be anywhere in those boxes.
        vehicle.getPossibleMovementCollisionBoxes(this.vehicleBox, this.scratch);
        float vehicleWidth = BoundingBoxSize.getWidth(player, vehicle);
        float vehicleHeight = BoundingBoxSize.getHeight(player, vehicle);
        double seatSlackXZ = Math.max(
                0.0D,
                (Math.max(this.vehicleBox.maxX - this.vehicleBox.minX, this.vehicleBox.maxZ - this.vehicleBox.minZ)
                                - vehicleWidth)
                        / 2.0D);
        // A boat turns its passenger's seat with its yaw.
        if (EntityTypeUtil.isBoat(vehicle.type)) seatSlackXZ += 0.5D;
        this.lastSeatSlackXZ = seatSlackXZ;
        this.lastSeatSlackY = Math.max(0.0D, this.vehicleBox.maxY - this.vehicleBox.minY - vehicleHeight);
        this.touchedPowderSnow = true;

        // Before 1.21.4 Player#tick keeps a passenger's ground state, which only a ride tick's move sets.
        boolean grounded = player.getClientVersion().isOlderThan(ClientVersion.V_1_21_4);
        boolean jump = player.packetStateData.knownInput.jump();
        if (jump && grounded) return ANY;
        double travel = AIR_TRAVEL;
        // LivingEntity#getSpeed at a sprint, which LivingEntity#travelInWater does not exceed.
        double sprintSpeed = player.compensatedEntities.playerEntity.playerSpeed * 1.3D;
        if (inFluid) travel = Math.max(travel, sprintSpeed + SPEED_MARGIN);
        if (grounded) {
            float friction = BlockProperties.getMaterialFriction(player.compensatedWorld.getBlockStateIdAt(
                    CultMath.floor(x), CultMath.floor(y - 0.500001D), CultMath.floor(z)));
            // LivingEntity#getFrictionInfluencedSpeed
            travel = Math.max(travel, sprintSpeed * (0.21600002F / (friction * friction * friction)) + SPEED_MARGIN);
        }
        double step = grounded
                ? player.compensatedEntities.playerEntity.stepHeightAttribute == null
                        ? STEP
                        : player.compensatedEntities.playerEntity.stepHeightAttribute
                : 0.0D;
        double halfWidth = player.pose.width * player.dimensionScale / 2.0D + slackXZ + travel;
        double height = Math.max(player.getBukkitHeight(), 1.8F * player.dimensionScale);
        double rise = step + CLIMB + slackY;
        int minX = CultMath.floor(x - halfWidth);
        int maxX = CultMath.floor(x + halfWidth);
        int minY = CultMath.floor(y - AIR_TRAVEL - slackY);
        int maxY = CultMath.floor(y + height + rise);
        int minZ = CultMath.floor(z - halfWidth);
        int maxZ = CultMath.floor(z + halfWidth);
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > MAX_BLOCKS) return ANY;
        if (CheckIfChunksLoaded.isChunksUnloadedAt(player, minX, minY, minZ, maxX, maxY, maxZ)) return ANY;
        // PowderSnowBlock#entityInside sticks a living entity only with its feet in the powder snow.
        int maxFeetY = CultMath.floor(y + rise);
        minY = Math.max(minY, player.compensatedWorld.getMinHeight());
        maxY = Math.min(maxY, player.compensatedWorld.getMaxHeight() - 1);

        int contacts = 0;
        this.touchedPowderSnow = false;
        for (int blockY = minY; blockY <= maxY; blockY++) {
            for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                for (int blockX = minX; blockX <= maxX; blockX++) {
                    int state = player.compensatedWorld.getBlockStateIdAt(blockX, blockY, blockZ);
                    // LivingEntity#jumpInLiquid and fluid travel lift a jumping passenger past these bounds.
                    if (jump && !ClientFluidQueries.modelFluid(state).isEmpty()) {
                        this.touchedPowderSnow = true;
                        return ANY;
                    }
                    if (BlockFamilies.WEB.test(state)) contacts |= COBWEB;
                    else if (BlockFamilies.SWEET_BERRY_BUSH.test(state)) contacts |= SWEET_BERRY_BUSH;
                    else if (BlockFamilies.POWDER_SNOW.test(state)) {
                        // PowderSnowBlock#entityInside freezes any overlap.
                        this.touchedPowderSnow = true;
                        if (blockY <= maxFeetY) contacts |= POWDER_SNOW;
                    }
                    if (contacts == ANY && !jump) return ANY;
                }
            }
        }
        return contacts;
    }
}
