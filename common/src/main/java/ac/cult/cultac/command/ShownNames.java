package ac.cult.cultac.command;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.network.NetworkPlayer;
import ac.cult.cultac.network.NetworkRouting;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.model.PlayerIdentity;
import ac.grim.grimac.api.storage.query.Queries;
import io.leangen.geantyref.TypeToken;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.key.CloudKey;
import org.jetbrains.annotations.Nullable;

/**
 * Online players by the name a server lists them under, which can differ from their account name: the name the
 * sender's own client was given for a player, else the name a player on any instance was given on their own client. A
 * name listed for several players matches none. A command argument that names a player this way takes the account.
 */
public final class ShownNames {
    /** How long the sender's connection or the identity store may take to answer. */
    static final long WAIT_MILLIS = 1_000;

    static final CloudKey<List<Match>> MATCHES = CloudKey.of("cultac:shown-names", new TypeToken<>() {});

    /** The account a shown name matched. */
    public record Match(UUID uuid, String account, String shown) {}

    private ShownNames() {}

    /**
     * The online account {@code sender} sees listed as {@code name}; null when none or several are, or it is listed by
     * account.
     */
    public static CompletableFuture<@Nullable Match> find(Sender sender, String name) {
        if (name.isEmpty()) return CompletableFuture.completedFuture(null);
        NetworkRouting routing = NetworkRouting.current();
        List<Match> listed = listed(
                listedHere(CultAPI.INSTANCE.getPlayerDataManager().getEntries(), name), routing.listedAs(name), name);
        return viewed(sender, name)
                .thenApply(viewed -> choose(
                        viewed,
                        listed,
                        name,
                        id -> routing.locate(id.toString()).orElse(null)));
    }

    /** The profiles the sender's own client was given under {@code name}; none for a sender not connected here. */
    private static CompletableFuture<List<UUID>> viewed(Sender sender, String name) {
        CultPlayer viewer = sender.isPlayer()
                ? CultAPI.INSTANCE.getPlayerDataManager().getPlayer(sender.getUniqueId())
                : null;
        if (viewer == null) return CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<UUID>> listed = new CompletableFuture<>();
        try {
            viewer.user.getCultConnection()
                    .execute(() -> listed.complete(viewer.compensatedEntities.clientPlayerModes.named(name)));
        } catch (RuntimeException closed) {
            return CompletableFuture.completedFuture(List.of());
        }
        return listed.completeOnTimeout(List.of(), WAIT_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * The match for {@code name}: the profile the sender's client lists under it when there is one, else the one player
     * whose own client lists them under it; null when the source that decides names several.
     */
    static @Nullable Match choose(
            List<UUID> viewed, List<Match> here, String name, Function<UUID, @Nullable NetworkPlayer> online) {
        if (viewed.size() > 1) return null;
        if (viewed.size() == 1) return account(viewed.get(0), name, online);
        return here.size() == 1 ? here.get(0) : null;
    }

    /** The players in {@code players} whose own client lists them as {@code name}, other than their account name. */
    static List<Match> listedHere(Collection<CultPlayer> players, String name) {
        List<Match> matches = new ArrayList<>(1);
        for (CultPlayer player : players) {
            String shown = player.shownName;
            String account = player.getName();
            if (shown != null && shown.equalsIgnoreCase(name) && account != null && !shown.equalsIgnoreCase(account))
                matches.add(new Match(player.playerUUID, account, shown));
        }
        return matches;
    }

    /**
     * The players in {@code here}, then those in {@code network} whose own client lists them as {@code name}, one match
     * per account.
     */
    static List<Match> listed(List<Match> here, Collection<NetworkPlayer> network, String name) {
        Map<UUID, Match> matches = new LinkedHashMap<>();
        for (Match match : here) matches.putIfAbsent(match.uuid(), match);
        for (NetworkPlayer player : network) {
            String shown = player.shown();
            if (shown != null && shown.equalsIgnoreCase(name))
                matches.putIfAbsent(player.uuid(), new Match(player.uuid(), player.name(), shown));
        }
        return List.copyOf(matches.values());
    }

    /** {@code uuid}'s online account, when {@code shown} is not its name. */
    static @Nullable Match account(UUID uuid, String shown, Function<UUID, @Nullable NetworkPlayer> online) {
        NetworkPlayer player = online.apply(uuid);
        return player == null || player.name().equalsIgnoreCase(shown)
                ? null
                : new Match(uuid, player.name(), shown);
    }

    /**
     * The stored account named {@code name}, read from the player identities alone: the name resolution chain may end in
     * a link that derives a UUID for any name. Empty without an identity store or when it does not answer in time.
     */
    static CompletableFuture<Optional<UUID>> known(String name) {
        DataStoreLifecycle lifecycle = CultAPI.INSTANCE.getDataStoreLifecycle();
        if (lifecycle == null || !lifecycle.isLoaded()) return CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<UUID>> known = new CompletableFuture<>();
        try {
            DataStore store = lifecycle.dataStore();
            if (store == null) return CompletableFuture.completedFuture(Optional.empty());
            store.query(Categories.PLAYER_IDENTITY, Queries.getPlayerIdentityByName(name))
                    .whenComplete((page, failure) -> known.complete(failure != null || page == null
                            ? Optional.empty()
                            : page.items().stream().map(PlayerIdentity::uuid).findFirst()));
        } catch (RuntimeException unavailable) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return known.completeOnTimeout(Optional.empty(), WAIT_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Keeps {@code match} on {@code context}, for a command that says which shown name it was given. */
    static void remember(CommandContext<Sender> context, Match match) {
        List<Match> matches = new ArrayList<>(context.getOrDefault(MATCHES, List.of()));
        matches.add(match);
        context.store(MATCHES, List.copyOf(matches));
    }

    /** The match kept on {@code context} for {@code account}; null when the command was given the account name. */
    public static @Nullable Match matched(CommandContext<Sender> context, String account) {
        for (Match match : context.getOrDefault(MATCHES, List.<Match>of())) {
            if (match.account().equalsIgnoreCase(account)) return match;
        }
        return null;
    }
}
