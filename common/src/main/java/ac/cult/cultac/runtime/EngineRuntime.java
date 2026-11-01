package ac.cult.cultac.runtime;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.manager.config.ConfigManagerFileImpl;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.reload.api.RuntimeInstance;
import ac.grim.grimac.api.config.ConfigManager;
import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public abstract class EngineRuntime implements RuntimeInstance {
    protected abstract Sender sender(Object nativeSender);

    protected abstract CompletionStage<Void> disconnectChannel(Channel channel);

    @Override
    public CompletionStage<Void> disconnect(Object connection) {
        return connection instanceof Channel channel
                ? disconnectChannel(channel)
                : GeyserBedrockBridgeRuntime.disconnectSession(connection);
    }

    @Override
    public void superseded() {
        var host = RuntimeEnvironment.host();
        String generation = host == null ? "0" : host.generationId();
        CloudModule.superseded("-drain-" + generation.substring(0, Math.min(8, generation.length())));
    }

    @Override
    public Object api() {
        return CultAPI.INSTANCE.getExternalAPI();
    }

    @Override
    public int playerCount() {
        return CultAPI.INSTANCE.getPlayerDataManager().getEntries().size();
    }

    @Override
    public boolean isObserveOnly() {
        return CultAPI.INSTANCE.getConfigManager().isObserveOnly();
    }

    @Override
    public boolean failsOpen() {
        return CultAPI.INSTANCE.getConfigManager().failsOpen();
    }

    @Override
    public Map<String, byte[]> configurationFiles() throws Exception {
        var config = CultAPI.INSTANCE.getExternalAPI().getConfigManager();
        return config instanceof ConfigManagerFileImpl files ? files.publication() : Map.of();
    }

    @Override
    public Object prepareReload(Object configuration) {
        ConfigManager parsed;
        if (configuration != null) parsed = (ConfigManager) configuration;
        else {
            var host = RuntimeEnvironment.host();
            parsed = new ConfigManagerFileImpl(host.directory()
                    .resolve("runtime/generations")
                    .resolve(host.generationId())
                    .resolve("reload-" + UUID.randomUUID())
                    .toFile());
            parsed.reload();
        }
        new BaseConfigManager().load(parsed);
        return parsed;
    }

    @Override
    public Object currentConfiguration() {
        return CultAPI.INSTANCE.getExternalAPI().getConfigManager();
    }

    @Override
    public boolean applyReload(Object configuration) {
        return CultAPI.INSTANCE.getExternalAPI().applyPrepared((ConfigManager) configuration);
    }

    @Override
    public List<String> placeholders() {
        var api = CultAPI.INSTANCE.getExternalAPI();
        var result = new ArrayList<String>();
        api.getStaticReplacements()
                .keySet()
                .forEach(key -> result.add(key.equals("%cult_version%") ? key : "%cult_" + key.replace("%", "") + "%"));
        api.getVariableReplacements()
                .keySet()
                .forEach(key -> result.add(
                        key.equals("%player%") ? "%cult_player%" : "%cult_player_" + key.replace("%", "") + "%"));
        return result;
    }

    @Override
    public String placeholder(UUID player, String parameter) {
        var api = CultAPI.INSTANCE.getExternalAPI();
        for (var entry : api.getStaticReplacements().entrySet()) {
            String key = entry.getKey().equals("%cult_version%")
                    ? "version"
                    : entry.getKey().replace("%", "");
            if (parameter.equalsIgnoreCase(key)) return entry.getValue();
        }
        var user =
                player == null ? null : CultAPI.INSTANCE.getPlayerDataManager().getPlayer(player);
        if (user == null) return null;
        for (var entry : api.getVariableReplacements().entrySet()) {
            String key = entry.getKey().equals("%player%")
                    ? "player"
                    : "player_" + entry.getKey().replace("%", "");
            if (parameter.equalsIgnoreCase(key)) return entry.getValue().apply(user);
        }
        return null;
    }

    @Override
    public CompletionStage<Void> execute(Object nativeSender, String input) {
        return ((CloudCommandService) CultAPI.INSTANCE.getCommandService())
                .execute(sender(nativeSender), CloudCommandService.onRegisteredRoot(input));
    }

    @Override
    public CompletionStage<List<String>> suggest(Object nativeSender, String input) {
        return ((CloudCommandService) CultAPI.INSTANCE.getCommandService())
                .suggest(sender(nativeSender), CloudCommandService.onRegisteredRoot(input));
    }

    @Override
    public List<String> commandRoots() {
        return CloudCommandService.roots();
    }
}
