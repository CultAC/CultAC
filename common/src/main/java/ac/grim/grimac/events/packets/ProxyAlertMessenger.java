package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
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
            GrimAPI.INSTANCE.getPlugin().getServer().getMessenger().registerOutgoingPluginChannel(GrimAPI.INSTANCE.getPlugin(), "BungeeCord");
            GrimAPI.INSTANCE.getPlugin().getServer().getMessenger().registerIncomingPluginChannel(GrimAPI.INSTANCE.getPlugin(), "BungeeCord", this);
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] data) {
        if (!ProxyAlertMessenger.canReceiveAlerts())
            return;
        if (!channel.equals("BungeeCord") && !channel.equals("bungeecord:main"))
            return;

        ByteArrayDataInput in = ByteStreams.newDataInput(data);

        if (!in.readUTF().equals("GRIMAC")) return;

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

        GrimAPI.INSTANCE.getAlertManager().sendAlert(MessageUtil.miniMessage(alert), null);
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
        out.writeUTF("GRIMAC");
        out.writeShort(payload.length);
        out.write(payload);

        Player carrier = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
        if (carrier != null) {
            carrier.sendPluginMessage(GrimAPI.INSTANCE.getPlugin(), "BungeeCord", out.toByteArray());
        }
    }

    public static boolean canSendAlerts() {
        return usingProxy && GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("alerts.proxy.send", false) && Bukkit.getOnlinePlayers().size() > 0;
    }

    public static boolean canReceiveAlerts() {
        return usingProxy
                && GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("alerts.proxy.receive", false)
                && GrimAPI.INSTANCE.getAlertManager().hasAlertListeners();
    }

    // TODO (Cross-Platform) check if new getBooleanFromFile impl is correct
    private static boolean getBooleanFromFile(String pathToFile, String pathToValue) {
        File file = new File(pathToFile);
        if (!file.exists()) return false;
        return YamlConfiguration.loadConfiguration(file).getBoolean(pathToValue);
    }
}
