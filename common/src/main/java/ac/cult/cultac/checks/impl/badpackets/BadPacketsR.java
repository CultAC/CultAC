package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "BadPacketsR",
        stableKey = "cult.badpackets.position_starvation",
        description = "Stopped sending position updates while still responding to transactions",
        decay = 0.25,
        experimental = true)
public class BadPacketsR extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("time={ulong}ms, lst={ulong}ms, positions={uint}");

    private int positions = 0;
    private long clock = 0;
    private long lastTransTime;
    private int oldTransId = 0;

    public BadPacketsR(final CultPlayer player) {
        super(player);
    }

    // isTransaction: the legacy container-ack packet does not exist on 26.2
    @CultPacketHandler
    public void onPong(final PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        if (!event.isAcceptedTransactionResponse()) return;

        long ms = (player.getPlayerClockAtLeast() - clock) / 1000000L;
        long diff = (System.currentTimeMillis() - lastTransTime);
        if (diff > 2000 && ms > 2000) {
            if (positions == 0
                    && clock != 0
                    && player.cameraEntity.isSelf()
                    && !player.compensatedEntities.getSelf().isDead) {
                flag(V.write(verbose()).ulong(ms).ulong(diff).uint(positions));
            } else {
                reward();
            }

            player.compensatedWorld.removeInvalidPistonLikeStuff(oldTransId);
            positions = 0;
            clock = player.getPlayerClockAtLeast();
            lastTransTime = System.currentTimeMillis();
            oldTransId = player.lastTransactionSent.get();
        }
    }

    @CultPacketHandler
    public void onMovePlayer(
            final PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if ((packet.hasPosition()) && !player.inVehicle()) {
            positions++;
        }
    }

    @CultPacketHandler
    public void onMoveVehicle(
            final PacketReceiveEvent<ServerboundMoveVehicle> event, CultPlayer player, ServerboundMoveVehicle packet) {
        if (player.inVehicle()) {
            positions++;
        }
    }
}
