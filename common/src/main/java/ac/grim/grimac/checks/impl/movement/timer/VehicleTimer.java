package ac.grim.grimac.checks.impl.movement.timer;

import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;

//@CheckData(name = "Timer - Vehicle", configName = "TimerVehicle", setback = 10)
public class VehicleTimer extends AbstractTimerCheck {
    boolean isDummy = false;
    private boolean countedVehicleMovementThisClientTick = false;

    public VehicleTimer(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder().name("TimerVehicle").configName("TimerVehicle").setback(5).build()); }

    @GrimPacketHandler
    public void onMoveVehicle(PacketReceiveEvent event, GrimPlayer player, ServerboundMoveVehiclePacket packet) {
        recordTimerEvent(event, false, shouldCountMoveVehicleForTimer());
    }

    @GrimPacketHandler
    public void onPlayerInput(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerInputPacket packet) {
        recordTimerEvent(event, false, shouldCountVehicleInputForTimer());
    }

    @GrimPacketHandler
    public void onPaddleBoat(PacketReceiveEvent event, GrimPlayer player, ServerboundPaddleBoatPacket packet) {
        recordTimerEvent(event, false, shouldCountVehicleInputForTimer());
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!usesClientTickEndBoundary()) {
            // LocalPlayer#tick sends passenger input, then Rot, then the
            // locally-authoritative vehicle movement. Rot is the backend-
            // observable per-tick boundary when ViaVersion has removed the
            // newer ClientTickEnd packet.
            countedVehicleMovementThisClientTick = false;
        }
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundClientTickEndPacket")
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        countedVehicleMovementThisClientTick = false;
    }

    @GrimPacketHandler
    public void onPong(PacketReceiveEvent event, GrimPlayer player, ServerboundPongPacket packet) {
        recordTimerEvent(event, true, false);
    }

    @GrimPacketHandler
    public void onContainerSlotStateChanged(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerSlotStateChangedPacket packet) {
        recordTimerEvent(event, true, false);
    }

    public boolean handleLegacySteerVehicle() {
        return recordTimerEventForPacketDecision(false, shouldCountVehicleInputForTimer());
    }

    private boolean shouldCountMoveVehicleForTimer() {
        // Ignore teleports
        if (player.packetStateData.lastPacketWasTeleport) return false;

        isDummy = false;
        if (countedVehicleMovementThisClientTick) return false;
        countedVehicleMovementThisClientTick = true;
        return true; // Client controlling vehicle
    }

    private boolean shouldCountVehicleInputForTimer() {
        // Ignore teleports
        if (player.packetStateData.lastPacketWasTeleport) return false;

        if (player.compensatedEntities.getSelf().inVehicle()) {
            if (isDummy) { // Server is controlling vehicle
                if (countedVehicleMovementThisClientTick) return false;
                countedVehicleMovementThisClientTick = true;
                return true;
            }
            isDummy = true; // Client is controlling vehicle
        }

        return false;
    }
}
