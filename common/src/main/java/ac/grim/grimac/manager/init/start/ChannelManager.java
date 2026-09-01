package ac.grim.grimac.manager.init.start;

import ac.grim.grimac.GrimAPI;
import com.google.common.collect.ImmutableSet;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class ChannelManager implements StartableInitable, PluginMessageListener {

    private final Set<String> CHANNELS = ImmutableSet.of(
            "lunarclient:pm", "fml:handshake", "fml:play", "feather:client", "ac:handshake", "badlion:modapi");

    @Override
    public void start() {
        for (String channel : CHANNELS) { registerChannel(channel); }
    }

    private void registerChannel(String channelName) {
        final Messenger messenger = GrimAPI.INSTANCE.getPlugin().getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(GrimAPI.INSTANCE.getPlugin(), channelName);
        messenger.registerIncomingPluginChannel(GrimAPI.INSTANCE.getPlugin(), channelName, this);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channelName, @NotNull Player bukkitPlayer, @NotNull byte[] data) {

    }


}
