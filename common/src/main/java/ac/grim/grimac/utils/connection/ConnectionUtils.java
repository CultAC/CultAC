package ac.grim.grimac.utils.connection;

import ac.grim.grimac.player.GrimPlayer;
import lombok.experimental.UtilityClass;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.NotNull;

/** Injects packets without dispatching them to Grim's handlers. */
@UtilityClass
public class ConnectionUtils {

    public void sendPacketPreVia(@NotNull GrimPlayer player, @NotNull Packet<?> packet) {
        player.user.sendPacketSilently(packet);
    }

    public void receivePacketPreVia(@NotNull GrimPlayer player, @NotNull Packet<?> packet) {
        player.user.receivePacketSilently(packet);
    }
}
