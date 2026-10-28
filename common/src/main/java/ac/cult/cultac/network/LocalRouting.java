package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.CloudCommandService;
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
        try {
            return Optional.ofNullable(players.getFromUUID(UUID.fromString(nameOrUuid)))
                    .map(LocalRouting::listed);
        } catch (IllegalArgumentException notUuid) {
            // Platform name lookups may match a prefix of a longer name.
            PlatformPlayer player = players.getFromName(nameOrUuid);
            return player != null && player.getName().equalsIgnoreCase(nameOrUuid)
                    ? Optional.of(listed(player))
                    : Optional.empty();
        }
    }

    @Override
    public Collection<NetworkPlayer> roster() {
        PlatformPlayerFactory players = CultAPI.INSTANCE.getPlatformPlayerFactory();
        if (players == null) return List.of();
        return players.getOnlinePlayers().stream().map(LocalRouting::listed).toList();
    }

    @Override
    public CompletableFuture<Boolean> run(Sender sender, String line, NetworkPlayer where) {
        if (!where.instance().equals(INSTANCE_ID)) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender,
                    "network-disabled-command",
                    "%prefix% &cNetwork-wide commands are not enabled on this server. Ask a server administrator to"
                            + " configure a messaging backend (Redis or NATS) in the CultAC configuration."));
            return CompletableFuture.completedFuture(false);
        }
        PlatformPlayerFactory players = CultAPI.INSTANCE.getPlatformPlayerFactory();
        if (players == null
                || players.getFromUUID(where.uuid()) == null
                || !(CultAPI.INSTANCE.getCommandService() instanceof CloudCommandService commands)) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return CompletableFuture.completedFuture(false);
        }
        return commands.execute(sender, CloudCommandService.onRegisteredRoot(line))
                .handle((ran, failure) -> failure == null)
                .toCompletableFuture();
    }

    private static NetworkPlayer listed(PlatformPlayer player) {
        return new NetworkPlayer(player.getUniqueId(), player.getName(), INSTANCE_ID, null);
    }
}
