package ac.cult.vanilla.interaction;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;

/** Vanilla bootstrap and the model's data registries, in this loader only. */
final class InteractionBindings implements AutoCloseable {
    /**
     * The synchronized registries the narrowed model reads: those default item components
     * reference, painting variants (a placed painting chooses one), and dimension types (with
     * their timelines and clocks) and biomes for the world. Interactions transfer no other item
     * data, so nothing else is consulted.
     */
    static final Set<String> MODEL_REGISTRIES = Set.of(
            "minecraft:damage_type",
            "minecraft:trim_material",
            "minecraft:block_transformer",
            "minecraft:chicken_variant",
            "minecraft:jukebox_song",
            "minecraft:banner_pattern",
            "minecraft:instrument",
            "minecraft:decorated_pot_pattern",
            "minecraft:painting_variant",
            "minecraft:dimension_type",
            "minecraft:timeline",
            "minecraft:world_clock",
            "minecraft:worldgen/biome");

    final RegistryAccess.Frozen registries;

    /** {@code narrowed} loads only {@link #MODEL_REGISTRIES} and compacts the block model. */
    InteractionBindings(boolean narrowed) {
        SharedConstants.tryDetectVersion();
        ModelBootstrap.checkVersion();
        // Vanilla's own switch (used by its data generator): entity and block entity type
        // registration otherwise builds the whole DataFixerUpper schema for data fixing,
        // which this model never performs.
        SharedConstants.CHECK_DATA_FIXER_SCHEMA = false;
        // The loader suppresses Bootstrap's process-wide stream replacement.
        Bootstrap.bootStrap();
        var base = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        RegistryAccess.Frozen data;
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ModelBootstrap.resources()))) {
            TagLoader.loadTagsForExistingRegistries(resources, base).forEach(Registry.PendingTags::apply);
            // Decoded with their network codecs, as a client decodes known-pack entries.
            data = ModelBootstrap.loadRegistries(resources, base, narrowed);
        }
        // Vanilla holds the jar's zip file system (an index of all 18k entries) in a static map.
        // The model reads no vanilla data after this.
        closeVanillaFileSystem();
        registries = new RegistryAccess.ImmutableRegistryAccess(Stream.concat(base.registries(), data.registries()))
                .freeze();
        ModelBootstrap.initializeComponents(registries);
        if (narrowed) ModelCompaction.run();
    }

    private static void closeVanillaFileSystem() {
        try {
            var root = net.minecraft.server.packs.VanillaPackResources.class.getResource(
                    "/" + PackType.SERVER_DATA.getDirectory() + "/.mcassetsroot");
            if (root != null && "jar".equals(root.toURI().getScheme()))
                java.nio.file.FileSystems.getFileSystem(root.toURI()).close();
        } catch (java.nio.file.FileSystemNotFoundException alreadyClosed) {
            // Nothing retained.
        } catch (java.io.IOException | java.net.URISyntaxException failure) {
            throw new IllegalStateException("Unable to release the vanilla data pack", failure);
        }
    }

    @Override
    public void close() {}
}
