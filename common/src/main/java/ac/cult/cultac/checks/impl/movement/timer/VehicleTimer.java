package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
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

    public VehicleTimer(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder().name("TimerVehicle").configName("TimerVehicle").setback(5).build()); }

    @CultPacketHandler
    public void onMoveVehicle(PacketReceiveEvent event, CultPlayer player, ServerboundMoveVehiclePacket packet) {
        recordTimerEvent(event, false, shouldCountMoveVehicleForTimer());
    }

    @CultPacketHandler
    public void onPlayerInput(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerInputPacket packet) {
        recordTimerEvent(event, false, shouldCountVehicleInputForTimer());
    }

    @CultPacketHandler
    public void onPaddleBoat(PacketReceiveEvent event, CultPlayer player, ServerboundPaddleBoatPacket packet) {
        recordTimerEvent(event, false, shouldCountVehicleInputForTimer());
    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        if (!usesClientTickEndBoundary()) {
            // LocalPlayer#tick sends passenger input, then Rot, then the
            // locally-authoritative vehicle movement. Rot is the backend-
            // observable per-tick boundary when ViaVersion has removed the
            // newer ClientTickEnd packet.
            countedVehicleMovementThisClientTick = false;
        }
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundClientTickEndPacket")
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        countedVehicleMovementThisClientTick = false;
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent event, CultPlayer player, ServerboundPongPacket packet) {
        recordTimerEvent(event, true, false);
    }

    @CultPacketHandler
    public void onContainerSlotStateChanged(PacketReceiveEvent event, CultPlayer player, ServerboundContainerSlotStateChangedPacket packet) {
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
