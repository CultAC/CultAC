package ac.cult.cultac.command.commands;

import static ac.cult.cultac.manager.config.BaseConfigManager.OBSERVE_ONLY_ENV;
import static ac.cult.cultac.manager.config.BaseConfigManager.OBSERVE_ONLY_KEY;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.manager.config.update.ConfigPatcher;
import ac.cult.cultac.manager.config.update.ConfigPatcher.NodePosition;
import ac.cult.cultac.manager.config.update.ConfigPatcher.PendingChange;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

public class CultObserve implements BuildableCommand {

    enum Outcome {
        PINNED,
        FAILED,
        UNRESTORED,
        APPLIED
    }

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        Command.Builder<Sender> observe =
                BuildableCommand.root(commandManager).literal("observe").permission("cult.observe");
        commandManager.command(observe.handler(this::handleStatus));
        commandManager.command(observe.literal("on").handler(context -> handleSwitch(context.sender(), true)));
        commandManager.command(observe.literal("off").handler(context -> handleSwitch(context.sender(), false)));
    }

    private void handleStatus(@NotNull CommandContext<Sender> context) {
        String source = BaseConfigManager.observeOnlyPin(System::getenv) != null
                ? "pinned by the " + OBSERVE_ONLY_ENV + " environment variable"
                : "from " + OBSERVE_ONLY_KEY + " in config.yml";
        context.sender().sendMessage(MessageUtil.miniMessage("%prefix% &f" + mode() + " &7(" + source + ")"));
    }

    private void handleSwitch(Sender sender, boolean observeOnly) {
        switchTo(observeOnly, configFile(), System::getenv, CultReload::reloadAsync)
                .thenAccept(outcome -> sender.sendMessage(MessageUtil.miniMessage(
                        switch (outcome) {
                            case PINNED ->
                                "%prefix% &cThe " + OBSERVE_ONLY_ENV
                                        + " environment variable pins the mode to " + mode()
                                        + "; set it to a value other than true/1/yes/false/0/no and restart to switch here.";
                            case FAILED ->
                                "%prefix% &cFailed to switch; still " + mode()
                                        + " and config.yml is unchanged. See the console.";
                            case UNRESTORED ->
                                "%prefix% &cFailed to switch; still " + mode()
                                        + ", but config.yml could not be restored. See the console.";
                            case APPLIED -> "%prefix% &fNow " + mode() + ".";
                        })));
    }

    /** Writes {@code observe-only} and reloads, unless {@code CULTAC_OBSERVE_ONLY} pins the mode. */
    static CompletableFuture<Outcome> switchTo(
            boolean observeOnly,
            File config,
            Function<String, String> env,
            Supplier<CompletableFuture<Boolean>> reload) {
        if (BaseConfigManager.observeOnlyPin(env) != null) return CompletableFuture.completedFuture(Outcome.PINNED);
        Path path = config.toPath();
        byte[] original;
        try {
            original = Files.readAllBytes(path);
        } catch (IOException failure) {
            LogUtil.error("Failed to read " + config, failure);
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        CompletableFuture<Boolean> reloaded;
        try {
            write(config, observeOnly);
            reloaded = reload.get();
        } catch (IOException | RuntimeException failure) {
            LogUtil.error("Failed to switch " + OBSERVE_ONLY_KEY + " in " + config, failure);
            return CompletableFuture.completedFuture(restore(path, original));
        }
        return reloaded.exceptionally(throwable -> false)
                .thenApply(ok -> ok ? Outcome.APPLIED : restore(path, original));
    }

    // A file left ahead of the live mode would put the next hot-swapped generation in the other mode.
    private static Outcome restore(Path config, byte[] original) {
        try {
            Files.write(config, original);
            return Outcome.FAILED;
        } catch (IOException failure) {
            LogUtil.error("Failed to restore " + config, failure);
            return Outcome.UNRESTORED;
        }
    }

    static void write(File config, boolean observeOnly) throws IOException {
        ConfigPatcher patcher = new ConfigPatcher(config);
        NodePosition position = patcher.getNodePosition(OBSERVE_ONLY_KEY);
        if (position == null) patcher.addMissingValue(OBSERVE_ONLY_KEY, observeOnly);
        else patcher.applyChange(new PendingChange(position, observeOnly));
        patcher.save();
    }

    // Every generation snapshots its config from this file, so a hot-swapped one starts in the written mode.
    private static File configFile() {
        var host = RuntimeEnvironment.host();
        Path directory =
                host == null ? CultAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath() : host.directory();
        return directory.resolve("config.yml").toFile();
    }

    private static String mode() {
        return CultAPI.INSTANCE.getConfigManager().isObserveOnly() ? "observe-only" : "enforcing";
    }
}
