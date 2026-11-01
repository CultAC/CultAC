package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.cult.cultac.utils.common.arguments.CommonCultArguments;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.jetbrains.annotations.NotNull;

public class CultLog implements BuildableCommand {
    // pastes.dev requires a User-Agent naming the app and a contact.
    private static final String CONTACT = "github.com/CultAC/CultAC";

    public static Component uploadedMessage(Sender sender, String template, String url) {
        String message = MessageUtil.replacePlaceholders(sender, template.replace("%url%", "<url>"));
        Component link = Component.text(url)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Click to open", NamedTextColor.GRAY)));
        return MessageUtil.miniMessage(message, Placeholder.component("url", link));
    }

    public static void sendLogAsync(Sender sender, String log, Consumer<String> consumer, String type) {
        String success = CultAPI.INSTANCE
                .getConfigManager()
                .getConfig()
                .getStringElse("upload-log", "%prefix% &fUploaded debug to: %url%");
        String failure = CultAPI.INSTANCE
                .getConfigManager()
                .getConfig()
                .getStringElse(
                        "upload-log-upload-failure",
                        "%prefix% &cSomething went wrong while uploading this log, see console for more information.");
        String uploading = CultAPI.INSTANCE
                .getConfigManager()
                .getConfig()
                .getStringElse("upload-log-start", "%prefix% &fUploading log... please wait");
        uploading = MessageUtil.replacePlaceholders(sender, uploading);
        sender.sendMessage(MessageUtil.miniMessage(uploading));
        CultAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(CultAPI.INSTANCE.getGrimPlugin(), () -> {
            try {
                sendLog(sender, log, success, failure, consumer, type);
            } catch (Exception e) {
                String message = MessageUtil.replacePlaceholders(sender, failure);
                sender.sendMessage(MessageUtil.miniMessage(message));
                LogUtil.error("Failed to send log", e);
            }
        });
    }

    private static void sendLog(
            Sender sender, String log, String success, String failure, Consumer<String> consumer, String type)
            throws IOException {
        byte[] body = log.getBytes(StandardCharsets.UTF_8);
        URL mUrl =
                URI.create(CommonCultArguments.PASTE_API_URL.value() + "post").toURL();
        HttpURLConnection urlConn = (HttpURLConnection) mUrl.openConnection();
        try {
            urlConn.setDoOutput(true);
            urlConn.setRequestMethod("POST");
            urlConn.setConnectTimeout(CommonCultArguments.URL_TIMEOUT.value());
            urlConn.setReadTimeout(CommonCultArguments.URL_TIMEOUT.value());
            urlConn.addRequestProperty(
                    "User-Agent",
                    "CultAC/" + CultAPI.INSTANCE.getExternalAPI().getGrimVersion() + " (" + CONTACT + ")");
            urlConn.addRequestProperty("Content-Type", type);
            urlConn.setFixedLengthStreamingMode(body.length);
            try (OutputStream stream = urlConn.getOutputStream()) {
                stream.write(body);
            }
            final int response = urlConn.getResponseCode();
            if (response == HttpURLConnection.HTTP_CREATED) {
                String location = urlConn.getHeaderField("Location");
                String key = location.substring(location.lastIndexOf('/') + 1);
                String url = CommonCultArguments.PASTE_URL.value() + key;
                consumer.accept(url);
                sender.sendMessage(uploadedMessage(sender, success, url));
            } else {
                String message = MessageUtil.replacePlaceholders(sender, failure);
                sender.sendMessage(MessageUtil.miniMessage(message));
                LogUtil.error("Returned response code " + response + ": " + urlConn.getResponseMessage());
            }
        } finally {
            urlConn.disconnect();
        }
    }

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        Command<Sender> command = BuildableCommand.root(commandManager)
                .literal("log", "logs")
                .permission("cult.log")
                .required("flagId", IntegerParser.integerParser())
                .flag(commandManager
                        .flagBuilder("save-to-file")
                        .withAliases("l")
                        .withDescription(Description.of(
                                "Write the debug log to the local debug-logs directory instead of uploading it")))
                .handler(this::handleLog)
                .manager(commandManager)
                .build();
        commandManager
                .command(command)
                .command(commandManager.commandBuilder("gl").proxies(command));
    }

    private void handleLog(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        int flagId = context.get("flagId");

        String log = SuperDebug.getFlag(flagId);
        if (log == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "upload-log-not-found", "%prefix% &cUnable to find that log"));
            return;
        }

        if (context.flags().hasFlag("save-to-file")) {
            saveLogLocally(sender, flagId, log);
            return;
        }
        sendLogAsync(sender, log, string -> {}, "text/yaml");
    }

    private static void saveLogLocally(Sender sender, int flagId, String log) {
        sender.sendMessage(Component.text("Writing debug log locally...", NamedTextColor.GRAY));
        CultAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(CultAPI.INSTANCE.getGrimPlugin(), () -> {
            try {
                Path directory = CultAPI.INSTANCE
                        .getGrimPlugin()
                        .getDataFolder()
                        .toPath()
                        .resolve("debug-logs");
                Files.createDirectories(directory);

                String fileName =
                        "flag-" + flagId + "-" + Instant.now().toString().replace(':', '-') + ".txt";
                Path path = directory.resolve(fileName).toAbsolutePath().normalize();
                Files.writeString(path, log, StandardCharsets.UTF_8);

                String pathString = path.toString();
                sender.sendMessage(Component.text()
                        .append(Component.text("Wrote debug log to: ", NamedTextColor.GRAY))
                        .append(Component.text(pathString, NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.copyToClipboard(pathString))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to copy", NamedTextColor.GRAY))))
                        .asComponent());
            } catch (IOException e) {
                sender.sendMessage(Component.text(
                        "Failed to write debug log locally; see console for more information.", NamedTextColor.RED));
                LogUtil.error("Failed to write debug log " + flagId + " locally", e);
            }
        });
    }
}
