package ac.cult.cultac.manager;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.manager.init.ReloadableInitable;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Location;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class SpectateManager implements StartableInitable, ReloadableInitable {

    /** How spectating reaches the target: same server, or across servers on a proxy. */
    public interface Router {
        /** The server the player is connected to, or null on a single-server platform. */
        @Nullable
        String serverName(PlatformPlayer player);

        /** Moves the player to {@code server}; completes true once game mode and teleports work there. */
        CompletableFuture<Boolean> connect(PlatformPlayer player, String server);

        CompletableFuture<Boolean> teleportToPlayer(PlatformPlayer spectator, PlatformPlayer target);

        /** The player's game mode and location as their server holds them; completes null when it cannot tell. */
        CompletableFuture<@Nullable ServerState> playerState(PlatformPlayer player);
    }

    /** A player's game mode and location on the server they are connected to. */
    public record ServerState(GameMode gameMode, Location location) {}

    public static final Router LOCAL = new Router() {
        @Override
        public @Nullable String serverName(PlatformPlayer player) {
            return null;
        }

        @Override
        public CompletableFuture<Boolean> connect(PlatformPlayer player, String server) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> teleportToPlayer(PlatformPlayer spectator, PlatformPlayer target) {
            return spectator.teleportAsync(target.getLocation());
        }

        @Override
        public CompletableFuture<@Nullable ServerState> playerState(PlatformPlayer player) {
            return CompletableFuture.completedFuture(new ServerState(player.getGameMode(), player.getLocation()));
        }
    };

    public enum Outcome {
        SPECTATING,
        UNSUPPORTED,
        CONNECT_FAILED,
        TELEPORT_FAILED,
        ALREADY_CONNECTING,
        STATE_UNAVAILABLE
    }

    public static final String ALREADY_CONNECTING_MESSAGE =
            "You are already connecting to a spectate target, please wait.";

    /** {@code started} is true when this request saved the state that stopspectating restores. */
    public record SpectateResult(Outcome outcome, boolean started) {}

    private volatile Router router = LOCAL;
    private final Map<UUID, PreviousState> spectatingPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> hiddenPlayers = ConcurrentHashMap.newKeySet();
    /** The generation of each in-flight cross-server connect; only the current generation acts on its completion. */
    private final Map<UUID, Long> connecting = new ConcurrentHashMap<>();

    private final AtomicLong connectGeneration = new AtomicLong();
    private final Set<String> allowedWorlds = ConcurrentHashMap.newKeySet();

    private boolean checkWorld = false;

    @Override
    public void start() {
        reload();
    }

    @Override
    public void reload() {
        allowedWorlds.clear();
        allowedWorlds.addAll(CultAPI.INSTANCE
                .getConfigManager()
                .getConfig()
                .getStringListElse("spectators.allowed-worlds", new ArrayList<>()));
        checkWorld = !(allowedWorlds.isEmpty()
                || new ArrayList<>(allowedWorlds).get(0).isEmpty());
    }

    public boolean isSpectating(UUID uuid) {
        return spectatingPlayers.containsKey(uuid);
    }

    /** True while a cross-server spectate connect of this staff member is in flight. */
    public boolean isConnecting(UUID uuid) {
        return connecting.containsKey(uuid);
    }

    public boolean shouldHidePlayer(CultPlayer receiver, UUID uuid) {
        return !Objects.equals(uuid, receiver.playerUUID) // don't hide to yourself
                && (spectatingPlayers.containsKey(uuid) || hiddenPlayers.contains(uuid)) // hide if you are a spectator
                && !(receiver.playerUUID != null
                        && (spectatingPlayers.containsKey(receiver.playerUUID)
                                || hiddenPlayers.contains(receiver.playerUUID))) // don't hide to other spectators
                && (!checkWorld
                        || (receiver.platformPlayer != null
                                && allowedWorlds.contains(receiver.platformPlayer
                                        .getWorld()
                                        .getName()))); // hide if you are in a specific world
    }

    /** Null restores {@link #LOCAL}. */
    public void setRouter(@Nullable Router router) {
        this.router = router == null ? LOCAL : router;
    }

    /**
     * Puts the spectator in spectator mode at the target, connecting to the target's server first if needed.
     *
     * <p>{@code onStarted} runs when this request saved the state that stopspectating restores: on the same server
     * before the game mode changes, across servers once the teleport there completed. Across servers it also runs when
     * the player's state there cannot be read after the connect, or the connect fails after the player reached that
     * server, since stopspectating then still returns them. On a single-server platform the teleport is not awaited
     * and the result is {@link Outcome#SPECTATING} once it is requested.
     */
    public CompletableFuture<SpectateResult> spectate(
            PlatformPlayer spectator, PlatformPlayer target, Runnable onStarted) {
        Router route = router;
        String from = route.serverName(spectator);
        String to = route.serverName(target);
        boolean crossServer = from != null && to != null && !from.equals(to);
        PlatformPlayer authority = crossServer ? target : spectator;
        if (!authority.hasCapability(ServerCapability.TELEPORT)
                || !authority.hasCapability(ServerCapability.GAMEMODE)) {
            return done(Outcome.UNSUPPORTED, false);
        }
        UUID id = spectator.getUniqueId();
        if (!crossServer) {
            if (connecting.containsKey(id)) {
                return done(Outcome.ALREADY_CONNECTING, false);
            }
            if (from == null || recordsGameModeOn(id, from)) {
                return spectateHere(route, spectator, target, from, save(spectator, from), onStarted);
            }
            PreviousState existing = spectatingPlayers.get(id);
            Long generation = beginConnect(id);
            if (generation == null) return done(Outcome.ALREADY_CONNECTING, false);
            return route.playerState(spectator)
                    .whenComplete((state, failure) -> {
                        if (failure != null) connecting.remove(id, generation);
                    })
                    .thenCompose(state -> {
                        if (!connecting.remove(id, generation)) {
                            return done(Outcome.CONNECT_FAILED, false);
                        }
                        if (state == null) {
                            return done(Outcome.STATE_UNAVAILABLE, false);
                        }
                        if (existing != null) {
                            existing.target = new TargetMode(from, state.gameMode());
                            return spectateHere(route, spectator, target, from, null, onStarted);
                        }
                        return spectateHere(route, spectator, target, from, save(id, state, from), onStarted);
                    });
        }
        Long generation = beginConnect(id);
        if (generation == null) return done(Outcome.ALREADY_CONNECTING, false);
        boolean alreadySpectating = spectatingPlayers.containsKey(id);
        CompletableFuture<@Nullable ServerState> origin =
                alreadySpectating ? CompletableFuture.completedFuture(null) : route.playerState(spectator);
        return origin.whenComplete((state, failure) -> {
                    if (failure != null) connecting.remove(id, generation);
                })
                .thenCompose(state -> {
                    if (!generation.equals(connecting.get(id))) {
                        return done(Outcome.CONNECT_FAILED, false);
                    }
                    if (!alreadySpectating && state == null) {
                        connecting.remove(id, generation);
                        return done(Outcome.STATE_UNAVAILABLE, false);
                    }
                    PreviousState saved = state == null ? null : save(id, state, from);
                    return connectAndSpectate(route, spectator, target, from, to, generation, saved, onStarted);
                });
    }

    private CompletableFuture<SpectateResult> spectateHere(
            Router route,
            PlatformPlayer spectator,
            PlatformPlayer target,
            @Nullable String from,
            @Nullable PreviousState saved,
            Runnable onStarted) {
        boolean started = saved != null;
        if (started) onStarted.run();
        spectator.setGameMode(GameMode.SPECTATOR);
        CompletableFuture<Boolean> teleported = route.teleportToPlayer(spectator, target);
        if (from == null) return done(Outcome.SPECTATING, started);
        return teleported.thenApply(
                success -> new SpectateResult(success ? Outcome.SPECTATING : Outcome.TELEPORT_FAILED, started));
    }

    private CompletableFuture<SpectateResult> connectAndSpectate(
            Router route,
            PlatformPlayer spectator,
            PlatformPlayer target,
            @Nullable String from,
            String to,
            Long generation,
            @Nullable PreviousState saved,
            Runnable onStarted) {
        UUID id = spectator.getUniqueId();
        boolean started = saved != null;
        PreviousState current = started ? null : spectatingPlayers.get(id);
        boolean restored = current != null && restoreTargetGameMode(current, spectator, from);
        return route.connect(spectator, to)
                .whenComplete((connected, failure) -> {
                    if (failure != null && generation.equals(connecting.get(id)) && restored) {
                        respectate(route, spectator, from);
                    }
                })
                .thenCompose(connected -> {
                    if (!generation.equals(connecting.get(id))) {
                        return done(Outcome.CONNECT_FAILED, false);
                    }
                    if (!connected) {
                        if (to.equals(route.serverName(spectator))) {
                            if (started) onStarted.run();
                            return done(Outcome.TELEPORT_FAILED, started);
                        }
                        if (started) spectatingPlayers.remove(id, saved);
                        if (restored) respectate(route, spectator, from);
                        return done(Outcome.CONNECT_FAILED, false);
                    }
                    return route.playerState(spectator)
                            .exceptionally(failure -> null)
                            .thenCompose(here -> {
                                if (!generation.equals(connecting.get(id))
                                        || started && spectatingPlayers.get(id) != saved) {
                                    return done(Outcome.CONNECT_FAILED, false);
                                }
                                if (here == null) {
                                    if (current != null) current.target = null;
                                    onStarted.run();
                                    return done(Outcome.TELEPORT_FAILED, started);
                                }
                                PreviousState state = spectatingPlayers.get(id);
                                if (state != null) state.target = new TargetMode(to, here.gameMode());
                                spectator.setGameMode(GameMode.SPECTATOR);
                                return route.teleportToPlayer(spectator, target).thenApply(success -> {
                                    if (started) onStarted.run();
                                    return new SpectateResult(
                                            success ? Outcome.SPECTATING : Outcome.TELEPORT_FAILED, started);
                                });
                            });
                })
                .whenComplete((result, failure) -> connecting.remove(id, generation));
    }

    private static CompletableFuture<SpectateResult> done(Outcome outcome, boolean started) {
        return CompletableFuture.completedFuture(new SpectateResult(outcome, started));
    }

    /** True when the player is spectating and stopspectating knows the game mode to restore on {@code server}. */
    private boolean recordsGameModeOn(UUID id, String server) {
        PreviousState state = spectatingPlayers.get(id);
        if (state == null || server.equals(state.server)) return state != null;
        TargetMode target = state.target;
        return target != null && target.server.equals(server);
    }

    /** The state this request saved, or null when the player is already spectating. */
    private @Nullable PreviousState save(PlatformPlayer spectator, @Nullable String server) {
        UUID id = spectator.getUniqueId();
        if (spectatingPlayers.containsKey(id)) return null;
        return save(id, new ServerState(spectator.getGameMode(), spectator.getLocation()), server);
    }

    private @Nullable PreviousState save(UUID id, ServerState state, @Nullable String server) {
        PreviousState saved = new PreviousState(state.gameMode(), state.location(), server);
        return spectatingPlayers.putIfAbsent(id, saved) == null ? saved : null;
    }

    public void onLogin(UUID uuid) {
        hiddenPlayers.add(uuid);
    }

    public void onQuit(UUID uuid) {
        hiddenPlayers.remove(uuid);
        connecting.remove(uuid);
        handlePlayerStopSpectating(uuid);
    }

    // only call this synchronously
    public void disable(@NotNull PlatformPlayer platformPlayer, boolean teleportBack) {
        if (connecting.containsKey(platformPlayer.getUniqueId())) return;
        PreviousState previousState = spectatingPlayers.get(platformPlayer.getUniqueId());
        if (previousState == null) return;
        Router route = router;
        String current = route.serverName(platformPlayer);
        boolean awayFromOrigin =
                previousState.server != null && current != null && !previousState.server.equals(current);
        if (!teleportBack) {
            if (current != null && !platformPlayer.hasCapability(ServerCapability.GAMEMODE)) {
                platformPlayer.sendMessage(Component.text(
                        "Could not restore your game mode on this server right now; try again.", NamedTextColor.RED));
                return;
            }
            if (awayFromOrigin) {
                restoreTargetGameMode(previousState, platformPlayer, current);
                handlePlayerStopSpectating(platformPlayer.getUniqueId());
            } else {
                onDisable(previousState, platformPlayer);
            }
            return;
        }
        UUID id = platformPlayer.getUniqueId();
        if (awayFromOrigin) {
            Long generation = beginConnect(id);
            if (generation == null) return;
            boolean restored = restoreTargetGameMode(previousState, platformPlayer, current);
            route.connect(platformPlayer, previousState.server)
                    .whenComplete((connected, failure) -> {
                        if (restored && !Boolean.TRUE.equals(connected) && generation.equals(connecting.get(id))) {
                            respectate(route, platformPlayer, current);
                        }
                    })
                    .thenCompose(connected -> {
                        if (connected) return teleportBack(previousState, platformPlayer, generation);
                        if (generation.equals(connecting.get(id))) {
                            platformPlayer.sendMessage(Component.text(
                                    "Could not connect you back to " + previousState.server + ", please try again.",
                                    NamedTextColor.RED));
                        }
                        return CompletableFuture.completedFuture(null);
                    })
                    .whenComplete((ignored, failure) -> connecting.remove(id, generation));
        } else if (current != null) {
            // Through a proxy the teleport carries the saved world, which the proxy cannot see as loaded.
            Long generation = beginConnect(id);
            if (generation == null) return;
            teleportBack(previousState, platformPlayer, generation)
                    .whenComplete((ignored, failure) -> connecting.remove(id, generation));
        } else if (previousState.location.isWorldLoaded()) {
            teleportBack(previousState, platformPlayer, null);
        } else {
            onDisable(previousState, platformPlayer);
        }
    }

    /** Holds off new spectate and stop requests until this connect or return completes; null if one is running. */
    private @Nullable Long beginConnect(UUID id) {
        Long generation = connectGeneration.incrementAndGet();
        return connecting.putIfAbsent(id, generation) == null ? generation : null;
    }

    /** Restores the game mode the player had on {@code server} before spectating there; false when none was recorded. */
    private static boolean restoreTargetGameMode(
            PreviousState previousState, PlatformPlayer platformPlayer, @Nullable String server) {
        TargetMode target = previousState.target;
        if (target == null || !target.server.equals(server)) return false;
        platformPlayer.setGameMode(target.gameMode);
        return true;
    }

    /** Puts the player back in spectator mode on {@code server} when an onward connect left them there. */
    private static void respectate(Router route, PlatformPlayer platformPlayer, @Nullable String server) {
        if (Objects.equals(server, route.serverName(platformPlayer))) platformPlayer.setGameMode(GameMode.SPECTATOR);
    }

    /**
     * Through a proxy a failed teleport ends spectating where the player is, if their server can set the game mode. A
     * return with a {@code generation} does nothing once that generation or the spectate state it restores is gone.
     */
    private CompletableFuture<Void> teleportBack(
            PreviousState previousState, PlatformPlayer platformPlayer, @Nullable Long generation) {
        UUID id = platformPlayer.getUniqueId();
        if (generation != null && !returning(id, generation, previousState)) {
            return CompletableFuture.completedFuture(null);
        }
        return platformPlayer.teleportAsync(previousState.location).handle((bool, failure) -> {
            if (generation != null && !returning(id, generation, previousState)) return null;
            if (failure == null && Boolean.TRUE.equals(bool)) {
                onDisable(previousState, platformPlayer);
            } else if (previousState.server != null && platformPlayer.hasCapability(ServerCapability.GAMEMODE)) {
                onDisable(previousState, platformPlayer);
                platformPlayer.sendMessage(Component.text(
                        "Could not teleport you back; your game mode is restored where you are.", NamedTextColor.RED));
            } else if (failure == null || previousState.server != null) {
                platformPlayer.sendMessage(Component.text("Teleport failed, please try again.", NamedTextColor.RED));
            }
            return null;
        });
    }

    /** Whether the return holding {@code generation} is still current and still restores {@code previousState}. */
    private boolean returning(UUID id, Long generation, PreviousState previousState) {
        return generation.equals(connecting.get(id)) && spectatingPlayers.get(id) == previousState;
    }

    private void onDisable(PreviousState previousState, PlatformPlayer platformPlayer) {
        platformPlayer.setGameMode(previousState.gameMode);
        handlePlayerStopSpectating(platformPlayer.getUniqueId());
    }

    public void handlePlayerStopSpectating(UUID uuid) {
        spectatingPlayers.remove(uuid);
    }

    private static final class PreviousState {
        private final GameMode gameMode;
        private final Location location;
        private final @Nullable String server;
        // The player's pre-spectator game mode on the server it last connected to for spectating.
        private volatile @Nullable TargetMode target;

        private PreviousState(GameMode gameMode, Location location, @Nullable String server) {
            this.gameMode = gameMode;
            this.location = location;
            this.server = server;
        }
    }

    private record TargetMode(String server, GameMode gameMode) {}
}
