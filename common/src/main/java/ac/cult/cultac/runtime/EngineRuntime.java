package ac.cult.cultac.runtime;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.reload.api.RuntimeInstance;
import io.netty.channel.Channel;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public abstract class EngineRuntime implements RuntimeInstance {
    /** How long a replaced generation waits for its captures to leave before its sockets retire. */
    private static final long SUPERSEDED_DETACH_MILLIS = 2000;

    protected abstract Sender sender(Object nativeSender);

    protected abstract CompletionStage<Void> disconnectChannel(Channel channel);

    @Override
    public CompletionStage<Void> disconnect(Object connection) {
        return connection instanceof Channel channel
                ? disconnectChannel(channel)
                : ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime.disconnectSession(connection);
    }

    @Override
    public void superseded() {
        CloudModule.superseded(SUPERSEDED_DETACH_MILLIS);
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
    public java.util.Map<String, byte[]> configurationFiles() throws Exception {
        var config = CultAPI.INSTANCE.getExternalAPI().getConfigManager();
        return config instanceof ac.cult.cultac.manager.config.ConfigManagerFileImpl files
                ? files.publication()
                : java.util.Map.of();
    }

    @Override
    public Object prepareReload(Object configuration) {
        ac.grim.grimac.api.config.ConfigManager parsed;
        if (configuration != null) parsed = (ac.grim.grimac.api.config.ConfigManager) configuration;
        else {
            var host = RuntimeEnvironment.host();
            parsed = new ac.cult.cultac.manager.config.ConfigManagerFileImpl(host.directory()
                    .resolve("runtime/generations")
                    .resolve(host.generationId())
                    .resolve("reload-" + java.util.UUID.randomUUID())
                    .toFile());
            parsed.reload();
        }
        new ac.cult.cultac.manager.config.BaseConfigManager().load(parsed);
        return parsed;
    }

    @Override
    public Object currentConfiguration() {
        return CultAPI.INSTANCE.getExternalAPI().getConfigManager();
    }

    @Override
    public boolean applyReload(Object configuration) {
        return CultAPI.INSTANCE.getExternalAPI().applyPrepared((ac.grim.grimac.api.config.ConfigManager) configuration);
    }

    @Override
    public java.util.List<String> placeholders() {
        var api = CultAPI.INSTANCE.getExternalAPI();
        var result = new java.util.ArrayList<String>();
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
        return ((CloudCommandService) CultAPI.INSTANCE.getCommandService()).execute(sender(nativeSender), input);
    }

    @Override
    public CompletionStage<java.util.List<String>> suggest(Object nativeSender, String input) {
        return ((CloudCommandService) CultAPI.INSTANCE.getCommandService()).suggest(sender(nativeSender), input);
    }
}
