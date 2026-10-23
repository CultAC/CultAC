package ac.cult.cultac.checks.impl.breaking;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "FarBreak",
        stableKey = "cult.breaking.far_break",
        description = "Breaking blocks too far away",
        experimental = true)
public class FarBreak extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("distance={f64:%.2f}");

    private boolean didLastMovementIncludePosition;

    public FarBreak(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        didLastMovementIncludePosition = packet.hasPosition();
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf()
                || player.inVehicle()
                || blockBreak.action == PlayerAction.ABORT_DESTROY_BLOCK) return; // falses

        double min = Double.MAX_VALUE;
        for (double d : player.getPossibleEyeHeights()) {
            SimpleCollisionBox box = new SimpleCollisionBox(blockBreak.position);
            Vector3dm best = VectorUtils.cutBoxToVector(player.x, player.y + d, player.z, box);
            min = Math.min(min, best.distanceSquared(player.x, player.y + d, player.z));
        }

        double maxReach = player.compensatedEntities.getSelf().getBlockInteractionRange();
        if (didLastMovementIncludePosition) {
            double threshold = player.getMovementThreshold();
            maxReach += Math.hypot(threshold, threshold);
        }

        if (min > maxReach * maxReach) {
            double distance = Math.sqrt(min);
            if (flag(V.write(verbose()).f64(distance)) && shouldModifyPackets()) {
                blockBreak.cancel();
            }
        }
    }
}
