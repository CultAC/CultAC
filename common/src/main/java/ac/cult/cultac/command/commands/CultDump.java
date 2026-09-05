package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.PlatformPlugin;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.cultac.utils.common.PropertiesUtil;
import ac.cult.cultac.utils.reflection.ReflectionUtils;
import ac.cult.cultac.utils.viaversion.ViaVersionUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Properties;

public class CultDump implements BuildableCommand {

    private static final boolean PAPER = ReflectionUtils.hasClass("com.destroystokyo.paper.PaperConfig")
            || ReflectionUtils.hasClass("io.papermc.paper.configuration.Configuration");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private String link = null; // these links should not expire for a while

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("cult", "cultac", "grim", "grimac")
                        .literal("dump", Description.of("Generate a debug dump"))
                        .permission("cult.dump")
                        .handler(this::handleDump)
        );
    }

    private void handleDump(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();

        if (link != null) {
            sender.sendMessage(MessageUtil.miniMessage(CultAPI.INSTANCE.getConfigManager().getConfig()
                    .getStringElse("upload-log", "%prefix% &fUploaded debug to: %url%")
                    .replace("%url%", link)));
            return;
        }
        // TODO: change this back to application/json once allowed
        CultLog.sendLogAsync(sender, generateDump(), string -> link = string, "text/yaml");
    }

    public static JsonObject getDumpInfo() {
        JsonObject base = new JsonObject();
        base.addProperty("type", "dump");
        base.addProperty("timestamp", System.currentTimeMillis());
        // versions
        JsonObject versions = new JsonObject();
        base.add("versions", versions);
        versions.addProperty("cult", CultAPI.INSTANCE.getExternalAPI().getGrimVersion());
        versions.addProperty("server", SERVER_VERSION.getReleaseName());
        versions.addProperty("implementation", CultAPI.INSTANCE.getPlatformServer().getPlatformImplementationString());
        // state of different properties
        JsonObject states = new JsonObject();
        base.add("states", states);
        if (CultAPI.INSTANCE.isInitialized()) states.addProperty("platform", CultAPI.INSTANCE.getPlatform().toString());
        if (ViaVersionUtil.isAvailable) states.addProperty("has_viaversion", true);
        if (PAPER) states.addProperty("has_paper", true);
        // include some relevant settings if not default
        JsonObject settings = new JsonObject();
        if (CultAPI.INSTANCE.getAlertManager().hasConsoleVerboseEnabled()) settings.addProperty("console_verbose", true);
        if (!CultAPI.INSTANCE.getAlertManager().hasConsoleAlertsEnabled()) settings.addProperty("console_alerts", false);
        if (settings.size() > 0) states.add("settings", settings);
        // system
        JsonObject system = new JsonObject();
        base.add("system", system);
        system.addProperty("os_name", System.getProperty("os.name"));
        system.addProperty("java_version", System.getProperty("java.version"));
        system.addProperty("user_language", System.getProperty("user.language"));
        // build
        base.add("build", getBuildInfo());
        // plugins
        JsonArray plugins = new JsonArray();
        base.add("plugins", plugins);
        for (PlatformPlugin plugin : CultAPI.INSTANCE.getPluginManager().getPlugins()) {
            JsonObject pluginJson = new JsonObject();
            pluginJson.addProperty("enabled", plugin.isEnabled());
            pluginJson.addProperty("name", plugin.getName());
            pluginJson.addProperty("version", plugin.getVersion());
            plugins.add(pluginJson);
        }
        return base;
    }

    private static JsonObject getBuildInfo() {
        JsonObject object = new JsonObject();
        try {
            Properties properties = PropertiesUtil.readProperties(CultAPI.INSTANCE.getClass(), "cultac.properties");
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                object.addProperty(entry.getKey().toString(), entry.getValue().toString());
            }
        } catch (Exception ignored) {}
        return object;
    }

    /**
     * Generates a diagnostic dump in JSON format that contains various metadata
     * about the system, platform, and plugins. This dump is primarily used for
     * debugging and finding potential issues with the environment.
     * @return A JSON-formatted string containing the diagnostic dump.
     */
    private String generateDump() {
        JsonObject base = getDumpInfo();
        return gson.toJson(base);
    }
}
