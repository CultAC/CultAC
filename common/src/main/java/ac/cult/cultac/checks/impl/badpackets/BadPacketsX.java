package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "BadPacketsX", stableKey = "cult.badpackets.extra_input_actions", description = "Sent duplicate sneak or sprint input actions before the next movement packet", experimental = true)
public class BadPacketsX extends Check implements PostPredictionListener, DecodedPacketReceiveListener {
    private boolean sprint;
    private boolean sneak;
    private int flags;

    public BadPacketsX(CultPlayer player) {
        super(player);
    }

    @Override
    public void onDecodedPacketReceive(PacketReceiveEvent event) {
        if (!player.cameraEntity.isSelf()) {
            sprint = sneak = false;
        }
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!player.canSkipTicks()) {
            if (flags > 0) {
                setbackIfAboveSetbackVL();
            }

            flags = 0;
            return;
        }

        if (player.isTickingReliablyFor(3)) {
            for (; flags > 0; flags--) {
                flagWithSetback();
            }
        }

        flags = 0;
    }

    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {
        if (!player.cameraEntity.isSelf()) {
            sprint = sneak = false;
            return;
        }


        switch (NmsPacketUtil.readPlayerCommand(packet).action()) {
            case PRESS_SHIFT_KEY, RELEASE_SHIFT_KEY -> {
                if (DecodedPacketReliability.nativeInputFamilyReliable(player.getClientVersion())) {
                    handleLegacySneakAction();
                }
            }
            case START_SPRINTING, STOP_SPRINTING -> {
                if (player.inVehicle()) {
                    return;
                }

                if (sprint) {
                    if (player.canSkipTicks() || flag()) {
                        flags++;
                    }
                }

                sprint = true;
            }
            default -> {
            }
        }
    }

    public void handleLegacySneakAction() {
        if (!player.cameraEntity.isSelf()) {
            sprint = sneak = false;
            return;
        }

        if (sneak && (player.canSkipTicks() || flag())) {
            flags++;
        }
        sneak = true;
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            sprint = sneak = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        if (!player.cameraEntity.isSelf()
                || (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick)) {
            sprint = sneak = false;
        }
    }
}
