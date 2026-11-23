package ac.cult.cultac.manager.config;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.config.update.ConfigUpdater;
import ac.cult.cultac.manager.config.update.CultConfigSpecs;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.common.BasicReloadable;
import ac.grim.grimac.api.config.ConfigManager;
import github.scarsz.configuralize.DynamicConfig;
import github.scarsz.configuralize.Language;
import github.scarsz.configuralize.Source;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

public class ConfigManagerFileImpl implements ConfigManager, BasicReloadable {

    private final DynamicConfig config;
    private boolean initialized = false;
    private final File snapshotDirectory;
    private final Path canonicalDirectory;
    private @Nullable Source legacyCultSource;

    // reload() replaces this; a lookup stores into the cache it read first, so one racing a reload is discarded.
    private volatile Lookups lookups = new Lookups();

    private static final class Lookups {
        final Map<String, StringLookup> strings = new ConcurrentHashMap<>();
        final Map<String, IntLookup> ints = new ConcurrentHashMap<>();
        final Map<String, LongLookup> longs = new ConcurrentHashMap<>();
        final Map<String, DoubleLookup> doubles = new ConcurrentHashMap<>();
        final Map<String, BooleanLookup> booleans = new ConcurrentHashMap<>();
    }

    private record StringLookup(String otherwise, String value) {}

    private record IntLookup(int otherwise, int value) {}

    private record LongLookup(long otherwise, long value) {}

    private record DoubleLookup(long otherwiseBits, double value) {}

    private record BooleanLookup(boolean otherwise, boolean value) {}

    public ConfigManagerFileImpl() {
        this(
                RuntimeEnvironment.host() == null
                        ? null
                        : RuntimeEnvironment.host()
                                .directory()
                                .resolve("runtime/generations")
                                .resolve(RuntimeEnvironment.host().generationId())
                                .resolve("config")
                                .toFile());
    }

    public ConfigManagerFileImpl(File snapshotDirectory) {
        this(snapshotDirectory, snapshotDirectory == null ? null : RuntimeEnvironment.host().directory());
    }

    ConfigManagerFileImpl(File snapshotDirectory, Path canonicalDirectory) {
        this.snapshotDirectory = snapshotDirectory;
        this.canonicalDirectory = canonicalDirectory;
        config = new DynamicConfig();
    }

    File getDataFolder() {
        return snapshotDirectory == null ? CultAPI.INSTANCE.getGrimPlugin().getDataFolder() : snapshotDirectory;
    }

    private File getConfigFile(String path) {
        return new File(getDataFolder(), path);
    }

    /** Backend ids whose per-backend yml gets loaded + auto-updated alongside the user-facing files. */
    static final List<String> BACKEND_IDS = List.of("sqlite", "mysql", "postgres", "mongo", "redis");

    private void runConfigUpdates(boolean configFresh) {
        Logger logger = Logger.getLogger("cult-config");
        ConfigUpdater updater = new ConfigUpdater(CultAPI.class, logger);
        // Use the multi-file API so cross-file migrations (e.g. config.yml
        // v9 → v10 lifting history.database.* into database.yml + the
        // matching databases/<id>.yml) can target sibling files via
        // ctx.otherFile(...). Open-ended punishments use only the Cult chain below.
        Map<File, ConfigUpdater.Spec> batch = new LinkedHashMap<>();
        batch.put(getConfigFile("config.yml"), CultConfigSpecs.mainConfig());
        batch.put(getConfigFile("discord.yml"), CultConfigSpecs.discord());
        batch.put(getConfigFile("messages.yml"), CultConfigSpecs.messages());
        batch.put(getConfigFile("database.yml"), CultConfigSpecs.database());
        for (String id : BACKEND_IDS) {
            batch.put(getConfigFile("databases/" + id + ".yml"), CultConfigSpecs.backend(id));
        }
        // Pass the same language Configuralize resolved (after its own
        // all-or-nothing availability check above) so a zh operator gets
        // ZH-comment bundled defaults stamped on disk to match.
        String langCode = config.getLanguage().getCode().toLowerCase();
        try {
            updater.foldLegacyCultFile(getConfigFile("config.yml"), configFresh, langCode);
        } catch (Exception e) {
            LogUtil.warn("Cannot move " + ConfigUpdater.LEGACY_CULT_FILE + " into config.yml, so it is read after"
                    + " config.yml: " + e);
        }
        if (snapshotDirectory != null) publishMergeBackup();
        try {
            updater.updateAll(batch, langCode);
            Map<File, ConfigUpdater.Spec> cultBatch = new LinkedHashMap<>();
            batch.forEach((file, spec) -> cultBatch.put(file, CultConfigSpecs.cultConfig(spec.resourceDirectory)));
            cultBatch.put(getConfigFile("punishments.yml"), CultConfigSpecs.cultConfig("/punishments/"));
            updater.updateAll(cultBatch, langCode);
        } catch (Exception e) {
            LogUtil.warn("ConfigUpdater batch failed — loading on-disk files as-is: " + e);
        }
    }

