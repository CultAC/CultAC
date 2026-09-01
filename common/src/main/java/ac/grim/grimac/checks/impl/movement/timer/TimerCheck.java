package ac.grim.grimac.checks.impl.movement.timer;

import ac.grim.grimac.checks.BedrockSupported;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@BedrockSupported
public class TimerCheck extends AbstractTimerCheck {
    public TimerCheck(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder().name("Timer").configName("TimerA").setback(5).build()); }

    protected TimerCheck(GrimPlayer grimPlayer, CheckInfo checkInfo) {
        super(grimPlayer, checkInfo);
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (player.isBedrockMovement()) {
            return;
        }
        boolean mountedPassengerRotation = !packet.hasPosition()
                && (player.compensatedEntities.vehicles.serverPlayerVehicle != null
                || player.compensatedEntities.getSelf().inVehicle());
        recordModernMovePlayerPacket(!player.packetStateData.lastPacketWasTeleport && !mountedPassengerRotation);
        recordTimerEvent(event, false, shouldCountMovePlayerForTimer());
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundClientTickEndPacket")
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        recordModernClientTickEndPacket();
        recordTimerEvent(event, false, shouldCountClientTickEndForTimer());
    }

    @GrimPacketHandler
    public void onPong(PacketReceiveEvent event, GrimPlayer player, ServerboundPongPacket packet) {
        recordTimerEvent(event, true, false);
    }

    @GrimPacketHandler
    public void onContainerSlotStateChanged(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerSlotStateChangedPacket packet) {
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
