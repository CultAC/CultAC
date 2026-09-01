package ac.grim.grimac.checks.impl.elytra;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
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
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "ElytraB", stableKey = "grim.elytra.no_jump", description = "Started gliding without jumping")
public class ElytraB extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("[no release|no jump]");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private boolean glide;
    private boolean setback;

    public ElytraB(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return supportsEndTick();
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
        if (!isApplicable()) return;
        if (NmsPacketUtil.readPlayerCommand(packet).action() != NmsPacketUtil.PlayerCommandAction.START_FLYING_WITH_ELYTRA) return;

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
    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        onUpdate();
    }

    // isUpdate: client tick end
    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        onUpdate();
    }

    // isUpdate: transaction pong
    @GrimPacketHandler
    public void onPong(PacketReceiveEvent event, GrimPlayer player, ServerboundPongPacket packet) {
        onUpdate();
    }

    private void onUpdate() {
        if (!isApplicable()) return;
        if (glide && !player.packetStateData.knownInput.jump() && flag(V.write(verbose()).bool(false))) {
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
