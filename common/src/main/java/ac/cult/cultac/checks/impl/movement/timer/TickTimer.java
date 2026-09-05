package ac.cult.cultac.checks.impl.movement.timer;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@CheckData(name = "TickTimer", stableKey = "cult.timer.tick", description = "Did not send client tick end packet", setback = 1)
public final class TickTimer extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("type=[end|flying], packets={uint}");

    private boolean receivedTickEnd = true;
    private int flyingPackets;

    public TickTimer(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.supportsEndTick();
    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        if (!isApplicable() || player.packetStateData.lastPacketWasTeleport) return;

        if (!receivedTickEnd
                && flagWithSetback(V.write(verbose()).bool(false).uint(flyingPackets))) {
            player.onPacketCancel();
        }
        receivedTickEnd = false;
        flyingPackets++;
    }

    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        if (!isApplicable()) return;

        receivedTickEnd = true;
        if (flyingPackets > 1
                && flagWithSetback(V.write(verbose()).bool(true).uint(flyingPackets))) {
            player.onPacketCancel();
        }
        flyingPackets = 0;
    }
}
