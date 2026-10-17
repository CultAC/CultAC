package ac.cult.cultac.checks.impl.vehicle;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "VehicleF",
        stableKey = "cult.vehicle.boat_input_mismatch",
        experimental = true,
        description = "Sent incorrect boat paddle states")
public class VehicleF extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("sent=({bool}, {bool}), expected=({bool}, {bool})");

    private PacketEntity lastTickVehicle;

    public VehicleF(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPaddleBoat(
            PacketReceiveEvent<ServerboundPaddleBoat> event, CultPlayer player, ServerboundPaddleBoat packet) {
        // lastVehicleSwitch isn't updated by this time.
        if (lastTickVehicle != player.compensatedEntities.getSelf().getRiding()) return;

        KnownInput input = player.packetStateData.knownInput;
        boolean expectedLeft = input.forward() || !input.left() && input.right();
        boolean expectedRight = input.forward() || input.left() && !input.right();

        if (packet.left() != expectedLeft || packet.right() != expectedRight) {
            boolean sentLeft = packet.left();
            boolean sentRight = packet.right();
            if (flag(V.write(verbose())
                            .bool(sentLeft)
                            .bool(sentRight)
                            .bool(expectedLeft)
                            .bool(expectedRight))
                    && shouldModifyPackets()) {
                event.replace(new ServerboundPaddleBoat(expectedLeft, expectedRight));
            }
        }
    }

    // isTickPacket: movement packets count unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.packetStateData.lastPacketWasTeleport) {
            lastTickVehicle = player.compensatedEntities.getSelf().getRiding();
        }
    }

    // isTickPacket: tick end counts for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.packetStateData.receivedMovementThisClientTick) {
            lastTickVehicle = player.compensatedEntities.getSelf().getRiding();
        }
    }
}
