package ac.cult.cultac.network.routing;

import static ac.cult.cultac.manager.config.BaseConfigManager.CHECKS_OFF_KEY;
import static ac.cult.cultac.manager.config.BaseConfigManager.DORMANT_KEY;
import static ac.cult.cultac.manager.config.BaseConfigManager.OBSERVE_ONLY_KEY;
import static ac.cult.cultac.manager.config.BaseConfigManager.PAUSE_PUNISHMENTS_KEY;

import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.routing.NetCommands.Target;
import ac.cult.cultac.network.routing.NetCommands.Term;
import ac.cult.cultac.network.routing.NetworkService.Registration;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.reload.api.HostContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The settings network commands change, {@link #TOGGLE}, {@link #OBSERVE}, {@link #PUNISH} and a {@link #CHECK} key per
 * check by its stable key, held by every instance as one table of {@link Record}s: the newest record of each key and
 * target by {@code (at, id)}, which every instance orders alike. A record targeting {@code all} drops the older records
 * of its key; past {@value #MAX_RECORDS} records, those that decide nothing go first, the {@code all} ones last. A
 * setting's {@link Value} here is that of its newest record whose target names this instance ({@code all},
 * {@code proxies} on a proxy, its id or a pattern matching it) or, for a player, their backend server, else
 * config.yml's. The records of a command {@code network.commands} keeps this instance from taking decide nothing here;
 * the table still keeps and forwards them.
 *
 * <p>A setting command's issuer merges its record and ships it in the command. Every roster carries the table, a full
 * one all of it and a delta the records new since the last. Starting, an instance broadcasts {@value #SYNC} with its
 * table, as it sends one to a peer heard from again after it expired; each receiver merges it and answers with
 * {@value #STATE}, at most once per peer every {@value #SYNC_INTERVAL_MILLIS} ms. Merging is idempotent and does not
 * depend on order, so instances that heard the same records hold the same table.
 *
 * <p>The engine generations of a host share the table, with the config.yml values CultAC last wrote ({@code written}),
 * in one host object of JDK types, {@value #SHARED_KEY}, loaded from {@value #FILE} in the data folder. After a change,
 * on the async scheduler and one change at a time, config.yml takes each value a record decides for this instance, the
 * file is replaced, and every generation is told with {@value #LOCAL_STATE} and runs its {@link Listener}s. A config.yml
 * value that differs from {@code written} at boot or reload was edited by hand: it becomes a record for this instance
 * alone, at the file's modification time. Without a host, the services of a runtime that keep the table in one file
 * share it. Setting commands wait until a peer answered the start-up {@value #SYNC}, or {@value #CATCH_UP_MILLIS} ms
 * passed. Any thread.
 */
public final class NetSettings {
    public static final String TOGGLE = "toggle";
    public static final String OBSERVE = "observe";
    public static final String PUNISH = "punish";
    /** Followed by a check's stable key, the key switching that check. */
    public static final String CHECK = "check:";

    public static final String ON = "on";
    public static final String OFF = "off";

    static final String SYNC = "net.sync";
    static final String STATE = "net.state";
    static final String LOCAL_STATE = "net.local.state";
    /** The records in a roster, {@value #SYNC} and {@value #STATE}. */
    static final String SETTINGS = "settings";
    /** The host-shared {@code AtomicReference<Map<String, Object>>} of the table and {@code written}. */
    static final String SHARED_KEY = "cultac.net.state";

    static final String FILE = "network-state.json";
    /** The {@code by} of a record a hand edit of config.yml made. */
    static final String HAND_EDIT = "config.yml";

    static final int MAX_RECORDS = 512;
    static final int MAX_TARGET_LENGTH = 256;
    static final long SYNC_INTERVAL_MILLIS = 10_000;
    /** How long after start a setting command waits for a peer's {@value #STATE} at most. */
    static final long CATCH_UP_MILLIS = 10_000;
    /** 2200-01-01T00:00:00Z, past which a record's {@code at} is refused: a stamp one past the newest cannot overflow. */
    static final long MAX_AT = 7_258_118_400_000L;

    private static final int MAX_BY_LENGTH = 64;
    private static final int MAX_PEERS = 1024;
    private static final Pattern KEY = Pattern.compile("[a-z]{1,32}(:[A-Za-z0-9._-]{1,64})?");
    private static final Pattern VALUE = Pattern.compile("[a-z0-9_-]{1,16}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    /** Each setting with a config.yml key of its own; a check's is its place in {@code checks-off}. */
    private static final Map<String, String> CONFIG_KEYS =
            Map.of(TOGGLE, DORMANT_KEY, OBSERVE, OBSERVE_ONLY_KEY, PUNISH, PAUSE_PUNISHMENTS_KEY);

    /**
     * One setting as a command set it: key {@code k} to {@code v}, {@code on} or {@code off}, for the target {@code t} as
     * issued, at {@code at} by the issuer's clock, by {@code by}, command id {@code id}. Records order by {@code (at,
     * id)}, the other fields breaking a tie no honest issuer makes.
     */
    public record Record(String k, String v, String t, long at, String by, String id) {
        static final Comparator<Record> ORDER = Comparator.comparingLong(Record::at)
                .thenComparing(Record::id)
                .thenComparing(Record::v)
                .thenComparing(Record::t)
                .thenComparing(Record::by);

        public boolean on() {
            return ON.equals(v);
        }

        boolean newerThan(Record other) {
            return ORDER.compare(this, other) > 0;
        }

        /** The table holds one record per key and target, the target ignoring case. */
        String slot() {
            return k + " " + t.toLowerCase(Locale.ROOT);
        }

        boolean all() {
            return t.equals(NetworkSettings.ALL);
        }

        /** A record of these fields, its target and id written alike everywhere; null when one cannot stand in one. */
        static @Nullable Record of(String k, String v, String t, long at, String by, String id) {
            if (!KEY.matcher(k).matches()
                    || !VALUE.matcher(v).matches()
                    || at <= 0
                    || at > MAX_AT
                    || t.length() > MAX_TARGET_LENGTH) return null;
            Target target = Target.parse(t);
            String name = CONTROL.matcher(by).replaceAll("").strip();
            if (target == null || name.isEmpty()) return null;
            if (name.length() > MAX_BY_LENGTH) name = name.substring(0, MAX_BY_LENGTH);
            try {
                return new Record(
                        k, v, target.text(), at, name, UUID.fromString(id).toString());
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("k", k);
            json.addProperty("v", v);
            json.addProperty("t", t);
            json.addProperty("at", at);
            json.addProperty("by", by);
            json.addProperty("id", id);
            return json;
        }

        /** The record {@code json} describes; null when a field is missing or malformed. */
        static @Nullable Record parse(@Nullable JsonElement json) {
            if (!(json instanceof JsonObject object)
                    || !(object.get("at") instanceof JsonPrimitive at)
                    || !at.isNumber()) return null;
            String k = text(object, "k");
            String v = text(object, "v");
            String t = text(object, "t");
            String by = text(object, "by");
            String id = text(object, "id");
            if (k == null || v == null || t == null || by == null || id == null) return null;
            try {
                return of(k, v, t, at.getAsLong(), by, id);
            } catch (RuntimeException malformed) {
                return null;
            }
        }

        private static @Nullable String text(JsonObject object, String key) {
            return object.get(key) instanceof JsonPrimitive primitive && primitive.isString()
                    ? primitive.getAsString()
                    : null;
        }
    }

    /** Where a setting's {@link Value} comes from. */
    public enum Source {
        RECORD,
        CONFIG
    }

    /**
     * A setting's value and where it comes from: {@code record} is the newest record that names the instance or player,
     * null when none decides.
     */
    public record Value(boolean on, Source source, @Nullable Record record) {}

    /** What a setting command sets: {@code key} on or off. */
    public record Setting(String key, boolean on) {}

    /** What changed: every setting ({@code all}), or those of {@code keys}. */
    public record Change(boolean all, Set<String> keys) {
        public Change {
            keys = Set.copyOf(keys);
        }

        public boolean affects(String key) {
            return all || keys.contains(key);
        }

        public boolean affectsChecks() {
            if (all) return true;
            for (String key : keys) {
                if (key.startsWith(CHECK)) return true;
            }
            return false;
        }
    }

    /** Told after each change; re-reads the values it applies. */
    @FunctionalInterface
    public interface Listener {
        void changed(Change change);
    }

    /** config.yml's values of the settings; {@code checksOff} lists stable keys. */
    record Baseline(boolean dormant, boolean observeOnly, boolean pausePunishments, List<String> checksOff) {
        static final Baseline DEFAULT = new Baseline(false, false, false, List.of());

        Baseline {
            checksOff = List.copyOf(checksOff);
        }

        /** The values as config.yml holds them. */
        Map<String, Object> config() {
            return NetSettings.config(dormant, observeOnly, pausePunishments, checksOff);
        }
    }

    private record Entry(Record record, Target target) {}

    /** The shared state {@code source} as this generation reads it; {@code byKey} newest first. */
    private record View(
            Map<String, Object> source,
            List<Record> records,
            Map<String, List<Entry>> byKey,
            Map<String, Object> written) {
        static View of(Map<String, Object> source) {
            List<Record> records = new ArrayList<>();
            if (source.get("records") instanceof List<?> listed) {
                for (Object each : listed) {
                    Record record = shared(each);
                    if (record != null) records.add(record);
                }
            }
            records.sort(Record.ORDER);
            Map<String, List<Entry>> byKey = new HashMap<>();
            for (int i = records.size() - 1; i >= 0; i--) {
                Record record = records.get(i);
                byKey.computeIfAbsent(record.k(), ignored -> new ArrayList<>())
                        .add(new Entry(record, Target.parse(record.t())));
            }
            return new View(source, List.copyOf(records), byKey, NetSettings.written(source.get("written")));
        }

        private static @Nullable Record shared(Object each) {
            return each instanceof Map<?, ?> map
                            && map.get("k") instanceof String k
                            && map.get("v") instanceof String v
                            && map.get("t") instanceof String t
                            && map.get("at") instanceof Long at
                            && map.get("by") instanceof String by
                            && map.get("id") instanceof String id
                    ? Record.of(k, v, t, at, by, id)
                    : null;
        }

        List<Entry> of(String key) {
            return byKey.getOrDefault(key, List.of());
        }
    }

    /** Without a host, the table of each file this runtime keeps one in. */
    private static final Map<Path, AtomicReference<Map<String, Object>>> UNHOSTED = new ConcurrentHashMap<>();

    private final NetworkService service;
    private final LongSupplier clock;
    private final NetCommands.Local local;
    private final @Nullable Path file;
    private final AtomicReference<Map<String, Object>> shared;
    /** Held while config.yml, {@code written} or the file is written. */
    private final Object configLock;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Long> answered = new ConcurrentHashMap<>();
    private final Map<String, Long> synced = new ConcurrentHashMap<>();
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean persistQueued = new AtomicBoolean();
    private final LogThrottle unscheduled = new LogThrottle();
    private volatile @Nullable View view;
    private volatile Baseline baseline;
    private volatile NetCommands.Options options;
    private volatile long syncedAll = Long.MIN_VALUE;
    /** Whether a peer sent its table in a {@value #STATE}. */
    private volatile boolean stateHeard;

    /** The settings of {@code service}; {@code host} shares the table with the other generations. */
    @SuppressWarnings("unchecked")
    NetSettings(NetworkService service, LongSupplier clock, NetCommands.Local local, @Nullable HostContext host) {
        this.service = service;
        this.clock = clock;
        this.local = local;
        this.file = quietly(local::stateFile, null);
        this.baseline = quietly(local::baseline, Baseline.DEFAULT);
        this.options = quietly(local::options, NetCommands.Options.DEFAULT);
        Path config = quietly(local::configFile, null);
        Supplier<AtomicReference<Map<String, Object>>> load =
                () -> new AtomicReference<>(NetStateFile.read(file, config));
        if (host != null) this.shared = (AtomicReference<Map<String, Object>>) host.shared(SHARED_KEY, load::get);
        else if (file != null)
            this.shared = UNHOSTED.computeIfAbsent(file.toAbsolutePath().normalize(), ignored -> load.get());
        else this.shared = load.get();
        this.configLock = BaseConfigManager.configLock(host);
    }

    void register() {
        service.on(SYNC, this::syncRequested);
        service.on(STATE, envelope -> {
            merge(records(envelope.body()));
            stateHeard = true;
        });
        service.on(RosterCache.TYPE, envelope -> merge(records(envelope.body())));
        service.on(LOCAL_STATE, this::told);
    }

    /** Sends every peer this table, asking for theirs; the service just started. */
    void start() {
        if (!service.enabled()) return;
        syncedAll = clock.getAsLong();
        service.broadcast(SYNC, body(records()));
    }

    /** Stops telling listeners; the service stopped. */
    void close() {
        listeners.clear();
    }

    /**
     * Whether a setting command can be stamped here: a peer answered the start-up {@value #SYNC}, or
     * {@value #CATCH_UP_MILLIS} ms passed since, or the bus is off. Until then a record stamped here may lose to records
     * this instance has not heard yet.
     */
    boolean caughtUp() {
        long started = syncedAll;
        return stateHeard
                || !service.enabled()
                || started == Long.MIN_VALUE
                || clock.getAsLong() - started >= CATCH_UP_MILLIS;
    }

    /** Calls {@code listener} after each change, on the async scheduler, one change at a time; it must not block. */
    public Registration listen(Listener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** {@code key}'s value on this instance. */
    public Value value(String key) {
        return value(key, null);
    }

    /** {@code key}'s value for a player on {@code backend}, their backend server; null gives the instance's value. */
    public Value value(String key, @Nullable String backend) {
        return value(baseline, key, decides(view(), key, backend));
    }

    /**
     * {@code key}'s value for a player on {@code backend}, their backend server, decided from one view of the table. For
     * a player whose backend is not known it is the instance's value while that is every player's, and null while a
     * backend server's record can decide for them.
     */
    public @Nullable Value playerValue(String key, @Nullable String backend) {
        View current = view();
        if (backend == null && backendScoped(current, key)) return null;
        return value(baseline, key, decides(current, key, backend));
    }

    /**
     * Whether {@code key}'s value for a player can differ from the instance's by the backend server they are on: a
     * record of it naming a backend server is newer than every record naming this instance as a whole.
     */
    private boolean backendScoped(View current, String key) {
        NetCommands.Options accepted = options;
        if (!accepted.enabled() || !accepted.accept().contains(command(key))) return false;
        String self = service.instanceId();
        boolean proxy = service.proxy();
        for (Entry entry : current.of(key)) {
            if (entry.target().instanceWide(self, proxy)) return false;
            for (Term term : entry.target().terms()) {
                if (term.kind() == Term.Kind.NAME) return true;
            }
        }
        return false;
    }

    /** {@code key}'s value under {@code baseline} when {@code record} is the newest that names the instance or player. */
    static Value value(Baseline baseline, String key, @Nullable Record record) {
        if (record != null) return new Value(record.on(), Source.RECORD, record);
        return new Value(on(key, baseline.config()), Source.CONFIG, null);
    }

    /**
     * The stable keys of the checks switched off on this instance or, given one, for a player on {@code backend}, their
     * backend server.
     */
    public Set<String> checksOff(@Nullable String backend) {
        View current = view();
        Set<String> recorded = new HashSet<>();
        for (String key : current.byKey().keySet()) {
            if (key.startsWith(CHECK)) recorded.add(key.substring(CHECK.length()));
        }
        return checksOff(baseline, recorded, key -> decides(current, key, backend));
    }

    /**
     * The stable keys of the checks switched off under {@code baseline}, of those it names and {@code recorded}, when
     * {@code decides} gives the newest record of a check's key that names the instance or player.
     */
    static Set<String> checksOff(
            Baseline baseline, Collection<String> recorded, Function<String, @Nullable Record> decides) {
        Set<String> candidates = new TreeSet<>(baseline.checksOff());
        candidates.addAll(recorded);
        Set<String> off = new TreeSet<>();
        for (String check : candidates) {
            String key = CHECK + check;
            if (!value(baseline, key, decides.apply(key)).on()) off.add(check);
        }
        return Collections.unmodifiableSet(off);
    }

    /** Completes once config.yml and the file hold the changes merged before this call. */
    CompletableFuture<Void> settled() {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        serially(() -> settled.complete(null));
        return settled;
    }

    /** The table, oldest first. */
    List<Record> records() {
        return view().records();
    }

    /**
     * The record of a setting command: {@code setting} for {@code target}, by {@code by}, command {@code id}, at this
     * clock or one past the newest record of its key held here, whichever is later, so that it supersedes every record
     * its issuer has seen. Null when an argument cannot stand in a record.
     */
    @Nullable
    Record stamp(Setting setting, String target, String by, String id) {
        long newest = Long.MIN_VALUE;
        for (Entry entry : view().of(setting.key()))
            newest = Math.max(newest, entry.record().at());
        long now = clock.getAsLong();
        return Record.of(
                setting.key(),
                setting.on() ? ON : OFF,
                target,
                newest == Long.MIN_VALUE ? now : Math.max(now, newest + 1),
                by,
                id);
    }

    /** Merges {@code incoming} into the table; the keys whose records changed. Never blocks. */
    Set<String> merge(Collection<Record> incoming) {
        if (incoming.isEmpty()) return Set.of();
        while (true) {
            View current = view();
            Set<String> changed = new HashSet<>();
            List<Record> evicted = new ArrayList<>();
            List<Record> next = merged(current.records(), incoming, changed, evicted);
            if (next == current.records()) return Set.of();
            if (shared.compareAndSet(current.source(), state(next, current.written()))) {
                evicted(evicted);
                changed(changed, false);
                return changed;
            }
        }
    }

    private static void evicted(List<Record> evicted) {
        for (Record record : evicted)
            LogUtil.info("Dropped the network setting " + record.k() + " " + record.v() + " for " + record.t()
                    + " (by " + record.by() + " at " + record.at() + "): the table holds " + MAX_RECORDS
                    + " records at most");
    }

    /**
     * Takes config.yml as just loaded, at boot or reload: its values, {@code network.commands}, and each value edited by
     * hand since CultAC last wrote it, which becomes a record for this instance at the file's modification time.
     */
    void configLoaded() {
        baseline = quietly(local::baseline, baseline);
        options = quietly(local::options, options);
        serially(this::takeHandEdits);
    }

    static List<Record> merged(List<Record> table, Collection<Record> incoming, Set<String> changed) {
        return merged(table, incoming, changed, new ArrayList<>());
    }

    /**
     * {@code table}, oldest first, with {@code incoming} merged: a record replaces an older one of its key and target
     * unless a newer {@code all} record of its key is held, and an {@code all} record drops the older records of its
     * key. Past {@value #MAX_RECORDS}, records go oldest first: first those a newer record of their key covers, which
     * decide nothing, then the others but {@code all} ones, then {@code all} ones. The same list when nothing changes;
     * {@code changed} gains the keys of the records added or dropped, and {@code evicted} the records of {@code table}
     * the limit dropped.
     */
    static List<Record> merged(
            List<Record> table, Collection<Record> incoming, Set<String> changed, List<Record> evicted) {
        if (incoming.isEmpty()) return table;
        Map<String, Record> slots = new HashMap<>();
        Map<String, Record> alls = new HashMap<>();
        for (Record held : table) {
            slots.put(held.slot(), held);
            if (held.all()) alls.merge(held.k(), held, (a, b) -> a.newerThan(b) ? a : b);
        }
        for (Record record : incoming) {
            Record all = alls.get(record.k());
            Record held = slots.get(record.slot());
            if ((all != null && !record.newerThan(all)) || (held != null && !record.newerThan(held))) continue;
            slots.put(record.slot(), record);
            if (record.all()) {
                alls.put(record.k(), record);
                slots.values().removeIf(other -> other.k().equals(record.k()) && record.newerThan(other));
            }
        }
        List<Record> next = new ArrayList<>(slots.values());
        next.sort(Record.ORDER);
        if (next.size() > MAX_RECORDS) {
            List<Record> over = evictable(next);
            next.removeAll(new HashSet<>(over));
            Set<Record> held = new HashSet<>(table);
            for (Record record : over) {
                if (held.contains(record)) evicted.add(record);
            }
        }
        if (next.equals(table)) return table;
        Set<Record> dropped = new HashSet<>(table);
        for (Record record : next) {
            if (!dropped.remove(record)) changed.add(record.k());
        }
        for (Record record : dropped) changed.add(record.k());
        return List.copyOf(next);
    }

    /** The records of {@code records}, oldest first, that go to bring it down to {@value #MAX_RECORDS}. */
    private static List<Record> evictable(List<Record> records) {
        Map<String, List<Entry>> newer = new HashMap<>();
        List<Record> covered = new ArrayList<>();
        List<Record> scoped = new ArrayList<>();
        List<Record> alls = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0; i--) {
            Record record = records.get(i);
            Target target = Target.parse(record.t());
            List<Entry> ofKey = newer.computeIfAbsent(record.k(), ignored -> new ArrayList<>());
            boolean shadowed = false;
            for (Entry entry : ofKey) {
                if (covers(entry.target(), target)) {
                    shadowed = true;
                    break;
                }
            }
            if (shadowed) covered.add(record);
            else if (record.all()) alls.add(record);
            else scoped.add(record);
            ofKey.add(new Entry(record, target));
        }
        List<Record> dropped = new ArrayList<>();
        int excess = records.size() - MAX_RECORDS;
        for (List<Record> newestFirst : List.of(covered, scoped, alls)) {
            for (int i = newestFirst.size() - 1; i >= 0 && dropped.size() < excess; i--)
                dropped.add(newestFirst.get(i));
        }
        return dropped;
    }

    /** Whether {@code wider} names every instance and player {@code narrower} names. */
    static boolean covers(Target wider, Target narrower) {
        for (Term term : narrower.terms()) {
            boolean named = false;
            for (Term by : wider.terms()) {
                if (covers(by, term)) {
                    named = true;
                    break;
                }
            }
            if (!named) return false;
        }
        return true;
    }

    private static boolean covers(Term wider, Term narrower) {
        if (wider.kind() == Term.Kind.ALL || wider.text().equalsIgnoreCase(narrower.text())) return true;
        return wider.kind() == Term.Kind.NAME
                && (narrower.kind() == Term.Kind.NAME || narrower.kind() == Term.Kind.ID)
                && !narrower.pattern()
                && NetCommands.glob(wider.value(), narrower.value());
    }

    /**
     * The records a hand edit of config.yml makes: one for each value {@code config} holds unlike {@code written}, for
     * {@code self} alone at {@code modified}. A value {@code written} lacks was never written and makes none.
     */
    static List<Record> handEdits(Map<String, Object> written, Map<String, Object> config, String self, long modified) {
        List<Record> edits = new ArrayList<>();
        for (Map.Entry<String, String> key : CONFIG_KEYS.entrySet()) {
            Object before = written.get(key.getValue());
            if (before instanceof Boolean && !before.equals(config.get(key.getValue())))
                edit(edits, key.getKey(), on(key.getKey(), config), self, modified);
        }
        if (written.get(CHECKS_OFF_KEY) instanceof List<?> before
                && config.get(CHECKS_OFF_KEY) instanceof List<?> now) {
            for (Object check : now) {
                if (!before.contains(check)) edit(edits, CHECK + check, false, self, modified);
            }
            for (Object check : before) {
                if (!now.contains(check)) edit(edits, CHECK + check, true, self, modified);
            }
        }
        return edits;
    }

    private static void edit(List<Record> edits, String key, boolean on, String self, long modified) {
        String value = on ? ON : OFF;
        String id = UUID.nameUUIDFromBytes(
                        (self + "\n" + key + "\n" + value + "\n" + modified).getBytes(StandardCharsets.UTF_8))
                .toString();
        Record record = Record.of(key, value, self, modified, HAND_EDIT, id);
        if (record != null) edits.add(record);
    }

    /** Whether {@code config}, config.yml's values, sets {@code key} on. */
    static boolean on(String key, Map<String, Object> config) {
        return switch (key) {
            case TOGGLE -> !Boolean.TRUE.equals(config.get(DORMANT_KEY));
            case OBSERVE -> Boolean.TRUE.equals(config.get(OBSERVE_ONLY_KEY));
            case PUNISH -> !Boolean.TRUE.equals(config.get(PAUSE_PUNISHMENTS_KEY));
            default ->
                !key.startsWith(CHECK)
                        || !(config.get(CHECKS_OFF_KEY) instanceof List<?> off
                                && off.contains(key.substring(CHECK.length())));
        };
    }

    /**
     * What {@code key}'s own config.yml key holds while {@code key} is {@code on}: {@code observe-only} is true while
     * observe is on, {@code dormant} and {@code pause-punishments} false while toggle and punish are.
     */
    private static boolean configured(String key, boolean on) {
        return key.equals(OBSERVE) == on;
    }

    /** config.yml's values as {@code written} and the file hold them, {@code checks-off} sorted. */
    static Map<String, Object> config(
            boolean dormant, boolean observeOnly, boolean pausePunishments, Collection<String> checksOff) {
        return Map.of(
                DORMANT_KEY,
                dormant,
                OBSERVE_ONLY_KEY,
                observeOnly,
                PAUSE_PUNISHMENTS_KEY,
                pausePunishments,
                CHECKS_OFF_KEY,
                List.copyOf(new TreeSet<>(checksOff)));
    }

    /** The config.yml values {@code value} holds, those of another type left out. */
    static Map<String, Object> written(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> written = new HashMap<>();
        for (String key : CONFIG_KEYS.values()) {
            if (map.get(key) instanceof Boolean flag) written.put(key, flag);
        }
        if (map.get(CHECKS_OFF_KEY) instanceof List<?> listed) {
            Set<String> off = new TreeSet<>();
            for (Object check : listed) {
                if (check instanceof String name) off.add(name);
            }
            written.put(CHECKS_OFF_KEY, List.copyOf(off));
        }
        return Map.copyOf(written);
    }

    /** The shared state of {@code records} and {@code written}, JDK types only. */
    static Map<String, Object> state(List<Record> records, Map<String, Object> written) {
        List<Map<String, Object>> listed = new ArrayList<>(records.size());
        for (Record record : records)
            listed.add(Map.of(
                    "k",
                    record.k(),
                    "v",
                    record.v(),
                    "t",
                    record.t(),
                    "at",
                    record.at(),
                    "by",
                    record.by(),
                    "id",
                    record.id()));
        return Map.of("records", List.copyOf(listed), "written", Map.copyOf(written));
    }

    static JsonArray json(Collection<Record> records) {
        JsonArray json = new JsonArray();
        for (Record record : records) json.add(record.toJson());
        return json;
    }

    static JsonObject body(Collection<Record> records) {
        JsonObject body = new JsonObject();
        body.add(SETTINGS, json(records));
        return body;
    }

    /** The valid records {@code body} lists under {@value #SETTINGS}, reading at most twice the table's size. */
    static List<Record> records(JsonObject body) {
        return records(body.get(SETTINGS));
    }

    static List<Record> records(@Nullable JsonElement listed) {
        if (!(listed instanceof JsonArray array)) return List.of();
        List<Record> records = new ArrayList<>();
        for (int i = 0; i < Math.min(array.size(), 2 * MAX_RECORDS); i++) {
            Record record = Record.parse(array.get(i));
            if (record != null) records.add(record);
        }
        return records;
    }

    /** {@code peer} was heard from after it expired, first by a message of {@code type}. Receiver thread. */
    void appeared(String peer, String type) {
        if (type.equals(SYNC) || !service.enabled() || !service.current()) return;
        long now = clock.getAsLong();
        long all = syncedAll;
        if ((all != Long.MIN_VALUE && now - all < SYNC_INTERVAL_MILLIS) || !due(synced, peer, now)) return;
        service.send(peer, SYNC, body(records()));
    }

    private void syncRequested(Envelope envelope) {
        merge(records(envelope.body()));
        String peer = envelope.from();
        if (peer.equals(service.instanceId()) || !service.current() || !due(answered, peer, clock.getAsLong())) return;
        service.send(peer, STATE, body(records()));
    }

    /** Whether {@code peer} was sent nothing in the last {@value #SYNC_INTERVAL_MILLIS} ms; records a send now. */
    private static boolean due(Map<String, Long> sent, String peer, long now) {
        if (sent.size() >= MAX_PEERS) sent.values().removeIf(at -> now - at >= SYNC_INTERVAL_MILLIS);
        Long last = sent.get(peer);
        if (last != null && now - last < SYNC_INTERVAL_MILLIS) return false;
        sent.put(peer, now);
        return true;
    }

    private void told(Envelope envelope) {
        JsonObject body = envelope.body();
        boolean all = body.get("all") instanceof JsonPrimitive flag && flag.isBoolean() && flag.getAsBoolean();
        Set<String> keys = new HashSet<>();
        if (body.get("keys") instanceof JsonArray listed) {
            for (JsonElement key : listed) {
                if (keys.size() >= 2 * MAX_RECORDS) break;
                if (key instanceof JsonPrimitive text
                        && text.isString()
                        && KEY.matcher(text.getAsString()).matches()) keys.add(text.getAsString());
            }
        }
        Change change = new Change(all, keys);
        serially(() -> notify(change));
    }

    private View view() {
        Map<String, Object> source = shared.get();
        View current = view;
        if (current == null || current.source() != source) view = current = View.of(source);
        return current;
    }

    private @Nullable Record decides(View current, String key, @Nullable String backend) {
        NetCommands.Options accepted = options;
        if (!accepted.enabled() || !accepted.accept().contains(command(key))) return null;
        String self = service.instanceId();
        boolean proxy = service.proxy();
        for (Entry entry : current.of(key)) {
            if (names(entry.target(), self, proxy, backend)) return entry.record();
        }
        return null;
    }

    /** The network command that sets {@code key}. */
    static String command(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(0, colon);
    }

    /** Whether {@code target} names {@code instance} as a whole or, given one, its player's {@code backend}. */
    static boolean names(Target target, String instance, boolean proxy, @Nullable String backend) {
        if (target.instanceWide(instance, proxy)) return true;
        if (backend == null) return false;
        for (Term term : target.terms()) {
            if (term.kind() == Term.Kind.NAME && NetCommands.glob(term.value(), backend)) return true;
        }
        return false;
    }

    private void takeHandEdits() {
        Path config = quietly(local::configFile, null);
        String self = NetCommands.ID_PREFIX + service.instanceId();
        synchronized (configLock) {
            Map<String, Object> values = null;
            long modified = clock.getAsLong();
            if (config != null && Files.isRegularFile(config)) {
                try {
                    values = NetStateFile.readConfig(config);
                    modified = Files.getLastModifiedTime(config).toMillis();
                } catch (IOException | RuntimeException unreadable) {
                    LogUtil.warn("Could not read " + config + " for the network's shared settings", unreadable);
                    values = null;
                }
            }
            if (values == null) values = baseline.config();
            while (true) {
                View current = view();
                List<Record> evicted = new ArrayList<>();
                List<Record> next = merged(
                        current.records(),
                        handEdits(current.written(), values, self, modified),
                        new HashSet<>(),
                        evicted);
                if (next == current.records() && values.equals(current.written())) break;
                if (shared.compareAndSet(current.source(), state(next, values))) {
                    evicted(evicted);
                    break;
                }
            }
        }
        changed(Set.of(), true);
    }

    private void changed(Set<String> keys, boolean all) {
        if (persistQueued.compareAndSet(false, true))
            serially(() -> {
                persistQueued.set(false);
                persist();
            });
        serially(() -> announce(keys, all));
    }

    /**
     * Writes each value a record decides for this instance into config.yml, then {@code written} and the file. The file
     * names the values being written before config.yml changes, and {@code written} takes them once config.yml holds
     * them: a config.yml left before or after an interrupted or failed write never reads as edited by hand.
     */
    private void persist() {
        Path config = quietly(local::configFile, null);
        synchronized (configLock) {
            View current = view();
            Map<String, Object> base = new HashMap<>(baseline.config());
            base.putAll(current.written());
            Map<String, Object> desired = desired(current, base);
            Map<String, Object> differing = new HashMap<>();
            for (Map.Entry<String, Object> value : desired.entrySet()) {
                if (!value.getValue().equals(base.get(value.getKey()))) differing.put(value.getKey(), value.getValue());
            }
            Map<String, Object> written = desired;
            if (config != null && !differing.isEmpty() && Files.isRegularFile(config)) {
                save(current.records(), current.written(), differing);
                try {
                    NetStateFile.writeConfig(config, differing, configLock);
                } catch (IOException | RuntimeException failure) {
                    LogUtil.warn("Could not write " + differing.keySet() + " to " + config, failure);
                    written = current.written();
                }
            }
            while (!written.equals(view().written())) {
                View now = view();
                if (shared.compareAndSet(now.source(), state(now.records(), written))) break;
            }
            View now = view();
            save(now.records(), now.written(), Map.of());
        }
    }

    /** Replaces the file, unless this instance never had a record and has none. */
    private void save(List<Record> records, Map<String, Object> written, Map<String, Object> writing) {
        if (file == null || (records.isEmpty() && !Files.exists(file))) return;
        try {
            NetStateFile.write(file, records, written, writing);
        } catch (IOException | RuntimeException failure) {
            LogUtil.warn("Could not save the network's shared settings to " + file, failure);
        }
    }

    /**
     * {@code base}, config.yml's values, with each one a record decides for this instance; {@code checks-off} lists only
     * checks of this build, which config.yml can name.
     */
    private Map<String, Object> desired(View current, Map<String, Object> base) {
        Map<String, Object> desired = new HashMap<>(base);
        for (Map.Entry<String, String> key : CONFIG_KEYS.entrySet()) {
            Record record = decides(current, key.getKey(), null);
            if (record != null) desired.put(key.getValue(), configured(key.getKey(), record.on()));
        }
        Set<String> off = new TreeSet<>();
        if (base.get(CHECKS_OFF_KEY) instanceof List<?> listed) {
            for (Object check : listed) off.add(String.valueOf(check));
        }
        for (String key : current.byKey().keySet()) {
            Record record = key.startsWith(CHECK) ? decides(current, key, null) : null;
            if (record == null) continue;
            if (record.on()) off.remove(key.substring(CHECK.length()));
            else off.add(key.substring(CHECK.length()));
        }
        off.removeIf(check -> CheckCatalog.byStableKey(check) == null);
        desired.put(CHECKS_OFF_KEY, List.copyOf(off));
        return Map.copyOf(desired);
    }

    private void announce(Set<String> keys, boolean all) {
        JsonObject body = new JsonObject();
        body.addProperty("all", all);
        JsonArray listed = new JsonArray();
        for (String key : keys) listed.add(key);
        body.add("keys", listed);
        if (!service.sendLocal(LOCAL_STATE, body)) notify(new Change(all, keys));
    }

    private void notify(Change change) {
        for (Listener listener : listeners) {
            try {
                listener.changed(change);
            } catch (RuntimeException failure) {
                LogUtil.warn("A listener of the network's shared settings failed", failure);
            }
        }
    }

    /**
     * Runs {@code task} after the ones before it, through {@link NetCommands.Local#dispatch}; while that refuses, the
     * tasks wait for the next one, never running on the caller's thread, which may be the network receiver.
     */
    private void serially(Runnable task) {
        tasks.add(task);
        drain();
    }

    private void drain() {
        if (!draining.compareAndSet(false, true)) return;
        Runnable run = () -> {
            try {
                Runnable next;
                while ((next = tasks.poll()) != null) {
                    try {
                        next.run();
                    } catch (RuntimeException failure) {
                        LogUtil.warn("Applying the network's shared settings failed", failure);
                    }
                }
            } finally {
                draining.set(false);
                if (!tasks.isEmpty()) drain();
            }
        };
        try {
            local.dispatch(run);
        } catch (RuntimeException unavailable) {
            draining.set(false);
            unscheduled.warn(
                    "dispatch",
                    "Could not schedule applying the network's shared settings; " + tasks.size()
                            + " steps wait for the next change",
                    unavailable);
        }
    }

    private static <T> T quietly(Supplier<T> read, T otherwise) {
        try {
            T value = read.get();
            return value == null ? otherwise : value;
        } catch (RuntimeException unavailable) {
            return otherwise;
        }
    }
}
