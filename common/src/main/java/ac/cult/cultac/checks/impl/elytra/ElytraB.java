package ac.cult.cultac.checks.impl.elytra;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
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

    private boolean glide;
    private boolean setback;

    public ElytraB(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() != PlayerCommandAction.START_FLYING_WITH_ELYTRA) return;

        // LocalPlayer#aiStep sends it only when the keys of the tick before, not the last PLAYER_INPUT, lacked jump.
        boolean jumpHeld = player.isBedrockMovement()
                ? player.packetStateData.knownInput.jump()
                : player.packetStateData.lastTickJump;
        if (jumpHeld) {
            if (flag(V.write(verbose()).bool(true))) {
                setback = true;
                if (shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                    player.resyncPose();
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
        if (glide
                && !player.packetStateData.knownInput.jump()
                && flag(V.write(verbose()).bool(false))) {
            setback = true;
        }

        glide = false;
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (setback) {
            setback = false;
            setbackIfAboveSetbackVL();
        }
    }
}
