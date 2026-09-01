package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.DeadCheck;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;

import java.lang.reflect.Field;

@CheckData(name = "BadPacketsS", stableKey = "grim.badpackets.window_confirmation_not_accepted", description = "Sent a window confirmation packet marked as not accepted")
@DeadCheck(reason = DeadCheck.Reason.WIRE_UNTRIGGERABLE, detail = "ServerboundContainerAckPacket was removed from the 26.2 protocol.")
public class BadPacketsS extends Check implements CheckListener {
    public BadPacketsS(GrimPlayer player) {
        super(player);
    }

    // ServerboundContainerAckPacket does not exist on 26.2 (legacy-only); the string form
    // leaves this handler unregistered there and the fields are resolved lazily.
    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundContainerAckPacket")
    public void onContainerAck(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
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