    @Override
    public void reload() {
        getDataFolder().mkdirs();
        if (!initialized) {
            if (snapshotDirectory != null) {
                var names = configurationNames();
                names.add(ConfigUpdater.CULT_MERGE_BACKUP);
                if (readsCanonicalCultFile()) names.add(ConfigUpdater.LEGACY_CULT_FILE);
                for (var name : names) {
                    var source = canonicalDirectory.resolve(name);
                    var target = snapshotDirectory.toPath().resolve(name);
                    try {
                        if (Files.exists(source)) {
                            Files.createDirectories(target.getParent());
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException failure) {
                        throw new IllegalStateException("Cannot snapshot configuration " + name, failure);
                    }
                }
            }
            initialized = true;
            config.addSource(CultAPI.class, "config", getConfigFile("config.yml"));
            config.addSource(CultAPI.class, "messages", getConfigFile("messages.yml"));
            config.addSource(CultAPI.class, "discord", getConfigFile("discord.yml"));
            config.addSource(CultAPI.class, "punishments", getConfigFile("punishments.yml"));
            // database.yml + per-backend files load through here too; their
            // keys are namespaced under `database:` / `<id>:` wrappers so
            // they don't collide with config.yml / discord.yml / each other
            // when Configuralize merges everything into one keyspace.
            config.addSource(CultAPI.class, "database", getConfigFile("database.yml"));
            for (String id : BACKEND_IDS) {
                config.addSource(CultAPI.class, "databases/" + id, getConfigFile("databases/" + id + ".yml"));
            }
        }

        String languageCode = System.getProperty("user.language").toUpperCase();

        try {
            config.setLanguage(Language.valueOf(languageCode));
        } catch (IllegalArgumentException ignored) { // not a valid language code
        }

        // Logic for system language
        if (!config.isLanguageAvailable(config.getLanguage())) {
            LogUtil.info("Unknown user language " + languageCode + ".");
            LogUtil.info("If you fluently speak " + languageCode
                    + " as well as English, see the GitHub repo to translate it!");
            config.setLanguage(Language.EN);
        }

        if (legacyCultSource != null) {
            config.removeSource(legacyCultSource);
            legacyCultSource = null;
        }
        boolean configFresh = !getConfigFile("config.yml").exists();
        try {
            // Save bundled defaults BEFORE the updater runs so any cross-file
            // migration (e.g. config.yml v9 → v10 lifting history.database.*
            // into database.yml + databases/<id>.yml) can target files that
            // exist on disk. saveAllDefaults(false) is non-overwriting — the
            // operator's existing files keep their current content.
            config.saveAllDefaults(false);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save default config files", e);
        }

        // Cross-version migrations run AFTER Configuralize creates any
        // missing files. Idempotent on already-current configs. Versioned
        // backups (<name>.v<oldVersion>.bak) preserve operator-rollback
        // evidence across stacked migration steps.
        runConfigUpdates(configFresh);
        File legacyCult = getConfigFile(ConfigUpdater.LEGACY_CULT_FILE);
        if (legacyCult.isFile()) {
            // Registered last, it answers only the keys config.yml lacks; its defaults are config.yml's.
            legacyCultSource = new Source(config, CultAPI.class, "config", legacyCult);
            config.addSource(legacyCultSource);
        }

        try {
            config.loadAll();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config", e);
        } finally {
            lookups = new Lookups();
        }
    }

    /**
     * Whether the canonical cult.yml still counts. Builds that predate the fold read only cult.yml, and staff can roll
     * back to one in place, so it stays on disk and is ignored once config.yml owns its cult section.
     */
    private boolean readsCanonicalCultFile() {
        if (!Files.isRegularFile(canonicalDirectory.resolve(ConfigUpdater.LEGACY_CULT_FILE))) return false;
        try {
            if (!new ConfigUpdater(CultAPI.class, Logger.getLogger("cult-config"))
                    .ownsCultSection(canonicalDirectory.resolve("config.yml").toFile(), false)) return true;
        } catch (IOException failure) {
            LogUtil.warn("Cannot compare config.yml's cult section with the bundled default, so "
                    + ConfigUpdater.LEGACY_CULT_FILE + " is read: " + failure);
            return true;
        }
        LogUtil.info(ConfigUpdater.LEGACY_CULT_FILE + " is no longer read, since config.yml holds its settings; it can"
                + " be deleted once no build older than this one will run");
        return false;
    }

    private void publishMergeBackup() {
        var backup = getConfigFile(ConfigUpdater.CULT_MERGE_BACKUP).toPath();
        var published = canonicalDirectory.resolve(ConfigUpdater.CULT_MERGE_BACKUP);
        try {
            if (Files.exists(backup) && !Files.exists(published)) Files.copy(backup, published);
        } catch (IOException failure) {
            LogUtil.warn("Cannot copy " + ConfigUpdater.CULT_MERGE_BACKUP + " to " + canonicalDirectory + ": "
                    + failure);
        }
    }

    private static List<String> configurationNames() {
        var names = new ArrayList<>(
                List.of("config.yml", "messages.yml", "discord.yml", "punishments.yml", "database.yml"));
        for (var id : BACKEND_IDS) names.add("databases/" + id + ".yml");
        return names;
    }

    public Map<String, byte[]> publication() throws IOException {
        if (snapshotDirectory == null) return Map.of();
        var files = new LinkedHashMap<String, byte[]>();
        for (var name : configurationNames()) {
            var path = snapshotDirectory.toPath().resolve(name);
            if (Files.exists(path)) files.put(name, Files.readAllBytes(path));
        }
        return files;
    }

    @Override
    public String getStringElse(String key, String otherwise) {
        Map<String, StringLookup> cache = lookups.strings;
        StringLookup lookup = cache.get(key);
        if (lookup != null && Objects.equals(lookup.otherwise(), otherwise)) return lookup.value();
        String value = config.getStringElse(key, otherwise);
        cache.put(key, new StringLookup(otherwise, value));
        return value;
    }

    @Override
    public @Nullable String getString(String key) {
        return config.getString(key);
    }

    @Override
    public List<String> getStringList(String key) {
        return config.getStringList(key);
    }

    @Override
    public List<String> getStringListElse(String key, List<String> otherwise) {
        return config.getStringListElse(key, otherwise);
    }

    @Override
    public int getIntElse(String key, int other) {
        Map<String, IntLookup> cache = lookups.ints;
        IntLookup lookup = cache.get(key);
        if (lookup != null && lookup.otherwise() == other) return lookup.value();
        int value = config.getIntElse(key, other);
        cache.put(key, new IntLookup(other, value));
        return value;
    }

    @Override
    public long getLongElse(String key, long otherwise) {
        Map<String, LongLookup> cache = lookups.longs;
        LongLookup lookup = cache.get(key);
        if (lookup != null && lookup.otherwise() == otherwise) return lookup.value();
        long value = config.getLongElse(key, otherwise);
        cache.put(key, new LongLookup(otherwise, value));
        return value;
    }

    @Override
    public double getDoubleElse(String key, double otherwise) {
        long otherwiseBits = Double.doubleToRawLongBits(otherwise);
        Map<String, DoubleLookup> cache = lookups.doubles;
        DoubleLookup lookup = cache.get(key);
        if (lookup != null && lookup.otherwiseBits() == otherwiseBits) return lookup.value();
        double value = config.getDoubleElse(key, otherwise);
        cache.put(key, new DoubleLookup(otherwiseBits, value));
        return value;
    }

    @Override
    public boolean getBooleanElse(String key, boolean otherwise) {
        Map<String, BooleanLookup> cache = lookups.booleans;
        BooleanLookup lookup = cache.get(key);
        if (lookup != null && lookup.otherwise() == otherwise) return lookup.value();
        boolean value = config.getBooleanElse(key, otherwise);
        cache.put(key, new BooleanLookup(otherwise, value));
        return value;
    }

    @Override
    public <T> T get(String key) {
        return config.get(key);
    }

    @Override
    public <T> @Nullable T getElse(String key, T otherwise) {
        return config.getElse(key, otherwise);
    }

    @Override
    public <K, V> Map<K, V> getMap(String key) {
        return config.getMap(key);
    }

    @Override
    public @Nullable <K, V> Map<K, V> getMapElse(String s, Map<K, V> map) {
        return config.getMapElse(s, map);
    }

    @Override
    public @Nullable <T> List<T> getList(String path) {
        return config.getList(path);
    }

    @Override
    public @Nullable <T> List<T> getListElse(String path, List<T> otherwise) {
        return config.getListElse(path, otherwise);
    }

    @Override
    public boolean hasLoaded() {
        return initialized;
    }
}
