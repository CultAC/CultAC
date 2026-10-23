package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;

@BedrockSupported
public class TimerCheck extends AbstractTimerCheck {
    public TimerCheck(CultPlayer cultPlayer) {
        super(
                cultPlayer,
                CheckInfo.builder()
                        .name("Timer")
                        .configName("TimerA")
                        .setback(5)
                        .build());
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (player.isBedrockMovement()) {
            return;
        }
        boolean mountedPassengerRotation = !packet.hasPosition()
                && (player.compensatedEntities.vehicles.serverPlayerVehicle != null
                        || player.compensatedEntities.getSelf().inVehicle());
        recordModernMovePlayerPacket(event, !player.packetStateData.lastPacketWasTeleport && !mountedPassengerRotation);
        recordTimerEvent(event, false, shouldCountMovePlayerForTimer());
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        recordModernClientTickEndPacket();
        recordTimerEvent(event, false, shouldCountClientTickEndForTimer());
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        recordTimerEvent(event, true, false);
    }

    @CultPacketHandler("serverbound.container_slot_state_changed")
    public void onContainerSlotStateChanged(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        recordTimerEvent(event, true, false);
    }

    public void onTransactionResponse() {
        recordTimerEventForPacketDecision(true, false);
    }

    public BedrockAuthInputDecision onBedrockAuthInput() {
        return recordTimerEventForPacketDecision(false, true)
                ? BedrockAuthInputDecision.REJECT
                : BedrockAuthInputDecision.ACCEPT;
    }

    public BedrockAuthInputDecision onBedrockAuthInput(PacketReceiveEvent event) {
        BedrockAuthInputDecision decision = onBedrockAuthInput();
        if (decision == BedrockAuthInputDecision.REJECT) {
            event.setCancelled(true);
        }
        return decision;
    }

    public enum BedrockAuthInputDecision {
        ACCEPT,
        REJECT
    }
}
