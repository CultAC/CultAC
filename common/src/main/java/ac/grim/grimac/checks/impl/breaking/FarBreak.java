package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.math.VectorUtils;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

@CheckData(name = "FarBreak", stableKey = "grim.breaking.far_break", description = "Breaking blocks too far away", experimental = true)
public class FarBreak extends Check implements BlockBreakListener {
    private static final Verbose V = Verbose.of("distance={f64:%.2f}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());


    private boolean didLastMovementIncludePosition;

    public FarBreak(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        didLastMovementIncludePosition = packet.hasPosition();
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf() || player.inVehicle() || blockBreak.action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) // PE DiggingAction.CANCELLED_DIGGING
            return; // falses

        double min = Double.MAX_VALUE;
        for (double d : player.getPossibleEyeHeights()) {
            SimpleCollisionBox box = new SimpleCollisionBox(blockBreak.position);
            Vector3dm best = VectorUtils.cutBoxToVector(player.x, player.y + d, player.z, box);
            min = Math.min(min, best.distanceSquared(player.x, player.y + d, player.z));
        }

        double maxReach = player.compensatedEntities.getSelf().getBlockInteractionRange();
        if (didLastMovementIncludePosition || canSkipTicks()) {
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

    private boolean canSkipTicks() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                && !(player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2));
    }
}
