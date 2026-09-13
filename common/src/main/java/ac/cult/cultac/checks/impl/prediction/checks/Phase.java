package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NmsBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@BedrockSupported
public class Phase extends Check implements PostPredictionListener {
    // Temporary Bedrock penetration approximation, inset on every face.
    private static final double BEDROCK_PENETRATION_INSET = 0.001D;

    public Phase(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder()
            .name("Phase")
            .stableKey("cult.prediction.phase")
            .description("Moved into a solid block during movement prediction")
            .setback(1)
            .decay(0.005)
            .build()); }

    SimpleCollisionBox oldBox = null;

    private void handleMovePlayer(ServerboundMovePlayerPacket packet) {
        // Geyser projections must neither check movement nor replace auth-input history.
        if (player.isBedrockMovement()) return;
        NmsPacketUtil.MovePlayerData flying = NmsPacketUtil.readMovePlayer(packet, player);
        if (!flying.hasPositionChanged()) return;

        checkMovement(flying.position(), false, player.packetStateData.lastPacketWasTeleport);
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (!player.isBedrockMovement() || complete.getPredictionResult() == null) return;
        var context = complete.getPredictionResult().getSimulationContext();
        if (context == null || !context.hasTrustedAuthoredInput() || context.getBedrockInput() == null) return;

        checkMovement(context.getEnd(), true,
                complete.isTeleport() || context.isBedrockTeleportTick() || complete.isExempt());
    }

    private void checkMovement(Vec3 location, boolean bedrock, boolean teleportOrExempt) {
        SimpleCollisionBox historyBox = boxAt(location, 1.8f, bedrock);
        if ((bedrock && oldBox == null) || !MovementProfiles.forPlayer(player).shouldRunPhaseCheck(player)) {
            oldBox = historyBox;
            return;
        }
        if (player.inVehicle() || teleportOrExempt || player.getSetbackTeleportUtil().shouldBlockMovement()
                || player.checkManager.getSimulationProcessor().isExempt() || player.getSetbackTeleportUtil().isPendingSetback()) {
            oldBox = historyBox;
            return;
        }

        SimpleCollisionBox newBox = boxAt(location, 0.6f, bedrock);

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
                BlockPos blockPos = BlockPos.containing((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2);
                BlockData state = player.compensatedWorld.getBlockDataAt(blockPos);
                // We don't attempt to calculate the many ways a block can be updated
                if (NmsBlockTags.isConnectingBlock(state.getMaterial())) {
                    continue;
                }

                // Simply resync if the player has ghost blocks.
                if (hasGhostBlocks) {
                    final ac.cult.cultac.manager.player.SetbackTeleportUtil setbackUtil = player.getSetbackTeleportUtil();
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

    private static SimpleCollisionBox boxAt(Vec3 location, float height, boolean bedrock) {
        SimpleCollisionBox box = GetBoundingBox.getBoundingBoxFromPosAndSize(location.x, location.y, location.z, 0.6f, height);
        return bedrock ? box.expand(-BEDROCK_PENETRATION_INSET) : box;
    }

    private static boolean intersects(SimpleCollisionBox first, SimpleCollisionBox second, boolean bedrock) {
        if (!bedrock) return first.isIntersected(second);
        // The inset supplies the entire threshold; do not add COLLISION_EPSILON.
        return first.maxX > second.minX && first.minX < second.maxX
                && first.maxY > second.minY && first.minY < second.maxY
                && first.maxZ > second.minZ && first.minZ < second.maxZ;
    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        handleMovePlayer(packet);
    }
}
