package ac.cult.cultac.utils.connection;

import ac.cult.cultac.player.CultPlayer;
import lombok.experimental.UtilityClass;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.NotNull;

/** Injects packets without dispatching them to Cult's handlers. */
@UtilityClass
public class ConnectionUtils {

    public void sendPacketPreVia(@NotNull CultPlayer player, @NotNull Packet<?> packet) {
        player.user.sendPacketSilently(packet);
    }

    public void receivePacketPreVia(@NotNull CultPlayer player, @NotNull Packet<?> packet) {
        player.user.receivePacketSilently(packet);
    }
}
