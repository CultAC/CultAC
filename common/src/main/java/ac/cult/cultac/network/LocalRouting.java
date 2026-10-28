package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.player.PlatformPlayerFactory;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The routing while no transport is running: this instance's players only, and commands never leave it. */
final class LocalRouting implements NetworkRouting {
    static final String INSTANCE_ID = "local";

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String instanceId() {
        return INSTANCE_ID;
    }

    @Override
    public Optional<NetworkPlayer> locate(String nameOrUuid) {
        PlatformPlayerFactory players = CultAPI.INSTANCE.getPlatformPlayerFactory();
        if (players == null) return Optional.empty();
        PlatformPlayer player;
        try {
            player = players.getFromUUID(UUID.fromString(nameOrUuid));
        } catch (IllegalArgumentException notUuid) {
            player = players.getFromName(nameOrUuid);
        }
        return Optional.ofNullable(player).map(LocalRouting::listed);
    }

    @Override
    public Collection<NetworkPlayer> roster() {
        PlatformPlayerFactory players = CultAPI.INSTANCE.getPlatformPlayerFactory();
        if (players == null) return List.of();
        return players.getOnlinePlayers().stream().map(LocalRouting::listed).toList();
    }

    @Override
    public CompletableFuture<Boolean> run(Sender sender, String line, NetworkPlayer where) {
        if (where.instance().equals(INSTANCE_ID)) {
            sender.performCommand(line);
            return CompletableFuture.completedFuture(true);
        }
        sender.sendMessage(MessageUtil.getParsedComponent(
                sender,
                "network-disabled-command",
                "%prefix% &cNetwork-wide commands are not enabled on this server. Ask a server administrator to"
                        + " configure a messaging backend (Redis or NATS) in the CultAC configuration."));
        return CompletableFuture.completedFuture(false);
    }

    private static NetworkPlayer listed(PlatformPlayer player) {
        return new NetworkPlayer(player.getUniqueId(), player.getName(), INSTANCE_ID, null);
    }
}
