package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

@CheckData(name = "MultiBreak", stableKey = "grim.breaking.multi_break", description = "Tried to break multiple different blocks in the same movement tick", experimental = true)
public class MultiBreak extends Check implements BlockBreakListener, PostPredictionListener {
    private static final Verbose V =
            Verbose.of("face={face}, lastFace={face}, pos={mcpos}, lastPos={mcpos}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private final List<FlagData> flags = new ArrayList<>();
    private boolean hasBroken;
    private BlockFace lastFace;
    private BlockPos lastPos;

    public MultiBreak(GrimPlayer player) {
        super(player);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf()) {
            hasBroken = false;
        }

        if (blockBreak.action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) { // PE DiggingAction.CANCELLED_DIGGING
            return;
        }

        if (hasBroken && (blockBreak.face != lastFace || !blockBreak.position.equals(lastPos))) {
            final int face = VerboseTags.enumId(blockBreak.face);
            final int previousFace = VerboseTags.enumId(lastFace);
            if (!canSkipTicks()) {
                var buf = V.write(verbose()).uint(face).uint(previousFace)
                        .mcPos(blockBreak.position.getX(), blockBreak.position.getY(), blockBreak.position.getZ())
                        .mcPos(lastPos.getX(), lastPos.getY(), lastPos.getZ());
                if (flag(buf) && shouldModifyPackets()) {
                    blockBreak.cancel();
                }
            } else {
                flags.add(new FlagData(face, previousFace, blockBreak.position, lastPos));
            }
        }

        lastFace = blockBreak.face;
        lastPos = blockBreak.position;
        hasBroken = true;
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            hasBroken = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        if (!player.cameraEntity.isSelf()
                || (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick)) {
            hasBroken = false;
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (FlagData data : flags) {
                flag(V.write(verbose()).uint(data.face()).uint(data.previousFace())
                        .mcPos(data.pos().getX(), data.pos().getY(), data.pos().getZ())
                        .mcPos(data.previousPos().getX(), data.previousPos().getY(), data.previousPos().getZ()));
            }
        }

        flags.clear();
    }

    private boolean canSkipTicks() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                && !(player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2));
    }

    private record FlagData(int face, int previousFace, BlockPos pos, BlockPos previousPos) {}
}
