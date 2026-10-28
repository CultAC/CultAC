package ac.cult.cultac.manager.config;

import ac.cult.cultac.CultAPI;
import ac.grim.grimac.api.common.BasicReloadable;
import ac.grim.grimac.api.config.ConfigManager;
import ac.cult.cultac.manager.config.update.ConfigUpdater;
import ac.cult.cultac.manager.config.update.CultConfigSpecs;
import ac.cult.cultac.utils.anticheat.LogUtil;
import github.scarsz.configuralize.DynamicConfig;
import github.scarsz.configuralize.Language;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class ConfigManagerFileImpl implements ConfigManager, BasicReloadable {

    private final DynamicConfig config;
    private boolean initialized = false;

    public ConfigManagerFileImpl() {
        config = new DynamicConfig();
    }

    File getDataFolder() {
        return CultAPI.INSTANCE.getGrimPlugin().getDataFolder();
    }

    private File getConfigFile(String path) {
        return new File(getDataFolder(), path);
    }

    /** Backend ids whose per-backend yml gets loaded + auto-updated alongside the user-facing files. */
    static final List<String> BACKEND_IDS = List.of("sqlite", "mysql", "postgres", "mongo", "redis");

    private void runConfigUpdates() {
        Logger logger = Logger.getLogger("cult-config");
        ConfigUpdater updater = new ConfigUpdater(CultAPI.class, logger);
        // Use the multi-file API so cross-file migrations (e.g. config.yml
        // v9 → v10 lifting history.database.* into database.yml + the
        // matching databases/<id>.yml) can target sibling files via
        // ctx.otherFile(...). Open-ended punishments use only the Cult chain below.
        Map<File, ConfigUpdater.Spec> batch = new LinkedHashMap<>();
        batch.put(getConfigFile("config.yml"), CultConfigSpecs.mainConfig());
        batch.put(getConfigFile("cult.yml"), CultConfigSpecs.cultSettings());
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
            updater.updateAll(batch, langCode);
            Map<File, ConfigUpdater.Spec> cultBatch = new LinkedHashMap<>();
            batch.forEach((file, spec) -> cultBatch.put(file,
                    CultConfigSpecs.cultConfig(spec.resourceDirectory)));
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
            initialized = true;
            config.addSource(CultAPI.class, "config", getConfigFile("config.yml"));
            config.addSource(CultAPI.class, "cult", getConfigFile("cult.yml"));
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
            String lang = languageCode.toUpperCase();
            LogUtil.info("Unknown user language " + lang + ".");
            LogUtil.info("If you fluently speak " + lang + " as well as English, see the GitHub repo to translate it!");
            config.setLanguage(Language.EN);
        }

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
        runConfigUpdates();

        try {
            config.loadAll();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }

    @Override
    public String getStringElse(String key, String otherwise) {
        return config.getStringElse(key, otherwise);
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
        return config.getIntElse(key, other);
    }

    @Override
    public long getLongElse(String key, long otherwise) {
        return config.getLongElse(key, otherwise);
    }

    @Override
    public double getDoubleElse(String key, double otherwise) {
        return config.getDoubleElse(key, otherwise);
    }

    @Override
    public boolean getBooleanElse(String key, boolean otherwise) {
        return config.getBooleanElse(key, otherwise);
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
