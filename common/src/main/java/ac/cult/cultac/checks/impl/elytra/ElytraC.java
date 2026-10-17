package ac.cult.cultac.checks.impl.elytra;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(name = "ElytraC", stableKey = "cult.elytra.too_frequent", description = "Started gliding too frequently")
public class ElytraC extends Check implements PostPredictionListener, DecodedPacketReceiveListener {
    private boolean glideThisTick, glideLastTick, setback;
    public boolean exempt;

    public ElytraC(CultPlayer player) {
        super(player);
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
        if (!player.cameraEntity.isSelf()) {
            glideThisTick = glideLastTick = false;
        }

        if (packet.action() != PlayerCommandAction.START_FLYING_WITH_ELYTRA) return;
        if (exempt) return;

        if (glideThisTick || glideLastTick) {
            if (flag()) {
                setback = true;
                if (shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                    player.resyncPose();
                }
            }
        }

        glideThisTick = true;
    }

    // isTickPacket: movement packets rotate unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
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
        if (!player.cameraEntity.isSelf()) {
            glideThisTick = glideLastTick = false;
        }

        if (!player.packetStateData.receivedMovementThisClientTick) {
            glideLastTick = glideThisTick;
            glideThisTick = exempt = false;
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (setback) {
            setback = false;
            setbackIfAboveSetbackVL();
        }
    }
}
