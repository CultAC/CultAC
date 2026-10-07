package ac.cult.cultac.checks.impl.elytra;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(name = "ElytraC", stableKey = "cult.elytra.too_frequent", description = "Started gliding too frequently")
public class ElytraC extends Check implements PostPredictionListener, DecodedPacketReceiveListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());

    private boolean glideThisTick, glideLastTick, setback;
    private int flags;
    public boolean exempt;

    public ElytraC(CultPlayer player) {
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

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (!isApplicable()) return;
        if (!player.cameraEntity.isSelf()) {
            glideThisTick = glideLastTick = false;
        }

        if (packet.action() != PlayerCommandAction.START_FLYING_WITH_ELYTRA) return;
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
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
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
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
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
