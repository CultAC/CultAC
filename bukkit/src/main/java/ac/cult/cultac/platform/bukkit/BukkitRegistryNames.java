package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.protocol.data.RegistryNames;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;

/** Exports host IDs once per registry access generation, at the allowed NMS boundary. */
public final class BukkitRegistryNames {
    private record Snapshot(RegistryAccess access, RegistryNames names) {}

    private static volatile Snapshot latest;

    private BukkitRegistryNames() {}

    public static RegistryNames read(RegistryAccess access) {
        var snapshot = latest;
        if (snapshot != null && snapshot.access() == access) return snapshot.names();
        var names = new HashMap<String, List<String>>();
        for (var registry : BuiltInRegistries.REGISTRY)
            names.put(NmsIdentifierUtil.resourceKey(registry.key()), names(registry));
        access.registries()
                .forEach(entry -> names.put(NmsIdentifierUtil.resourceKey(entry.key()), names(entry.value())));
        var result = new RegistryNames(names);
        latest = new Snapshot(access, result);
        return result;
    }

    private static <T> List<String> names(Registry<T> registry) {
        return registry.listElements()
                .sorted(Comparator.comparingInt(holder -> registry.getId(holder.value())))
                .map(holder -> NmsIdentifierUtil.resourceKey(holder.key()))
                .toList();
    }
}
