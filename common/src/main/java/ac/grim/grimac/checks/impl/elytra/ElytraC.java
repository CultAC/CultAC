package ac.grim.grimac.checks.impl.elytra;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.DecodedPacketReceiveListener;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "ElytraC", stableKey = "grim.elytra.too_frequent", description = "Started gliding too frequently")
public class ElytraC extends Check implements PostPredictionListener, DecodedPacketReceiveListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private boolean glideThisTick, glideLastTick, setback;
    private int flags;
    public boolean exempt;

    public ElytraC(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);
    }

    @Override
    public void onDecodedPacketReceive(PacketReceiveEvent event) {
        if (!player.cameraEntity.isSelf()) {
            glideThisTick = glideLastTick = false;
        }
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
        if (!isApplicable()) return;
        if (!player.cameraEntity.isSelf()) {
            glideThisTick = glideLastTick = false;
        }

        if (NmsPacketUtil.readPlayerCommand(packet).action() != NmsPacketUtil.PlayerCommandAction.START_FLYING_WITH_ELYTRA) return;
        if (exempt) return;

        if (glideThisTick || glideLastTick) {
            if (canSkipTicks()) {
                flags++;
            } else if (flag()) {
                setback = true;
                if (shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                    resyncPose();
                }
            }
        }

        glideThisTick = true;
    }

    // isTickPacket: movement packets rotate unless they answered a teleport
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!isApplicable()) return;
        if (!player.cameraEntity.isSelf()) {
            glideThisTick = glideLastTick = false;
        }

        if (!player.packetStateData.lastPacketWasTeleport) {
            glideLastTick = glideThisTick;
            glideThisTick = exempt = false;
        }
    }

    // isTickPacket: tick end rotates for 1.21.2+ clients when no movement arrived this client tick
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        if (!isApplicable()) return;
        if (!player.cameraEntity.isSelf()) {
            glideThisTick = glideLastTick = false;
        }

        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick) {
            glideLastTick = glideThisTick;
            glideThisTick = exempt = false;
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!isApplicable()) return;
        if (canSkipTicks()) {
            if (player.isTickingReliablyFor(3)) {
                for (; flags > 0; flags--) {
                    flag();
                }
            }

            flags = 0;
            setback = false;
        }

        if (setback) {
            setback = false;
            setbackIfAboveSetbackVL();
        }
    }

    private boolean canSkipTicks() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)
                && !(player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2));
    }

    private void resyncPose() {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14) && player.platformPlayer != null) {
            player.platformPlayer.setSneaking(!player.platformPlayer.isSneaking());
        }
    }
}
