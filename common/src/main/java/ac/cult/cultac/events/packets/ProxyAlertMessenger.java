package ac.cult.cultac.events.packets;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.*;

public class ProxyAlertMessenger implements PluginMessageListener {
    private static boolean usingProxy;

    public ProxyAlertMessenger() {
        usingProxy = ProxyAlertMessenger.getBooleanFromFile("spigot.yml", "settings.bungeecord")
                || ProxyAlertMessenger.getBooleanFromFile("paper.yml", "settings.velocity-support.enabled")
                || ProxyAlertMessenger.getBooleanFromFile("config/paper-global.yml", "proxies.velocity.enabled");

        if (usingProxy) {
            LogUtil.info("Registering an outgoing plugin channel...");
            CultAPI.INSTANCE.getPlugin().getServer().getMessenger().registerOutgoingPluginChannel(CultAPI.INSTANCE.getPlugin(), "BungeeCord");
            CultAPI.INSTANCE.getPlugin().getServer().getMessenger().registerIncomingPluginChannel(CultAPI.INSTANCE.getPlugin(), "BungeeCord", this);
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] data) {
        if (!ProxyAlertMessenger.canReceiveAlerts())
            return;
        if (!channel.equals("BungeeCord") && !channel.equals("bungeecord:main"))
            return;

        ByteArrayDataInput in = ByteStreams.newDataInput(data);

        if (!in.readUTF().equals("CULTAC")) return;

        final String alert;
        byte[] messageBytes = new byte[in.readShort()];
        in.readFully(messageBytes);

        try {
            alert = new DataInputStream(new ByteArrayInputStream(messageBytes)).readUTF();
        } catch (IOException exception) {
            LogUtil.error("Something went wrong whilst reading an alert forwarded from another server!");
            exception.printStackTrace();
            return;
        }

        CultAPI.INSTANCE.getAlertManager().sendAlert(MessageUtil.miniMessage(alert), null);
    }

    public static void sendPluginMessage(String message) {
        if (!canSendAlerts()) {
            return;
        }

        // The alert text rides inside the proxy message as a length-prefixed UTF payload.
        byte[] payload;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream dataOut = new DataOutputStream(buffer);
            dataOut.writeUTF(message);
            dataOut.flush();
            payload = buffer.toByteArray();
        } catch (IOException exception) {
            LogUtil.error("Something went wrong whilst forwarding an alert to other servers!");
            exception.printStackTrace();
            return;
        }

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Forward");
        out.writeUTF("ONLINE");
        out.writeUTF("CULTAC");
        out.writeShort(payload.length);
        out.write(payload);

        Player carrier = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
        if (carrier != null) {
            carrier.sendPluginMessage(CultAPI.INSTANCE.getPlugin(), "BungeeCord", out.toByteArray());
        }
    }

    public static boolean canSendAlerts() {
        return usingProxy && CultAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("alerts.proxy.send", false) && Bukkit.getOnlinePlayers().size() > 0;
    }

    public static boolean canReceiveAlerts() {
        return usingProxy
                && CultAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("alerts.proxy.receive", false)
                && CultAPI.INSTANCE.getAlertManager().hasAlertListeners();
    }

    // TODO (Cross-Platform) check if new getBooleanFromFile impl is correct
    private static boolean getBooleanFromFile(String pathToFile, String pathToValue) {
        File file = new File(pathToFile);
        if (!file.exists()) return false;
        return YamlConfiguration.loadConfiguration(file).getBoolean(pathToValue);
    }
}
