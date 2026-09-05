package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;

import java.lang.reflect.Field;

@CheckData(name = "BadPacketsS", stableKey = "cult.badpackets.window_confirmation_not_accepted", description = "Sent a window confirmation packet marked as not accepted")
@DeadCheck(reason = DeadCheck.Reason.WIRE_UNTRIGGERABLE, detail = "ServerboundContainerAckPacket was removed from the 26.2 protocol.")
public class BadPacketsS extends Check implements CheckListener {
    public BadPacketsS(CultPlayer player) {
        super(player);
    }

    // ServerboundContainerAckPacket does not exist on 26.2 (legacy-only); the string form
    // leaves this handler unregistered there and the fields are resolved lazily.
    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundContainerAckPacket")
    public void onContainerAck(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        if (!isAccepted(packet) && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    private static boolean isAccepted(Packet<?> packet) {
        try {
            Field field = packet.getClass().getDeclaredField("accepted");
            field.setAccessible(true);
            return field.getBoolean(packet);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read container-ack accepted state", exception);
        }
    }
}
