package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.command.IsolatedCommandManager;
import ac.cult.cultac.platform.api.command.CommandService;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.sender.Sender;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

final class VelocityCommands implements CloudPlatformCommandArguments {
    private final ProxyServer proxy;
    private final VelocitySenders senders;

    VelocityCommands(ProxyServer proxy, VelocitySenders senders) {
        this.proxy = proxy;
        this.senders = senders;
    }

    CommandService service(Object plugin) {
        return new CloudCommandService(IsolatedCommandManager::new, this);
    }

    @Override
    public ParserDescriptor<Sender, PlayerSelector> singlePlayerSelectorParser() {
        return ParserDescriptor.of(
                StringParser.<Sender>stringParser().parser().flatMapSuccess((context, name) -> {
                    var player = proxy.getPlayer(name).orElse(null);
                    // Suggestions also try this dynamic branch beside literals such as proxyticks.
                    // An offline name is a normal parse failure, not an exceptional future.
                    if (player == null)
                        return ArgumentParseResult.failureFuture(
                                new IllegalArgumentException("Player is not online: " + name));
                    Sender sender = senders.wrap(player);
                    return ArgumentParseResult.successFuture(new PlayerSelector() {
                        @Override
                        public boolean isSingle() {
                            return true;
                        }

                        @Override
                        public Sender getSinglePlayer() {
                            return sender;
                        }

                        @Override
                        public Collection<Sender> getPlayers() {
                            return List.of(sender);
                        }

                        @Override
                        public String inputString() {
                            return name;
                        }
                    });
                }),
                PlayerSelector.class);
    }

    @Override
    public SuggestionProvider<Sender> onlinePlayerSuggestions() {
        return (context, input) -> CompletableFuture.completedFuture(proxy.getAllPlayers().stream()
                .map(player -> Suggestion.suggestion(player.getUsername()))
                .toList());
    }
}
