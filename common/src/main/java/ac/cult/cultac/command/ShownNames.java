package ac.cult.cultac.command;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import io.leangen.geantyref.TypeToken;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.key.CloudKey;
import org.jetbrains.annotations.Nullable;

/**
 * Online players by the name a server lists them under, which can differ from their account name: the name the
 * sender's own client was given for a player, else the name a player here was given on their own client. A command
 * argument that names a player this way takes the account, and the sender is told which account matched.
 */
public final class ShownNames {
    /** How long the sender's connection may take to answer. */
    static final long WAIT_MILLIS = 1_000;

    static final CloudKey<List<Match>> MATCHES = CloudKey.of("cultac:shown-names", new TypeToken<>() {});

    /** The account a shown name matched. */
    public record Match(UUID uuid, String account, String shown) {}

    private ShownNames() {}

    /** The online account {@code sender} sees listed as {@code name}; null when none is, or it is listed by account. */
    public static CompletableFuture<@Nullable Match> find(Sender sender, String name) {
        if (name.isEmpty()) return CompletableFuture.completedFuture(null);
        Match here = listedHere(CultAPI.INSTANCE.getPlayerDataManager().getEntries(), name);
        if (here != null) return CompletableFuture.completedFuture(here);
        CultPlayer viewer = sender.isPlayer()
                ? CultAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId())
                : null;
        if (viewer == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<@Nullable UUID> listed = new CompletableFuture<>();
        try {
            viewer.user.getCultConnection()
                    .execute(() -> listed.complete(viewer.compensatedEntities.clientPlayerModes.named(name)));
        } catch (RuntimeException closed) {
            return CompletableFuture.completedFuture(null);
        }
        return listed.completeOnTimeout(null, WAIT_MILLIS, TimeUnit.MILLISECONDS)
                .thenApply(uuid -> uuid == null
                        ? null
                        : account(uuid, name, id -> NetworkRouting.current().locate(id.toString()).orElse(null)));
    }

    /** The player in {@code players} whose own client lists them as {@code name}, other than their account name. */
    static @Nullable Match listedHere(Collection<CultPlayer> players, String name) {
        for (CultPlayer player : players) {
            String shown = player.shownName;
            String account = player.getName();
            if (shown != null && shown.equalsIgnoreCase(name) && account != null && !shown.equalsIgnoreCase(account))
                return new Match(player.playerUUID, account, shown);
        }
        return null;
    }

    /** {@code uuid}'s online account, when {@code shown} is not its name. */
    static @Nullable Match account(UUID uuid, String shown, Function<UUID, @Nullable NetworkPlayer> online) {
        NetworkPlayer player = online.apply(uuid);
        return player == null || player.name().equalsIgnoreCase(shown)
                ? null
                : new Match(uuid, player.name(), shown);
    }

    /** Keeps {@code match} on {@code context}, to be told to the sender if the command runs. */
    static void remember(CommandContext<Sender> context, Match match) {
        List<Match> matches = new ArrayList<>(context.getOrDefault(MATCHES, List.of()));
        matches.add(match);
        context.store(MATCHES, List.copyOf(matches));
    }

    /** Tells the sender of a command about to run which account each shown name it typed matched. */
    static void tell(CommandContext<Sender> context) {
        Sender sender = context.sender();
        for (Match match : context.getOrDefault(MATCHES, List.<Match>of())) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender,
                    "player-listed-as",
                    "%prefix% &f%shown% &7is &f%player%",
                    Map.of("shown", match.shown(), "player", match.account())));
        }
    }
}
