package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Above;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public class PacketEntityStrider extends PacketEntityRideable {
    private boolean suffocating;
    private ClientFluidHeights fluidHeights = ClientFluidHeights.EMPTY;
    // LivingEntity#walkAnimation: WalkAnimationState's speed, its position before scaling, and the positionScale its
    // last update set.
    private float walkSpeed = 0.0F;
    private float walkPosition = 0.0F;
    private float walkPositionScale = 1.0F;
    // The client tick whose travel the local player last ran for this root, where the root stood as that tick began,
    // and the report position and block-entity shove of a 26.3 report sent after that shove.
    private int localTick = Integer.MIN_VALUE;
    private @Nullable Vec3 localTickStart;
    private @Nullable Vec3 localShovedTo;
    private Vec3 localShove = Vec3.ZERO;

    public PacketEntityStrider(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
    }

    /** The client tick of a strider no local travel moves: its interpolation step, then its walk animation. */
    @Override
    public void onMovement(CultPlayer player) {
        Vec3 tickStart = clientPhysicalPosition;
        super.onMovement(player);
        if (!player.isBedrockMovement()) calculateWalkAnimation(tickStart, clientPhysicalPosition);
    }

    /** Every travel the local player runs for this root first steps the root's interpolation here. */
    @Override
    public void interpolateBeforeLocalTravel(CultPlayer player) {
        int tick = player.packetStateData.acceptedClientTick;
        if (localTick != tick) {
            localTick = tick;
            localTickStart = clientPhysicalPosition;
            localShovedTo = null;
        }
        super.interpolateBeforeLocalTravel(player);
    }

    /**
     * From 26.3 the report of this tick's travel, at {@code reported}, follows the block-entity pass, which shoved the
     * root by {@code shove} after LivingEntity#aiStep updated its walk animation.
     */
    public void shovedBeforeReport(CultPlayer player, Vec3 reported, Vec3 shove) {
        if (localTick != player.packetStateData.acceptedClientTick) return;
        localShovedTo = reported;
        localShove = shove;
    }

    /**
     * The walk animation update of a client tick in which the local player's travel moved this root, from where the
     * tick began to where travelRidden left the root. A tick that moved it by no travel leaves it where it was.
     */
    public void calculateLocalWalkAnimation(CultPlayer player) {
        Vec3 end = clientPhysicalPosition;
        if (localTick != player.packetStateData.acceptedClientTick) {
            calculateWalkAnimation(end, end);
            return;
        }
        if (localShovedTo != null && localShovedTo.equals(end)) end = end.subtract(localShove);
        calculateWalkAnimation(localTickStart, end);
    }

    /**
     * LivingEntity#calculateEntityAnimation at the end of LivingEntity#aiStep on the client.
     * Strider#omnidirectionalAirMover is false, so the distance is horizontal; a passenger or a strider that is not alive
     * stops the animation.
     */
    private void calculateWalkAnimation(@Nullable Vec3 from, @Nullable Vec3 to) {
        if (riding != null || !actionAlive) {
            walkSpeed = 0.0F;
            walkPosition = 0.0F;
            return;
        }
        double dx = from == null || to == null ? 0.0D : to.x - from.x;
        double dz = from == null || to == null ? 0.0D : to.z - from.z;
        // LivingEntity#updateWalkAnimation, then WalkAnimationState#update(target, 0.4F, isBaby() ? 3 : 1).
        float target = Math.min((float) Math.sqrt(dx * dx + dz * dz) * 4.0F, 1.0F);
        walkSpeed += (target - walkSpeed) * 0.4F;
        walkPosition += walkSpeed;
        walkPositionScale = isBaby ? 3.0F : 1.0F;
    }

    /** ClientPacketListener's data assignment does not call Strider#setSuffocating or change the attribute. */
    public void onSuffocatingMetadata(boolean cold) {
        suffocating = cold;
    }

    public boolean isSuffocating() {
        return suffocating;
    }

    @Override
    protected void onBaseTick(CultPlayer player, Vec3 at) {
        if (!noAI) {
            // Strider#tick reads the previous Entity#baseTick fluid cache, then writes the flag AND modifier.
            MainSupportingBlockData support = clientSupport(player);
            boolean warm = isWarmBlock(
                            player, player.compensatedWorld.getBlockStateIdAt(BlockPos.containing(at.x, at.y, at.z)))
                    || isWarmBlock(player, BlockProperties.getOnPos(player, support, at))
                    || fluidHeights.lavaHeight(player) > 0.0D;
            PacketEntityStrider parent = riding instanceof PacketEntityStrider strider ? strider : null;
            // 26.1 changes cold-parent propagation to warm-parent inheritance.
            suffocating = player.getClientVersion().isOlderThan(ClientVersion.V_26_1)
                    ? !warm || parent != null && parent.isSuffocating()
                    : !warm && (parent == null || parent.isSuffocating());
            movementSpeed.setStriderSuffocating(suffocating);
            refreshRiddenMovementSpeed();
        }
        // Entity#baseTick refreshes fluids before LivingEntity#aiStep's interpolation through 26.2.
        var body = GetBoundingBox.getBoundingBoxFromPosAndSize(
                at.x, at.y, at.z, BoundingBoxSize.getWidth(player, this), BoundingBoxSize.getHeight(player, this));
        fluidHeights = ClientFluidHeights.capture(player, body);
    }

    /** Strider#floatStrider's test of Entity#isInLava, the fluid cache from this tick's Entity#baseTick. */
    public boolean isInLavaAfterBaseTick(CultPlayer player) {
        return fluidHeights.lavaHeight(player) > 0.0D;
    }

    /**
     * Strider#floatStrider's surface branch: above the 0.5-high liquid column of its block, with no lava over it, the
     * strider is set on the ground instead of buoyed.
     */
    public boolean floatsOnLavaSurface(CultPlayer player) {
        return clientPhysicalPosition != null && floatsOnLavaSurface(player, clientPhysicalPosition);
    }

    /** {@link #floatsOnLavaSurface(CultPlayer)} for a strider at {@code at}. */
    public static boolean floatsOnLavaSurface(CultPlayer player, Vec3 at) {
        return Above.isAbove(at.y)
                && !FluidTags.LAVA.test(ClientFluidQueries.fluidAt(
                        player.compensatedWorld, BlockPos.containing(at.x, at.y, at.z).above()));
    }

    /** Commit only the fluid refresh reached by the selected travel or block-entity pass. */
    public void commitFluidHeights(ClientFluidHeights heights) {
        if (heights != null) fluidHeights = heights;
    }

    private static boolean isWarmBlock(CultPlayer player, int state) {
        return ClientFluidQueries.clientTags(player)
                .blocks()
                .getOrDefault("minecraft:strider_warm_blocks", List.of())
                .contains(DataTables.defaults().registry().block(state).key());
    }

    public void handleDamageEvent() {
        // MCP-Reborn LivingEntity#handleDamageEvent writes walkAnimation.speed directly.
        walkSpeed = 1.5F;
    }

    /**
     * Strider#getPassengerAttachmentPoint's client-side rise of the passenger point, before
     * LivingEntity#getPassengerRidingPosition's scale: 0.12 * cos(position * 1.5) * 2 * min(0.25, speed) of the walk
     * animation.
     */
    public float passengerWalkBob(ClientVersion version) {
        float speed = Math.min(0.25F, walkSpeed);
        float position = walkPosition * walkPositionScale;
        return 0.12F * VanillaMath.cos(version, position * 1.5F) * 2.0F * speed;
    }
}
