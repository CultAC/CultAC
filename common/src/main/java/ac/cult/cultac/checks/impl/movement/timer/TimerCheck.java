package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.cultac.checks.BedrockSupported;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@BedrockSupported
public class TimerCheck extends AbstractTimerCheck {
    public TimerCheck(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder().name("Timer").configName("TimerA").setback(5).build()); }

    protected TimerCheck(CultPlayer cultPlayer, CheckInfo checkInfo) {
        super(cultPlayer, checkInfo);
    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        if (player.isBedrockMovement()) {
            return;
        }
        boolean mountedPassengerRotation = !packet.hasPosition()
                && (player.compensatedEntities.vehicles.serverPlayerVehicle != null
                || player.compensatedEntities.getSelf().inVehicle());
        recordModernMovePlayerPacket(!player.packetStateData.lastPacketWasTeleport && !mountedPassengerRotation);
        recordTimerEvent(event, false, shouldCountMovePlayerForTimer());
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundClientTickEndPacket")
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        recordModernClientTickEndPacket();
        recordTimerEvent(event, false, shouldCountClientTickEndForTimer());
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent event, CultPlayer player, ServerboundPongPacket packet) {
        recordTimerEvent(event, true, false);
    }

    @CultPacketHandler
    public void onContainerSlotStateChanged(PacketReceiveEvent event, CultPlayer player, ServerboundContainerSlotStateChangedPacket packet) {
        recordTimerEvent(event, true, false);
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
