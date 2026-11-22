package ac.cult.cultac.command.commands;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

public class CultObserve implements BuildableCommand {

    enum Outcome {
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
        String source = "from " + OBSERVE_ONLY_KEY + " in config.yml";
        context.sender().sendMessage(MessageUtil.miniMessage("%prefix% &f" + mode() + " &7(" + source + ")"));
    }

    private void handleSwitch(Sender sender, boolean observeOnly) {
        switchTo(observeOnly, configFile(), CultReload::reloadAsync)
                .thenAccept(outcome -> sender.sendMessage(MessageUtil.miniMessage(
                        switch (outcome) {
                            case FAILED ->
                                "%prefix% &cFailed to switch; still " + mode()
                                        + " and config.yml is unchanged. See the console.";
                            case UNRESTORED ->
                                "%prefix% &cFailed to switch; still " + mode()
                                        + ", but config.yml could not be restored. See the console.";
                            case APPLIED -> "%prefix% &fNow " + mode() + ".";
                        })));
    }

    /** Writes {@code observe-only} and reloads. */
    static CompletableFuture<Outcome> switchTo(
            boolean observeOnly, File config, Supplier<CompletableFuture<Boolean>> reload) {
        Path path = config.toPath();
        byte[] original;
        byte[] switched;
        synchronized (lock()) {
            try {
                original = Files.readAllBytes(path);
            } catch (IOException failure) {
                LogUtil.error("Failed to read " + config, failure);
                return CompletableFuture.completedFuture(Outcome.FAILED);
            }
            try {
                write(config, observeOnly);
                switched = Files.readAllBytes(path);
            } catch (IOException | RuntimeException failure) {
                LogUtil.error("Failed to switch " + OBSERVE_ONLY_KEY + " in " + config, failure);
                return CompletableFuture.completedFuture(restore(path, original, null));
            }
        }
        CompletableFuture<Boolean> reloaded;
        try {
            reloaded = reload.get();
        } catch (RuntimeException failure) {
            LogUtil.error("Failed to switch " + OBSERVE_ONLY_KEY + " in " + config, failure);
            return CompletableFuture.completedFuture(restore(path, original, switched));
        }
        return reloaded.exceptionally(throwable -> false)
                .thenApply(ok -> ok ? Outcome.APPLIED : restore(path, original, switched));
    }

    // A file left ahead of the live mode would put the next hot-swapped generation in the other mode. A file that no
    // longer reads as switched (null: the switch wrote nothing) has another writer's change, which stays: only
    // observe-only goes back.
    private static Outcome restore(Path config, byte[] original, byte @Nullable [] switched) {
        synchronized (lock()) {
            try {
                if (switched == null || Arrays.equals(switched, Files.readAllBytes(config)))
                    Files.write(config, original);
                else write(config.toFile(), observeOnly(original));
                return Outcome.FAILED;
            } catch (IOException | RuntimeException failure) {
                LogUtil.error("Failed to restore " + config, failure);
                return Outcome.UNRESTORED;
            }
        }
    }

    private static boolean observeOnly(byte[] config) {
        Object loaded = new Yaml().load(new String(config, StandardCharsets.UTF_8));
        return loaded instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get(OBSERVE_ONLY_KEY));
    }

    static void write(File config, boolean observeOnly) throws IOException {
        synchronized (lock()) {
            ConfigPatcher patcher = new ConfigPatcher(config);
            NodePosition position = patcher.getNodePosition(OBSERVE_ONLY_KEY);
            if (position == null) patcher.addMissingValue(OBSERVE_ONLY_KEY, observeOnly);
            else patcher.applyChange(new PendingChange(position, observeOnly));
            patcher.save();
        }
    }

    private static Object lock() {
        return BaseConfigManager.configLock(RuntimeEnvironment.host());
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
