package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.prediction.SuperDebug;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.common.arguments.CommonGrimArguments;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;

public class GrimLog implements BuildableCommand {
    public static void sendLogAsync(Sender sender, String log, Consumer<String> consumer, String type) {
        String success = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("upload-log", "%prefix% &fUploaded debug to: %url%");
        String failure = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("upload-log-upload-failure", "%prefix% &cSomething went wrong while uploading this log, see console for more information.");
        String uploading = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("upload-log-start", "%prefix% &fUploading log... please wait");
        uploading = MessageUtil.replacePlaceholders(sender, uploading);
        sender.sendMessage(MessageUtil.miniMessage(uploading));
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(GrimAPI.INSTANCE.getGrimPlugin(), () -> {
            try {
                sendLog(sender, log, success, failure, consumer, type);
            } catch (Exception e) {
                String message = MessageUtil.replacePlaceholders(sender, failure);
                sender.sendMessage(MessageUtil.miniMessage(message));
                LogUtil.error("Failed to send log", e);
            }
        });
    }

    private static void sendLog(Sender sender, String log, String success, String failure, Consumer<String> consumer, String type) throws IOException {
        URL mUrl = new URL(CommonGrimArguments.PASTE_URL.value() + "data/post");
        HttpURLConnection urlConn = (HttpURLConnection) mUrl.openConnection();
        try {
            urlConn.setDoOutput(true);
            urlConn.setRequestMethod("POST");
            urlConn.setConnectTimeout(CommonGrimArguments.URL_TIMEOUT.value());
            urlConn.setReadTimeout(CommonGrimArguments.URL_TIMEOUT.value());
            urlConn.addRequestProperty("User-Agent", "GrimAC/" + GrimAPI.INSTANCE.getExternalAPI().getGrimVersion());
            urlConn.addRequestProperty("Content-Type", type); // Not really yaml, but looks nicer than plaintext
            urlConn.setRequestProperty("Content-Length", Integer.toString(log.length()));
            try (OutputStream stream = urlConn.getOutputStream()) {
                stream.write(log.getBytes(StandardCharsets.UTF_8));
            }
            final int response = urlConn.getResponseCode();
            if (response == HttpURLConnection.HTTP_CREATED) {
                String responseURL = urlConn.getHeaderField("Location");
                String message = success.replace("%url%", CommonGrimArguments.PASTE_URL.value() + responseURL);
                consumer.accept(message);
                message = MessageUtil.replacePlaceholders(sender, message);
                sender.sendMessage(MessageUtil.miniMessage(message));
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
        Command<Sender> command = commandManager.commandBuilder("grim", "grimac")
                .literal("log", "logs")
                .permission("grim.log")
                .required("flagId", IntegerParser.integerParser())
                .flag(commandManager.flagBuilder("save-to-file")
                        .withAliases("l")
                        .withDescription(Description.of("Write the debug log to the local debug-logs directory instead of uploading it")))
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
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "upload-log-not-found", "%prefix% &cUnable to find that log"));
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
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(GrimAPI.INSTANCE.getGrimPlugin(), () -> {
            try {
                Path directory = GrimAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath().resolve("debug-logs");
                Files.createDirectories(directory);

                String fileName = "flag-" + flagId + "-" + Instant.now().toString().replace(':', '-') + ".txt";
                Path path = directory.resolve(fileName).toAbsolutePath().normalize();
                Files.writeString(path, log, StandardCharsets.UTF_8);

                String pathString = path.toString();
                sender.sendMessage(Component.text()
                        .append(Component.text("Wrote debug log to: ", NamedTextColor.GRAY))
                        .append(Component.text(pathString, NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.copyToClipboard(pathString))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to copy", NamedTextColor.GRAY))))
                        .build());
            } catch (IOException e) {
                sender.sendMessage(Component.text("Failed to write debug log locally; see console for more information.", NamedTextColor.RED));
                LogUtil.error("Failed to write debug log " + flagId + " locally", e);
            }
        });
    }
}
