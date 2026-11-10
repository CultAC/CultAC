package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;

public class VehicleTimer extends AbstractTimerCheck {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("TimerVehicle")
            .stableKey("grim.legacy.timervehicle")
            .description("Game ran faster than normal while riding")
            .configName("TimerVehicle")
            .setback(5)
            .build();
    boolean isDummy = false;
    private boolean countedVehicleMovementThisClientTick = false;

    public VehicleTimer(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
    }

    @CultPacketHandler
    public void onMoveVehicle(
            PacketReceiveEvent<ServerboundMoveVehicle> event, CultPlayer player, ServerboundMoveVehicle packet) {
        recordTimerEvent(event, false, shouldCountMoveVehicleForTimer(event.answersTeleport()));
    }

    @CultPacketHandler
    public void onPlayerInput(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        recordTimerEvent(event, false, shouldCountVehicleInputForTimer(event.answersTeleport()));
    }

    @CultPacketHandler
    public void onPaddleBoat(
            PacketReceiveEvent<ServerboundPaddleBoat> event, CultPlayer player, ServerboundPaddleBoat packet) {
        recordTimerEvent(event, false, shouldCountVehicleInputForTimer(event.answersTeleport()));
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!usesClientTickEndBoundary()) {
            // LocalPlayer#tick sends passenger input, then Rot, then the
            // locally-authoritative vehicle movement. Rot is the backend-
            // observable per-tick boundary when ViaVersion has removed the
            // newer ClientTickEnd packet.
            countedVehicleMovementThisClientTick = false;
        }
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (event.isCancelled()) return;
        countedVehicleMovementThisClientTick = false;
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        recordTimerEvent(event, true, false);
    }

    @CultPacketHandler("serverbound.container_slot_state_changed")
    public void onContainerSlotStateChanged(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        recordTimerEvent(event, true, false);
    }

    private boolean shouldCountMoveVehicleForTimer(boolean teleport) {
        // Ignore teleports
        if (teleport) return false;

        isDummy = false;
        if (countedVehicleMovementThisClientTick) return false;
        countedVehicleMovementThisClientTick = true;
        return true; // Client controlling vehicle
    }

    private boolean shouldCountVehicleInputForTimer(boolean teleport) {
        // Ignore teleports
        if (teleport) return false;

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
