package ac.grim.grimac.checks.impl.movement.timer;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@CheckData(name = "TickTimer", stableKey = "grim.timer.tick", description = "Did not send client tick end packet", setback = 1)
public final class TickTimer extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("type=[end|flying], packets={uint}");

    private boolean receivedTickEnd = true;
    private int flyingPackets;

    public TickTimer(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.supportsEndTick();
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!isApplicable() || player.packetStateData.lastPacketWasTeleport) return;

        if (!receivedTickEnd
                && flagWithSetback(V.write(verbose()).bool(false).uint(flyingPackets))) {
            player.onPacketCancel();
        }
        receivedTickEnd = false;
        flyingPackets++;
    }

    @GrimPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, ServerboundClientTickEndPacket packet) {
        if (!isApplicable()) return;

        receivedTickEnd = true;
        if (flyingPackets > 1
                && flagWithSetback(V.write(verbose()).bool(true).uint(flyingPackets))) {
            player.onPacketCancel();
        }
        flyingPackets = 0;
    }
}
