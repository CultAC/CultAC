package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;

@CheckData(name = "BadPacketsR", stableKey = "cult.badpackets.position_starvation", description = "Stopped sending position updates while still responding to transactions", decay = 0.25, experimental = true)
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
    public void onPong(final PacketReceiveEvent event, CultPlayer player, ServerboundPongPacket packet) {
        if (!event.isAcceptedTransactionResponse()) return;

        long ms = (player.getPlayerClockAtLeast() - clock) / 1000000L;
        long diff = (System.currentTimeMillis() - lastTransTime);
        if (diff > 2000 && ms > 2000) {
            if (positions == 0 && clock != 0 && player.cameraEntity.isSelf() && !player.compensatedEntities.getSelf().isDead) {
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
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(final PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {

        if ((packet instanceof ServerboundMovePlayerPacket.PosRot || packet instanceof ServerboundMovePlayerPacket.Pos)
                && !player.inVehicle()) {
            positions++;
        }
    }


    public void handleLegacySteerVehicle() {
        if (player.inVehicle()) {
            positions++;
        }
    }


    @CultPacketHandler
    public void onMoveVehicle(final PacketReceiveEvent event, CultPlayer player, ServerboundMoveVehiclePacket packet) {
        if (player.inVehicle()) {
            positions++;
        }
    }
}
