package ac.cult.cultac.companion.api;

import ac.grim.grimac.api.config.ConfigManager;
import java.util.List;
import java.util.Map;

/** An empty configuration: every lookup returns its default. The real configuration lives on the proxy. */
final class CompanionConfigManager implements ConfigManager {
    static final CompanionConfigManager INSTANCE = new CompanionConfigManager();

    private CompanionConfigManager() {}

    @Override
    public String getStringElse(String key, String otherwise) {
        return otherwise;
    }

    @Override
    public String getString(String key) {
        return null;
    }

    @Override
    public List<String> getStringList(String key) {
        return List.of();
    }

    @Override
    public List<String> getStringListElse(String key, List<String> otherwise) {
        return otherwise;
    }

    @Override
    public int getIntElse(String key, int otherwise) {
        return otherwise;
    }

    @Override
    public long getLongElse(String key, long otherwise) {
        return otherwise;
    }

    @Override
    public double getDoubleElse(String key, double otherwise) {
        return otherwise;
    }

    @Override
    public boolean getBooleanElse(String key, boolean otherwise) {
        return otherwise;
    }

    @Override
    public <T> T get(String key) {
        return null;
    }

    @Override
    public <T> T getElse(String key, T otherwise) {
        return otherwise;
    }

    @Override
    public <K, V> Map<K, V> getMap(String key) {
        return Map.of();
    }

    @Override
    public <K, V> Map<K, V> getMapElse(String key, Map<K, V> otherwise) {
        return otherwise;
    }

    @Override
    public <T> List<T> getList(String key) {
        return List.of();
    }

    @Override
    public <T> List<T> getListElse(String key, List<T> otherwise) {
        return otherwise;
    }

    @Override
    public boolean hasLoaded() {
        return true;
    }

    @Override
    public void reload() {}
}
