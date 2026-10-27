package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.ClientFluidHeights;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;

public class PacketEntityStrider extends PacketEntityRideable {
    private boolean suffocating;
    private ClientFluidHeights fluidHeights = ClientFluidHeights.EMPTY;
    private int stateTick = Integer.MIN_VALUE;
    private float walkAnimationSpeed = 0.0F;
    private float walkAnimationPosition = 0.0F;

    public PacketEntityStrider(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
    }

    /** ClientPacketListener's data assignment does not call Strider#setSuffocating or change the attribute. */
    public void onSuffocatingMetadata(boolean cold) {
        suffocating = cold;
    }

    public boolean isSuffocating() {
        return suffocating;
    }

    @Override
    public void onMovement(CultPlayer player) {
        boolean commonInterpolation = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
        if (!commonInterpolation) tickStriderState(player);
        super.onMovement(player);
        if (commonInterpolation) tickStriderState(player);
    }

    @Override
    public void interpolateBeforeLocalTravel(CultPlayer player) {
        boolean commonInterpolation = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
        if (!commonInterpolation) tickStriderState(player);
        super.interpolateBeforeLocalTravel(player);
        if (commonInterpolation) tickStriderState(player);
    }

    private void tickStriderState(CultPlayer player) {
        if (player.isBedrockMovement() || stateTick == player.packetStateData.acceptedClientTick) return;
        stateTick = player.packetStateData.acceptedClientTick;
        Vec3 at = clientPhysicalPosition;
        if (at == null) return;
        if (!noAI) {
            // Strider#tick reads the previous Entity#baseTick fluid cache, then writes the flag AND modifier.
            MainSupportingBlockData support = blockEntityState == null
                    ? new MainSupportingBlockData(null, false)
                    : blockEntityState.support();
            var last = player.checkManager.getSimulationProcessor().getLastPrediction();
            if (blockEntityState == null && last != null && last.getSimulationContext().getVehicle() == this)
                support = last.getSimulationContext().getWorldData().getMainSupportingBlockPos();
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

    public void updateWalkAnimation(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        float targetSpeed = Math.min((float) Math.sqrt(dx * dx + dz * dz) * 4.0F, 1.0F);
        walkAnimationSpeed += (targetSpeed - walkAnimationSpeed) * 0.4F;
        walkAnimationPosition += walkAnimationSpeed;
    }

    public void handleDamageEvent() {
        // MCP-Reborn LivingEntity#handleDamageEvent writes walkAnimation.speed directly.
        walkAnimationSpeed = 1.5F;
    }

    public double getPassengerAttachmentYOffset() {
        float speed = Math.min(0.25F, walkAnimationSpeed);
        float position = walkAnimationPosition * (isBaby ? 3.0F : 1.0F);
        return 0.12F * VanillaMath.cos(position * 1.5F) * 2.0F * speed;
    }
}
