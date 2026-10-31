package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@BedrockSupported
public class Phase extends Check implements PostPredictionListener {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("Phase")
            .stableKey("cult.prediction.phase")
            .description("Moved into a solid block during movement prediction")
            .setback(1)
            .decay(0.005)
            .build();
    // Temporary Bedrock penetration approximation, inset on every face.
    private static final double BEDROCK_PENETRATION_INSET = 0.001D;

    public Phase(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
    }

    SimpleCollisionBox oldBox = null;
    // Read only within one checkMovement call; never stored.
    private final SimpleCollisionBox newBoxScratch = new SimpleCollisionBox();

    private void handleMovePlayer(ServerboundMovePlayer packet) {
        // Geyser projections must neither check movement nor replace auth-input history.
        if (player.isBedrockMovement()) return;
        ServerboundMovePlayer flying = packet;
        if (!flying.hasPosition()) return;

        checkMovement(
                new Vec3(flying.x(), flying.y(), flying.z()), false, player.packetStateData.lastPacketWasTeleport);
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        var result = complete.getPredictionResult();
        if (result == null) return;
        if (result.isTeleport()) {
            if (result.getSetBackData() != null) {
                oldBox = boxAt(result.getSetBackData().getLocation(), 1.8f, player.isBedrockMovement());
            }
            return;
        }
        var context = result.getSimulationContext();
        if (!player.isBedrockMovement()) {
            // Before 26.3, pistons and later-ticking shulkers move the player after its report.
            // The runner carries those moves into this travel's physical start. The previous
            // report can still be outside a block the player already overlaps after that pass.
            if (context != null) oldBox = boxAt(context.getStart(), 1.8f, false);
            return;
        }
        if (context == null || !context.hasTrustedAuthoredInput() || context.getBedrockInput() == null) return;

        checkMovement(
                context.getEnd(),
                true,
                complete.isTeleport() || context.isBedrockTeleportTick() || complete.isExempt());
    }

    private void checkMovement(Vec3 location, boolean bedrock, boolean teleportOrExempt) {
        SimpleCollisionBox historyBox = boxAt(location, 1.8f, bedrock);
        if (bedrock && oldBox == null) {
            oldBox = historyBox;
            return;
        }
        if (player.inVehicle()
                || teleportOrExempt
                || player.getSetbackTeleportUtil().shouldBlockMovement()
                || player.checkManager.getSimulationProcessor().isExempt()
                || player.getSetbackTeleportUtil().isPendingSetback()) {
            oldBox = historyBox;
            return;
        }

        // Entity.refreshDimensions resizes the client box in place before travel.
        // Compare movement against that resized history, including after shrinking.
        if (!bedrock && oldBox != null) {
            oldBox = boxAt(
                    new Vec3((oldBox.minX + oldBox.maxX) / 2.0, oldBox.minY, (oldBox.minZ + oldBox.maxZ) / 2.0),
                    1.8f,
                    false);
        }
        SimpleCollisionBox newBox = boxAt(newBoxScratch, location, 0.6f, bedrock);

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        SimpleCollisionBox savedBox = player.boundingBox;
        try {
            // Bedrock callbacks run before position commit; query dynamic shapes at the destination.
            if (bedrock) player.boundingBox = boxAt(location, 1.8f, false);
            Collisions.getCollisionBoxes(player, newBox, boxes, false, bedrock ? location.y : player.y);
        } finally {
            player.boundingBox = savedBox;
        }

        Map<BlockPos, GhostBlockMitigator.GhostData> ghostDataMap = player.getGhostBlockMitigator().unknownStuff;
        boolean hasGhostBlocks = !ghostDataMap.isEmpty();

        for (SimpleCollisionBox box : boxes) {
            if (intersects(newBox, box, bedrock) && !intersects(oldBox, box, bedrock)) {
                if (!bedrock && isMovingBlock(player, box)) continue;
                BlockPos blockPos = BlockPos.containing(
                        (box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2);
                int state = player.compensatedWorld.getBlockStateIdAt(blockPos);
                // We don't attempt to calculate the many ways a block can be updated
                if (ClientBlockProperties.isConnectingBlock(state)) {
                    continue;
                }

                // Simply resync if the player has ghost blocks.
                if (hasGhostBlocks) {
                    final SetbackTeleportUtil setbackUtil = player.getSetbackTeleportUtil();
                    setbackUtil.executeForceResync("phase");
                } else if (flagWithSetback()) {
                    final String playerName = player.getName();
                    LogUtil.info(playerName + "'s box is " + newBox);
                    LogUtil.info(playerName + "'s box was " + oldBox);
                    LogUtil.info(playerName + " | Block box is " + box);
                }
                return;
            }
        }

        oldBox = historyBox;
    }

    /**
     * Whether {@code box} is the collision of a block a piston is moving: the block the scan took it from is a
     * MOVING_PISTON. PistonMovingBlockEntity#moveEntityByPiston moves entities with NOCLIP for blocks moving the same way,
     * as fixEntityWithinPistonBase does pushing an entity out of a retracting base into the blocks it pulls, so a piston
     * can leave the player inside one. Simulation checks movement against them; every other block stays Phase's.
     */
    public static boolean isMovingBlock(CultPlayer player, SimpleCollisionBox box) {
        if (!(box instanceof VoxelShapeCollisionBox collider) || !collider.isTranslated()) return false;
        BlockPos source = new BlockPos((int) collider.offsetX(), (int) collider.offsetY(), (int) collider.offsetZ());
        return BlockIds.is(player.compensatedWorld.getBlockStateIdAt(source), BlockIds.MOVING_PISTON);
    }

    private SimpleCollisionBox boxAt(Vec3 location, float height, boolean bedrock) {
        return boxAt(new SimpleCollisionBox(), location, height, bedrock);
    }

    private SimpleCollisionBox boxAt(SimpleCollisionBox out, Vec3 location, float height, boolean bedrock) {
        float scale = player.isBedrockMovement() ? 1.0f : player.getScale();
        SimpleCollisionBox box = GetBoundingBox.getBoundingBoxFromPosAndSize(
                out, location.x, location.y, location.z, 0.6f * scale, height * scale);
        return bedrock ? box.expand(-BEDROCK_PENETRATION_INSET) : box;
    }

    private static boolean intersects(SimpleCollisionBox first, SimpleCollisionBox second, boolean bedrock) {
        if (!bedrock) return first.isIntersected(second);
        // The inset supplies the entire threshold; do not add COLLISION_EPSILON.
        return first.maxX > second.minX
                && first.minX < second.maxX
                && first.maxY > second.minY
                && first.minY < second.maxY
                && first.maxZ > second.minZ
                && first.minZ < second.maxZ;
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        handleMovePlayer(packet);
    }
}
