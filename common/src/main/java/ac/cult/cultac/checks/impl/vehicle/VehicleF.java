package ac.cult.cultac.checks.impl.vehicle;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;

@CheckData(name = "VehicleF", stableKey = "cult.vehicle.boat_input_mismatch", experimental = true, description = "Sent incorrect boat paddle states")
public class VehicleF extends Check implements CheckListener {
    private static final Verbose V =
            Verbose.of("sent=({bool}, {bool}), expected=({bool}, {bool})");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private PacketEntity lastTickVehicle;

    public VehicleF(CultPlayer player) {
        super(player);
    }


    @CultPacketHandler
    public void onPaddleBoat(PacketReceiveEvent event, CultPlayer player, ServerboundPaddleBoatPacket packet) {
        // lastVehicleSwitch isn't updated by this time.
        if (lastTickVehicle != player.compensatedEntities.getSelf().getRiding()) return;

        boolean expectedLeft;
        boolean expectedRight;

        if (supportsEndTick()) {
            KnownInput input = player.packetStateData.knownInput;
            expectedLeft = input.forward() || !input.left() && input.right();
            expectedRight = input.forward() || input.left() && !input.right();
        } else {
            expectedLeft = player.boatData.nextVehicleForward > 0 || player.boatData.nextVehicleHoriz < 0;
            expectedRight = player.boatData.nextVehicleForward > 0 || player.boatData.nextVehicleHoriz > 0;

            if (player.boatData.nextVehicleForward == 0 && packet.getLeft() && packet.getRight()) {
                return; // the player is pressing forward and backward
            }
        }

        if (packet.getLeft() != expectedLeft || packet.getRight() != expectedRight) {
            boolean sentLeft = packet.getLeft();
            boolean sentRight = packet.getRight();
            if (flag(V.write(verbose()).bool(sentLeft).bool(sentRight).bool(expectedLeft).bool(expectedRight))
                && shouldModifyPackets()) {
                event.setNmsPacket(new ServerboundPaddleBoatPacket(expectedLeft, expectedRight));
                event.markForReEncode(true);
            }
        }
    }

    // isTickPacket: movement packets count unless they answered a teleport
    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.packetStateData.lastPacketWasTeleport) {
            lastTickVehicle = player.compensatedEntities.getSelf().getRiding();
        }
    }

    // isTickPacket: tick end counts for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick) {
            lastTickVehicle = player.compensatedEntities.getSelf().getRiding();
        }
    }

    private boolean supportsEndTick() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }
}
