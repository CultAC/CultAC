package ac.cult.cultac.manager.portal;

import ac.cult.cultac.cloud.intake.v3.PortalRole;
import ac.cult.cultac.cloud.intake.v3.TeamRole;
import ac.cult.cultac.cloud.transport.TeamSocket;
import ac.cult.cultac.cloud.wire.IntakeWire;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Tells the intake service's team sync, over the intake sockets, which portal {@link Role} each of this server's
 * players holds. A player still online {@link #JOIN_DELAY_SECONDS} seconds after joining who holds a role is reported;
 * a permission change for a player checked that way reports the new role when it differs; a {@code team_sweep}
 * (LuckPerms only) reports the stored role of the listed players who are not online.
 *
 * <p>Each socket whose connect answered {@code team} keeps its last {@code TeamState}; team sync is on while any open
 * one is enabled. {@link #tick} sends at most one {@code TeamRoles} of {@link #MAX_PER_MESSAGE} reports, on the
 * longest-open enabled socket. Turning on, and losing a socket that carried reports while another stays enabled,
 * reports every watched player again; turning off drops the queue. Every method is safe from any thread and returns
 * without waiting on LuckPerms.
 */
public final class PortalSync {
    static final long JOIN_DELAY_SECONDS = 120;
    static final long TICK_SECONDS = 30;
    /** The least time between two sweeps acted on. */
    static final long SWEEP_EVERY_MS = TimeUnit.HOURS.toMillis(1);
    /** Further {@code team_sweep} replies within this of the sweep acted on are its other parts. */
    static final long SWEEP_PARTS_MS = TimeUnit.MINUTES.toMillis(1);
    static final int MAX_PER_MESSAGE = 100;
    static final int MAX_QUEUED = 500;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** A portal role and the node granting it, lowest first. */
    public enum Role {
        VIEWER("viewer"),
        MODERATOR("moderator"),
        ADMIN("admin");

        private static final Role[] HIGHEST_FIRST = {ADMIN, MODERATOR, VIEWER};

        private final String node;

        Role(String name) {
            this.node = "cult.portal." + name;
        }

        public String node() {
            return node;
        }

        /** {@code role} on the wire; null, holding none, is {@code PORTAL_ROLE_NONE}. */
        static PortalRole wire(@Nullable Role role) {
            if (role == null) return PortalRole.PORTAL_ROLE_NONE;
            return switch (role) {
                case VIEWER -> PortalRole.PORTAL_ROLE_VIEWER;
                case MODERATOR -> PortalRole.PORTAL_ROLE_MODERATOR;
                case ADMIN -> PortalRole.PORTAL_ROLE_ADMIN;
            };
        }

        /** The highest role whose node {@code holds} answers true for; null when it holds none. */
        public static @Nullable Role highest(Predicate<String> holds) {
            for (Role role : HIGHEST_FIRST) {
                if (holds.test(role.node)) return role;
            }
            return null;
        }
    }

    /** The server's online players. */
    public interface Players {
        boolean isOnline(UUID uuid);

        /** The player's name and highest held {@link Role}; null when they are not online. */
        @Nullable
        Online check(UUID uuid);
    }

    /** An online player; {@code role} is null when they hold none. */
    public record Online(String name, @Nullable Role role) {}

    /**
     * What the permission plugin stores for a player who is not online; {@code name} is null when it knows none,
     * {@code role} when they hold none.
     */
    public record Offline(@Nullable String name, @Nullable Role role) {}

    @FunctionalInterface
    public interface OfflineLookup {
        /** Completes with null when the player has no stored data. */
        CompletableFuture<Offline> lookup(UUID uuid);
    }

    /** {@code name} is null only on an offline report, {@code role} when the player holds none. */
    record Report(UUID uuid, @Nullable String name, @Nullable Role role, boolean offline) {}

    /** A team socket's last {@code TeamState}, and whether a {@code TeamRoles} went out on it. */
    private static final class Link {
        boolean enabled;
        boolean carried;

        Link(boolean enabled) {
            this.enabled = enabled;
        }
    }

    private final Players players;
    private final LongSupplier clock;
    private final Supplier<OfflineLookup> offline;
    private final Consumer<String> info;
    private final Consumer<String> debug;

    private final Map<UUID, Report> queue = new LinkedHashMap<>();
    /** Online players whose join check ran; only their permission changes are re-checked. */
    private final Set<UUID> watched = new HashSet<>();
    /**
     * The role last queued for each watched player: null when the last report was no role, absent when none was
     * queued. A resync re-sends every present entry.
     */
    private final Map<UUID, Role> reported = new HashMap<>();
    private final Set<UUID> changed = new LinkedHashSet<>();
    /** The next tick reports each watched player's state whatever was reported before. */
    private boolean resync;
    /** Reports turned away by a full queue since the last tick. */
    private int full;

    /** The open team sockets, longest open first. */
    private final Map<TeamSocket, Link> sockets = new LinkedHashMap<>();
    /** The state last logged; null before the first. */
    private @Nullable Boolean announced;
    private boolean refusalLogged;

    private long nextSweep = Long.MIN_VALUE;
    private long sweepPartsUntil = Long.MIN_VALUE;

    public PortalSync(
            Players players,
            LongSupplier clock,
            Supplier<OfflineLookup> offline,
            Consumer<String> info,
            Consumer<String> debug) {
        this.players = players;
        this.clock = clock;
        this.offline = offline;
        this.info = info;
        this.debug = debug;
    }

    /** Only version-4 (Mojang) UUIDs; offline-mode and Floodgate accounts are never reported. */
    public static boolean accepts(@Nullable UUID uuid) {
        return uuid != null && uuid.version() == 4;
    }

    static boolean validName(@Nullable String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** The join check, {@link #JOIN_DELAY_SECONDS} seconds after the player joined. */
    public void checkJoined(UUID uuid) {
        if (!accepts(uuid)) return;
        Online online = players.check(uuid);
        if (online == null) return;
        synchronized (this) {
            watched.add(uuid);
            if (online.role() != null && online.role() != reported.get(uuid)) apply(uuid, online, false);
        }
    }

    /** A permission plugin's data for {@code uuid} changed; the next tick re-checks a watched player. */
    public synchronized void permissionsChanged(UUID uuid) {
        if (watched.contains(uuid)) changed.add(uuid);
    }

    /** A socket offering team sync opened; {@code enabled} is connect's answer, null when the service took none. */
    public synchronized void opened(TeamSocket socket, @Nullable Boolean enabled) {
        if (enabled == null) {
            if (!refusalLogged) info.accept("Team sync: the cloud does not take it from this key");
            refusalLogged = true;
            return;
        }
        boolean was = enabled();
        sockets.put(socket, new Link(enabled));
        changed(was, enabled);
    }

    /** A {@code team_state} reply on {@code socket}. */
    public synchronized void state(TeamSocket socket, boolean enabled) {
        Link link = sockets.get(socket);
        if (link == null) return;
        boolean was = enabled();
        link.enabled = enabled;
        changed(was, enabled);
    }

    public synchronized void closed(TeamSocket socket) {
        Link link = sockets.remove(socket);
        if (link == null) return;
        if (sockets.isEmpty() && Boolean.TRUE.equals(announced)) announced = null;
        if (!enabled()) {
            if (link.enabled) turnedOff("no enabled socket is open");
            if (!sockets.isEmpty()) announce(false, "Team sync off: the organization has it off");
        } else if (link.carried) {
            reportEveryoneAgain();
            debug.accept("Team sync: a socket that carried reports closed; reporting every watched player again");
        }
    }

    /** Whether any open team socket is enabled. */
    synchronized boolean enabled() {
        for (Link link : sockets.values()) {
            if (link.enabled) return true;
        }
        return false;
    }

    private void changed(boolean was, boolean answered) {
        boolean now = enabled();
        if (!was && now) {
            reportEveryoneAgain();
            announce(true, "Team sync on: reporting portal roles to the cloud");
        } else if (was && !now) {
            turnedOff("the organization has it off");
        }
        if (!now && !answered) announce(false, "Team sync off: the organization has it off");
    }

    private void announce(boolean on, String line) {
        if (announced != null && announced == on) return;
        announced = on;
        info.accept(line);
    }

    private void reportEveryoneAgain() {
        resync = true;
        changed.addAll(watched);
    }

    private void turnedOff(String why) {
        if (!queue.isEmpty()) debug.accept("Team sync dropped " + queue.size() + " queued reports: " + why);
        queue.clear();
        changed.clear();
        resync = false;
    }

    public void tick() {
        List<UUID> recheckNow;
        boolean all;
        int turnedAway;
        synchronized (this) {
            turnedAway = full;
            full = 0;
        }
        if (turnedAway > 0) debug.accept("Team sync queue full; turned away " + turnedAway + " reports");
        synchronized (this) {
            for (Iterator<UUID> it = watched.iterator(); it.hasNext(); ) {
                if (!players.isOnline(it.next())) it.remove();
            }
            reported.keySet().retainAll(watched);
            changed.retainAll(watched);
            if (!enabled()) return;
            recheckNow = new ArrayList<>(changed);
            changed.clear();
            all = resync;
            resync = false;
        }

        for (UUID uuid : recheckNow) {
            Online online = players.check(uuid);
            synchronized (this) {
                if (online == null) watched.remove(uuid);
                else if (all
                        ? online.role() != null || reported.containsKey(uuid)
                        : online.role() != reported.get(uuid)) apply(uuid, online, all);
            }
        }

        List<Report> batch = new ArrayList<>();
        TeamSocket target;
        synchronized (this) {
            target = target();
            if (target == null || queue.isEmpty()) return;
            Iterator<Report> it = queue.values().iterator();
            while (it.hasNext() && batch.size() < MAX_PER_MESSAGE) {
                batch.add(it.next());
                it.remove();
            }
        }
        boolean sent = target.send(message(batch));
        synchronized (this) {
            Link link = sockets.get(target);
            if (sent && link != null) {
                link.carried = true;
            } else if (sent) {
                if (enabled()) reportEveryoneAgain();
            } else if (enabled()) {
                for (Report report : batch) queue.putIfAbsent(report.uuid(), report);
            }
        }
        debug.accept("Team sync " + (sent ? "sent " : "could not send ") + batch.size() + " reports on "
                + target.serverId());
    }

    /** The longest-open enabled socket; null while none is. */
    private @Nullable TeamSocket target() {
        for (Map.Entry<TeamSocket, Link> entry : sockets.entrySet()) {
            if (entry.getValue().enabled) return entry.getKey();
        }
        return null;
    }

    /**
     * A {@code team_sweep} on {@code socket}: with an offline lookup, the stored role of each listed player not online
     * is queued. One sweep is acted on an hour, its other parts included; the rest are ignored.
     */
    public void sweep(TeamSocket socket, List<UUID> listed) {
        OfflineLookup lookup = offline.get();
        if (lookup == null) return;
        long now = clock.getAsLong();
        synchronized (this) {
            Link link = sockets.get(socket);
            if (link == null || !link.enabled) return;
            if (now > sweepPartsUntil) {
                if (now < nextSweep) {
                    debug.accept("Team sync ignored a sweep within the hour");
                    return;
                }
                nextSweep = now + SWEEP_EVERY_MS;
                sweepPartsUntil = now + SWEEP_PARTS_MS;
            }
        }
        for (UUID uuid : listed) {
            if (!accepts(uuid) || players.isOnline(uuid)) continue;
            CompletableFuture<Offline> found;
            try {
                found = lookup.lookup(uuid);
            } catch (RuntimeException e) {
                found = CompletableFuture.failedFuture(e);
            }
            found.whenComplete((stored, failure) -> {
                if (failure != null) {
                    debug.accept("Team sync lookup failed: " + failure.getClass().getSimpleName());
                    return;
                }
                if (stored == null) return;
                String name = validName(stored.name()) ? stored.name() : null;
                synchronized (this) {
                    enqueue(new Report(uuid, name, stored.role(), true));
                }
            });
        }
    }

    private void apply(UUID uuid, Online online, boolean resyncing) {
        if (!validName(online.name())) return;
        if (!enqueue(new Report(uuid, online.name(), online.role(), false))) {
            if (enabled()) {
                changed.add(uuid);
                resync |= resyncing;
            }
            return;
        }
        reported.put(uuid, online.role());
    }

    /** Queues {@code report} while team sync is on and the queue has room. */
    private boolean enqueue(Report report) {
        if (!enabled()) return false;
        if (queue.size() >= MAX_QUEUED && !queue.containsKey(report.uuid())) {
            full++;
            return false;
        }
        queue.put(report.uuid(), report);
        return true;
    }

    /** The {@code TeamRoles} record of {@code batch}. */
    static byte[] message(List<Report> batch) {
        List<TeamRole> roles = new ArrayList<>(batch.size());
        for (Report report : batch) {
            roles.add(IntakeWire.teamRole(report.uuid(), report.name(), Role.wire(report.role()), report.offline()));
        }
        return IntakeWire.team(roles);
    }
}
