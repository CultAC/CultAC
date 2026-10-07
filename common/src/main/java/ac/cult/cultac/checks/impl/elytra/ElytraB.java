package ac.cult.cultac.checks.impl.elytra;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(name = "ElytraB", stableKey = "cult.elytra.no_jump", description = "Started gliding without jumping")
public class ElytraB extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("[no release|no jump]");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());

    private boolean glide;
    private boolean setback;

    public ElytraB(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return supportsEndTick();
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (!isApplicable()) return;
        if (packet.action() != PlayerCommandAction.START_FLYING_WITH_ELYTRA) return;

        if (player.packetStateData.knownInput.jump()) {
            if (flag(V.write(verbose()).bool(true))) {
                setback = true;
                if (shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                    resyncPose();
                }
            }
        } else {
            glide = true;
        }
    }

    // isUpdate: any flying packet
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        onUpdate();
    }

    // isUpdate: client tick end
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        onUpdate();
    }

    // isUpdate: transaction pong
    @CultPacketHandler
    public void onPong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        onUpdate();
    }

    private void onUpdate() {
        if (!isApplicable()) return;
        if (glide
                && !player.packetStateData.knownInput.jump()
                && flag(V.write(verbose()).bool(false))) {
            setback = true;
        }

        glide = false;
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!isApplicable()) return;
        if (setback) {
            setback = false;
            setbackIfAboveSetbackVL();
        }
    }

    private boolean supportsEndTick() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }

    private void resyncPose() {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14) && player.platformPlayer != null) {
            player.platformPlayer.setSneaking(!player.platformPlayer.isSneaking());
        }
    }
}
