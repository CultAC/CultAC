package ac.cult.cultac.companion.api;

import ac.grim.grimac.api.storage.backend.BackendProvider;
import ac.grim.grimac.api.storage.backend.BackendRegistry;
import java.util.Set;

/** No storage backends: violation storage runs on the proxy. Registrations are accepted and ignored. */
final class CompanionBackendRegistry implements BackendRegistry {
    private final WarnOnce warnings;

    CompanionBackendRegistry(WarnOnce warnings) {
        this.warnings = warnings;
    }

    @Override
    public void register(BackendProvider provider) {
        warnings.warn(
                "backend",
                "Storage backend '" + provider.id() + "' was registered on the CultAC proxy companion and has no"
                        + " effect; storage runs on the proxy");
    }

    @Override
    public BackendProvider lookup(String id) {
        return null;
    }

    @Override
    public Set<String> registeredIds() {
        return Set.of();
    }
}
