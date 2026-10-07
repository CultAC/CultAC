package ac.cult.cultac.platform.bukkit;

import net.minecraft.core.RegistryAccess;

/** Host conversion only; normal received registry updates use the owned packet producer. */
final class BukkitWorldRegistries {
    private BukkitWorldRegistries() {}
    /** Converts the host's initial world registries at the Bukkit boundary. */
    static ac.cult.cultac.utils.latency.ClientWorldRegistries.Data read(RegistryAccess registries) {
        var builtins =
                RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var codecRegistries = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(
                        builtins.registries()
                                .filter(entry -> registries.lookup(entry.key()).isEmpty()),
                        registries.registries()))
                .freeze();
        var values = new java.util.HashMap<
                String, java.util.List<ac.cult.cultac.utils.latency.ClientWorldRegistries.Definition>>();
        var tags = new java.util.HashMap<String, java.util.Map<String, java.util.List<String>>>();
        for (var definition : net.minecraft.resources.RegistryDataLoader.SYNCHRONIZED_REGISTRIES)
            if (ac.cult.cultac.utils.latency.ClientWorldRegistries.REGISTRIES.contains(
                    definition.key().identifier().toString()))
                worldData(registries, codecRegistries, definition, values, tags);
        return new ac.cult.cultac.utils.latency.ClientWorldRegistries.Data(values, tags);
    }

    private static <T> void worldData(
            RegistryAccess access,
            RegistryAccess codecs,
            net.minecraft.resources.RegistryDataLoader.RegistryData<T> definition,
            java.util.Map<String, java.util.List<ac.cult.cultac.utils.latency.ClientWorldRegistries.Definition>> values,
            java.util.Map<String, java.util.Map<String, java.util.List<String>>> tags) {
        String name = definition.key().identifier().toString();
        var registry = access.lookupOrThrow(definition.key());
        var nbtOps = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, codecs);
        var jsonOps = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, codecs);
        values.put(
                name,
                registry.listElements()
                        .sorted(java.util.Comparator.comparingInt(holder -> registry.getId(holder.value())))
                        .map(holder -> {
                            var data = definition
                                    .elementCodec()
                                    .encodeStart(nbtOps, holder.value())
                                    .getOrThrow();
                            String json = definition
                                    .elementCodec()
                                    .encodeStart(jsonOps, holder.value())
                                    .getOrThrow()
                                    .toString();
                            return new ac.cult.cultac.utils.latency.ClientWorldRegistries.Definition(
                                    holder.key().identifier().toString(),
                                    new ac.cult.blocksim.environment.ClientWorldDefaults.Entry(
                                            (ac.cult.blocksim.data.nbt.NbtValue.Compound)
                                                    ac.cult.blocksim.data.nbt.CanonicalSnbt.parse(data.toString()),
                                            json));
                        })
                        .toList());
        var namedTags = new java.util.HashMap<String, java.util.List<String>>();
        registry.getTags()
                .forEach(tag -> namedTags.put(
                        tag.key().location().toString(),
                        tag.stream()
                                .map(holder -> holder.unwrapKey()
                                        .orElseThrow()
                                        .identifier()
                                        .toString())
                                .toList()));
        tags.put(name, java.util.Map.copyOf(namedTags));
    }
}
