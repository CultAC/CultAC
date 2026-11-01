package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.movement.timer.ItemUseBurst;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
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
    // LocalPlayer#sendPosition sends a position at least every 20 client ticks.
    private static final int WINDOW_TICKS = 40;

    private int positions = 0;
    private long clock = 0;
    private long lastTransTime;
    private int oldTransId = 0;
    private long windowTickNanos = ItemUseBurst.periodNanos(20F, false);

    public BadPacketsR(final CultPlayer player) {
        super(player);
    }

    // isTransaction: the legacy container-ack packet does not exist on 26.2
    @CultPacketHandler
    public void onPong(final PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        if (!event.isAcceptedTransactionResponse()) return;

        long tickNanos = ItemUseBurst.periodNanos(
                player.packetStateData.serverTickRate,
                player.packetStateData.serverTicksFrozen && player.packetStateData.serverFrozenTickStepsRemaining == 0);
        boolean periodChanged = tickNanos != windowTickNanos;
        long window = WINDOW_TICKS * windowTickNanos / 1000000L;
        long ms = (player.getPlayerClockAtLeast() - clock) / 1000000L;
        long diff = (System.currentTimeMillis() - lastTransTime);
        if (periodChanged || (diff > window && ms > window)) {
            // LocalPlayer#tick, which sends every position, runs only once ClientPacketListener#hasClientLoaded.
            if (periodChanged) {
                windowTickNanos = tickNanos;
            } else if (positions == 0
                    && clock != 0
                    && (player.packetStateData.playerLoadedIntoLevel
                            || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_4))
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
        if (player.inVehicle() || packet.hasPosition()) countPositionUpdate(player);
    }

    // From 26.3 ClientPacketListener#handleMovePlayer carries the teleport response's position in this packet and
    // sends no PosRot after it. PacketServerTeleport, registered ahead of the check listener, cancels one that answers
    // no pending teleport.
    @CultPacketHandler
    public void onAcceptTeleportation(
            final PacketReceiveEvent<ServerboundAcceptTeleportation> event,
            CultPlayer player,
            ServerboundAcceptTeleportation packet) {
        if (packet.position() == null || event.isCancelled()) return;
        countPositionUpdate(player);
    }

    private void countPositionUpdate(CultPlayer player) {
        if (!player.inVehicle()) {
            positions++;
            return;
        }
        // LocalPlayer#sendChanges sends a passenger's Rot every tick, and a MoveVehicle only for a vehicle it controls,
        // so the Rot is the update of a passenger the vehicle does not require to move it.
        if (!player.compensatedEntities.vehicles.mustEchoMoveVehicle(
                player.compensatedEntities.getSelf().getRiding())) {
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
