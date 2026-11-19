package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.WaterCurrent;
import org.jetbrains.annotations.Nullable;

public class PacketEntityRideable extends PacketEntity {

    public boolean hasSaddle = false;
    public final RideableBoostState boost = new RideableBoostState();

    /** Pig#getRiddenSpeed and Strider#getRiddenSpeed read movementSpeed's value as a double. */
    public double movementSpeedAttribute = 0.1D;

    public final ClientMovementSpeed movementSpeed;
    public float flyingSpeedAttribute = 0.1f;

    /** Where this tick's Entity#baseTick refreshed fluids and applied their currents. */
    private @Nullable Vec3 baseTickPosition;

    private int baseTickClientTick = Integer.MIN_VALUE;

    public PacketEntityRideable(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
        if (type == EntityTypeIds.PIG) {
            // MCP-Reborn Pig#createAttributes defines MOVEMENT_SPEED as 0.25.
            movementSpeedAttribute = 0.25f;
        } else if (type == EntityTypeIds.STRIDER) {
            // MCP-Reborn Strider#createAttributes defines MOVEMENT_SPEED as 0.175.
            movementSpeedAttribute = player.isBedrockMovement() ? 0.175D : (double) 0.175F;
        }
        movementSpeed = new ClientMovementSpeed(movementSpeedAttribute);
        flyingSpeedAttribute = (float) movementSpeedAttribute;
    }

    @Override
    public ClientMovementSpeed riddenMovementSpeed() {
        return movementSpeed;
    }

    @Override
    public void refreshRiddenMovementSpeed() {
        movementSpeedAttribute = movementSpeed.value();
    }

    @Override
    public void onMovement(CultPlayer player) {
        boolean commonInterpolation = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
        if (!commonInterpolation) baseTick(player);
        super.onMovement(player);
        if (commonInterpolation) baseTick(player);
        tickRemoteControlledBoost(player);
    }

    @Override
    public void interpolateBeforeLocalTravel(CultPlayer player) {
        boolean commonInterpolation = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
        if (!commonInterpolation) baseTick(player);
        super.interpolateBeforeLocalTravel(player);
        if (commonInterpolation) baseTick(player);
    }

    /**
     * Entity#baseTick, once a client tick. Through 26.2 it runs before LivingEntity#aiStep's interpolation; from 26.3
     * Entity#commonTick interpolates first.
     */
    private void baseTick(CultPlayer player) {
        if (player.isBedrockMovement() || baseTickClientTick == player.packetStateData.acceptedClientTick) return;
        baseTickClientTick = player.packetStateData.acceptedClientTick;
        baseTickPosition = clientPhysicalPosition;
        if (baseTickPosition != null) onBaseTick(player, baseTickPosition);
    }

    /** The state this type's own tick updates around its Entity#baseTick at {@code at}. */
    protected void onBaseTick(CultPlayer player, Vec3 at) {}

    /**
     * Entity#baseTick's water then lava current on a tick the client does not move this root: updateFluidInteraction
     * pushes every entity, controlled or not.
     */
    public Vec3 withBaseTickCurrents(CultPlayer player, Vec3 velocity) {
        if (baseTickPosition == null || baseTickClientTick != player.packetStateData.acceptedClientTick)
            return velocity;
        var box = GetBoundingBox.getPacketEntityBoundingBox(
                player, baseTickPosition.x, baseTickPosition.y, baseTickPosition.z, this);
        Vec3 water = WaterCurrent.calculateNonPlayerWaterCurrent(player, box, velocity);
        if (water != null) velocity = velocity.add(water);
        Vec3 lava = WaterCurrent.calculateNonPlayerLavaCurrent(player, box, velocity);
        return lava == null ? velocity : velocity.add(lava);
    }

    /**
     * LivingEntity#aiStep calls travelRidden for any controlling player, and Pig/Strider#tickRidden advance the boost
     * there before travelRidden tests canSimulateMovement. So this client advances the boost while a remote player
     * steers the mount. The local controller's ticks are counted from its Rot -> MoveVehicle instead.
     */
    private void tickRemoteControlledBoost(CultPlayer player) {
        if (player.isBedrockMovement() || !actionAlive || !hasSaddle || passengers.isEmpty()) return;
        // Pig/Strider#getControllingPassenger: the first passenger, if a player holding the steering item.
        var steering = type == EntityTypeIds.PIG ? ItemTypes.CARROT_ON_A_STICK : ItemTypes.WARPED_FUNGUS_ON_A_STICK;
        if (passengers.getFirst() instanceof PacketEntityPlayer controller
                && controller.actionPresent
                && controller.isHolding(steering)) {
            boost.tick(player.packetStateData.acceptedClientTick);
        }
    }
}
