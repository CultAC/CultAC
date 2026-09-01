package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.impl.movement.GhostBlockMitigator;
import ac.grim.grimac.checks.impl.prediction.profile.MovementProfiles;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//@CheckData(name = "Phase", configName = "Phase", setback = 1, decay = 0.005)
public class Phase extends Check implements PostPredictionListener {

    public Phase(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder()
            .name("Phase")
            .stableKey("grim.prediction.phase")
            .description("Moved into a solid block during movement prediction")
            .setback(1)
            .decay(0.005)
            .build()); }

    SimpleCollisionBox oldBox = null;

    private void handleMovePlayer(ServerboundMovePlayerPacket packet) {
        NmsPacketUtil.MovePlayerData flying = NmsPacketUtil.readMovePlayer(packet, player);
        if (!flying.hasPositionChanged()) return;

        Vec3 location = flying.position();
        if (!MovementProfiles.forPlayer(player).shouldRunPhaseCheck(player)) {
            oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(location.x, location.y, location.z, 0.6f, 1.8f);
            return;
        }
        if (player.inVehicle() || player.packetStateData.lastPacketWasTeleport || player.getSetbackTeleportUtil().shouldBlockMovement()
                || player.checkManager.getSimulationProcessor().isExempt() || player.getSetbackTeleportUtil().isPendingSetback()) {
            oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(location.x, location.y, location.z, 0.6f, 1.8f);
            return;
        }

        SimpleCollisionBox newBox = GetBoundingBox.getBoundingBoxFromPosAndSize(location.x, location.y, location.z, 0.6f, 0.6f);

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        Collisions.getCollisionBoxes(player, newBox, boxes, false);

        Map<BlockPos, GhostBlockMitigator.GhostData> ghostDataMap = player.getGhostBlockMitigator().unknownStuff;
        boolean hasGhostBlocks = !ghostDataMap.isEmpty();

        for (SimpleCollisionBox box : boxes) {
            if (newBox.isIntersected(box) && !oldBox.isIntersected(box)) {
                BlockPos blockPos = BlockPos.containing((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2);
                BlockData state = player.compensatedWorld.getBlockDataAt(blockPos);
                // We don't attempt to calculate the many ways a block can be updated
                if (NmsBlockTags.isConnectingBlock(state.getMaterial())) {
                    continue;
                }

                // Simply resync if the player has ghost blocks.
                if (hasGhostBlocks) {
                    final ac.grim.grimac.manager.player.SetbackTeleportUtil setbackUtil = player.getSetbackTeleportUtil();
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

        oldBox = GetBoundingBox.getBoundingBoxFromPosAndSize(location.x, location.y, location.z, 0.6f, 1.8f);
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        handleMovePlayer(packet);
    }
}
